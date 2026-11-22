# tts: read aloud (text prep → G2P → Kitten/Kokoro ONNX or Android TextToSpeech → streamed AudioTrack)

## Purpose
Speaks text (bubble speaker: selection/clipboard; Talk tab: transcript). Port of the prototype's `src/tts/*.js` + `src/lib/tts.js`. eSpeak NG is replaced by misaki dictionaries + rules. One facade: `VoiceApp.instance.readAloud` ([ReadAloud.kt](ReadAloud.kt)). App wiring: [../CLAUDE.md](../CLAUDE.md).

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `ReadAloud.kt` | Main-thread facade. Picks the engine, owns state, ties host + player + system TTS together | `speak`, `stop`, `prepare`, `hearVoice`, `unload`, `unloadIfIdle`, `activeModel`; `state: StateFlow<TtsState>`, `messages: SharedFlow<String>`, `TtsStatus` |
| `TtsHost.kt` | Single `"tts"` worker thread: load + warm up the engine, generate chunk by chunk, cancel | `load`, `speak(id, …)`, `cancel`, `unload`, `loadedModelId`, `Listener`, `trimSilence` |
| `VoiceEngines.kt` | StyleTTS 2 ONNX engines and voice-data parsing | `VoiceEngine`, `KittenEngine`, `KokoroEngine` (`create(dir, stage)`), `Lexicons`, `Npy`, private `runStyleTts` |
| `G2P.kt` | Text → misaki phonemes; misaki → eSpeak-style IPA; letter-to-sound fallback | `G2P.phonemize`/`word`, `EspeakStyle.fromMisaki`, `LetterRules.convert` |
| `Lexicon.kt` | Loads misaki gold/silver JSON into a `HashMap` with a streaming parser | `Lexicon.load(vararg File)`, `Lexicon.parseInto`, private `JsonStream` |
| `TextPrep.kt` | `normalize` + sentence chunking (line-by-line port of `text.js`); number words | `TextPrep.normalize`/`chunks`, `NumberWords.expand`/`cardinal`/`ordinal` |
| `TtsPlayer.kt` | One streaming float-PCM `AudioTrack` per read, with audio focus | `TtsPlayer.Playback(sampleRate, onDone)`: `enqueue`, `endOfInput`, `stop` |
| `SystemTts.kt` | Android `TextToSpeech` backend (no download, any installed language) | `SystemTts.speak(chunks, speed, Listener)`, `stop`, `shutdown` |

## How it works
**Callers (all main thread):** [../dictation/DictationService.kt](../dictation/DictationService.kt) `onSpeaker` (`stop` if reading; else `speak(selectedText)`; else `prepare()` then `speak` the clipboard text from `ClipboardGrabActivity`). [../ui/home/HomeScreen.kt](../ui/home/HomeScreen.kt) dock button (`speak(transcript)`/`stop`). [../ui/models/ModelsScreen.kt](../ui/models/ModelsScreen.kt) and [../ui/settings/SettingsScreen.kt](../ui/settings/SettingsScreen.kt) (`hearVoice`, and `unload` before deleting models). [../dictation/DictationController.kt](../dictation/DictationController.kt) and [../ui/home/InAppDictation.kt](../ui/home/InAppDictation.kt) call `stop()` before recording. [../VoiceApp.kt](../VoiceApp.kt) runs idle/trim-memory unloads.

**`ReadAloud.speak(text)`:**
1. `TextPrep.chunks(text)` runs on main: `normalize` (quotes, titles, years, times, money, decimals), split into paragraphs then sentences, ≤300 chars each, each ends in punctuation. If the first chunk is >48 chars, it is cut at its first `, ; : —` (≥16 chars each side) so the first audio is ready sooner. Returns false if there are no chunks.
2. `stop()` ends the previous read. `activeModel()` = `settings.ttsModelId`'s model if `modelStore.isReady(it)`, else `system-tts`. `TtsState.fallback` is set when a chosen model is not downloaded yet.
3. **System path:** `SystemTts.speak(chunks, ttsSpeed)` → `setSpeechRate(speed)`, first chunk `QUEUE_FLUSH`, then `QUEUE_ADD`, utterance ids `voice-<session>-<i>`. The engine is created lazily; `speak` waits for its init callback (`pending`).
4. **ONNX path:** `ensureLoaded(model)` → `host.load(model, modelStore.dir(model), loadListener)`. Then `player.Playback(24000)` is created. Then `host.speak(id, chunks, model.voice(ttsVoice), ttsSpeed, listener)`. Both go to the same single-thread executor, so the load finishes before the speak starts.
5. On the `tts` thread, for each chunk: `engine.generate` → `trimSilence(lead 0.06 s, tail 0.35 s if the chunk ends in `.!?…` (+closing quotes/brackets), else 0.12 s)` → `onAudio` → `pb.enqueue`. A `LinkedBlockingQueue` feeds the `tts-play` thread (MAX_PRIORITY), which does blocking `AudioTrack.write`. This is the streaming: chunk 1 plays while chunks 2..n are generated. `onStats` = total seconds of audio ÷ total seconds of compute. `onDone` → `pb.endOfInput()`. The player then waits up to 15 s for `playbackHeadPosition` to reach the end, releases the track and focus, and calls `onDone`, which runs `ReadAloud.finished()`.
6. **Engines** ([VoiceEngines.kt](VoiceEngines.kt)). Both call `runStyleTts`: inputs `input_ids` int64 [1,n], `style` float [1,dim], `speed` float [1]; output[0] = 24 kHz mono float. Sessions come from `Ort.session(file, Ort.bigThreads)` in [../asr/Engines.kt](../asr/Engines.kt).
   - **Kitten** (`kitten-nano`, `onnx/model.onnx`, `voices.npz`): `Lexicons.forAccent("US")` → `G2P.phonemize` → `EspeakStyle.fromMisaki` (A→eɪ, I→aɪ, O→oʊ, W→aʊ, Y→ɔɪ, Q→əʊ, ʤ→dʒ, ʧ→tʃ, əɹ→ɚ, ɜɹ→ɜː, stressed i/u/ɑ/ɔ get ː) → `kittenTokens` (Python `\w+|[^\w\s]` tokens joined by spaces) → `kittenIds` = `[0, …SYMBOLS…, 10, 0]`. Style row = `min(codepoints(chunk), rows-1)` of the voice's `.npy`. Speed = `speed * voice.prior`. Drops the last 5000 samples if the output is >24000 samples.
   - **Kokoro** (`kokoro-q8`, `onnx/model_quantized.onnx`, `tokenizer.json`, `voices/<id>.bin`): `forAccent(voice.accent)` (US or UK lexicon) → misaki phonemes used as-is → `kokoroIds` (single-char keys of `tokenizer.json` `model.vocab`; unknown chars dropped; ≤510 phoneme ids + 2 pads). Style = row `clamp(ids-2, 0, 509)` of the raw little-endian float32 [510×256] `.bin`, cached per voice. Speed is not scaled.
   - **G2P** (`G2P.phonemize`): first `NumberWords.expand` (ordinals, %, digits → words; leading-zero or >15-digit numbers are read digit by digit; `& @ + = %` → words). Then for each word: lexicon lookup (raw, lowercase, Capitalized) → mixed letters/digits → possessive `'s` → 2–5-letter all-caps acronym spelled out → camelCase → stems (-s/-es/-ies/-ed/-ied/-ing/-ly) → compound split → `LetterRules`. Punctuation in `KEEP` stays in place (the models use it for pauses and intonation).
7. **Load** (`TtsHost.load`): if the same id is already loaded it just calls `onReady`. Otherwise it closes the old engine and calls `create` (stages "Reading the voice model…" / "Loading the dictionary…" (US lexicon) / "Preparing the voice…"). Then it warms up with `generate("Hello there.", voices.first(), 1f)`, then calls `onReady`. Stage strings → `TtsState.stage`, which the HomeScreen status line shows.
8. **State:** host, player and `SystemTts` callbacks arrive off the main thread. ReadAloud `Handler(mainLooper).post`s them and checks `currentId == id` first, so a superseded read can't change the state. Consumers: DictationService (`bubble.setReading`, `messages` → `bubble.showPreview`), HomeScreen (status line, toast), ModelsScreen (loading/busy), VoiceApp (idle unload).
9. **Unload:** `VoiceApp.unloadWhenIdle` waits while `!reading && status == READY && modelId != null` for `modelUnloadSec` (if enabled), then calls `unloadIfIdle()`. `onTrimMemory(TRIM_MEMORY_COMPLETE)` does the same. `unload()` = `stop` + `host.unload` (closes the `OrtSession`) + reset to `TtsState()`. The next `speak`/`prepare` reloads. Changing `ttsModelId` in Settings loads nothing; loading happens only in `speak`/`prepare`.

## Invariants & gotchas
- `ReadAloud`: main thread only. It has no locks: `currentId`, `playback` and `requestedModelId` are plain fields. Engines and `Lexicons` are touched only on `TtsHost`'s thread.
- The ONNX env is shared with ASR (`Ort` in asr/Engines.kt; its "AsrHost's thread" comment is stale for TTS). The `tts` thread runs at NORM+1 and `asr` at NORM+2, so ASR wins. Always `readAloud.stop()` before recording: the mic must never record the phone reading aloud, and the CPU contention would slow ASR.
- Cancellation: `TtsHost.current` is `@Volatile`. `speak()` sets it before posting, so a newer `speak`/`cancel` takes effect even before the task runs. It is checked only between chunks: the chunk being generated (up to 300 chars) still finishes, and its audio is discarded.
- Ids: `nextId` is pre-incremented (first id is 2); 0 = none. A load error sets `currentId = 0` on purpose. That suppresses the queued speak's "No voice model loaded" error, so the ERROR state stays visible. `ensureLoaded` skips the reload when `requestedModelId` matches, unless the status is ERROR.
- `Playback(24000)` is hard-coded in ReadAloud. The `sampleRate` passed to `onAudio` is ignored. An engine at another rate would play at the wrong pitch.
- The `Playback` (audio focus `GAIN_TRANSIENT_MAY_DUCK`, `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH`) starts before the model finishes loading, so other audio is ducked during the load. Focus `LOSS`/`LOSS_TRANSIENT` → `Playback.stop()` only. `TtsHost` keeps generating the rest of the text (the enqueues become no-ops) until the next `speak`/`cancel`.
- `stop()` during a load does not cancel the load. `finished()` keeps the status at LOADING, then `onReady` sets READY.
- `host.loadedModelId` is read on main but is not volatile. This is benign: at worst the status shows LOADING until the first `onAudio`.
- Kitten uses only the US lexicon, and its download has no `gb_*.json`. A UK `forAccent` for Kitten would throw. Kokoro loads the GB lexicon lazily, on the `tts` thread, during the first UK-voice chunk, so that first chunk is late. Each model dir has its own copy of the US lexicon. Lexicon files come from a pinned misaki commit on GitHub (see `MISAKI` in [../asr/ModelCatalog.kt](../asr/ModelCatalog.kt)) into `filesDir/models/<id>@<rev>/lexicon/`.
- Kitten `SYMBOLS`: the id is the char's index in `PAD+PUNCT+LETTERS+IPA`, and later duplicates win (`"`=15, space=16, `ᵻ`=177). End token 10 = `…`. Don't reorder or "fix" these strings; `VoiceEnginesTest` pins them.
- The Kitten style row is keyed on text length; the Kokoro row on token count (same as kitten.js / kokoro.js). `Voice.prior` (0.8, Hugo 0.9) affects Kitten only.
- `TextPrep.normalize`/`chunks` must stay equivalent to the prototype's `src/tts/text.js` (outside this git repo, at `../prototype` from the repo root). Watch JS-vs-Java regex differences (lookbehind, `\b`, `\s`). `NumberWords` is Android-only (it replaces eSpeak's number reading), is not in the golden test, and runs inside `G2P.phonemize`. So the system TTS gets normalized text that still has digits, and reads them itself.
- Pauses between chunks come only from the `trimSilence` tails (values copied from the prototype's `worker.js`). Changing `splitFirst` or the tails changes rhythm and time to first audio.
- `Lexicon`: the earlier file wins (gold over silver), null entries are skipped, and heteronyms → `DEFAULT`, else the first variant. `JsonStream` only handles misaki's flat shape; it is not a general JSON parser.
- `SystemTts`: no `setLanguage` (the engine's default language is used). A failed init is retried on the next `speak`. `shutdown()` is never called: `unload()` doesn't release the system engine. Its callbacks come on binder threads.
- `TtsState.speed/index/total/fallback/error` are published, but no UI reads them today. Only `status`, `stage`, `reading` and `modelId` are consumed.
- Error text: `OutOfMemoryError` → "Not enough memory for this voice model."; anything else → the first line of the message, ≤200 chars.

## How to extend
- **Add a voice to Kitten/Kokoro:** add a `Voice(id, label, gender, accent, prior)` in [../asr/ModelCatalog.kt](../asr/ModelCatalog.kt). For Kitten, the id must be a `.npy` name inside `voices.npz`, and `prior` comes from `kitten_config.json`. For Kokoro, add it to `KOKORO_VOICES`; its `voices/<id>.bin` file is added automatically. `accent = "UK"` selects the GB lexicon. Adding a file means `ModelStore.isReady` (it checks exact sizes) reports existing installs as not ready, so they fall back to the system voice until they re-download.
- **Add an ONNX voice engine:**
  1. Add an `EngineKind` and a `VOICE_MODELS` entry (`kind = ModelKind.VOICE`, pinned `base`, exact file sizes, `voices`) in ModelCatalog.kt.
  2. Implement `VoiceEngine` with `companion create(dir, stage)` in VoiceEngines.kt.
  3. Add a branch to the `when` in `TtsHost.load`.
  4. Check that `AsrHost.load` still ignores it (its `else -> return` does this).
  5. If its rate isn't 24 kHz, pass the real rate to `Playback` in `ReadAloud.speak`.
  6. Add id/tokenizer tests to `VoiceEnginesTest`.
- **Change text normalization or chunking:** change the prototype's `text.js` and `TextPrep.kt` together. Then regenerate `textprep_golden.json` by running the prototype's `normalize()`/`chunks()` in Node (no generator script is checked in), and add the new case.
- **Pronunciation fix:** to add a rule, edit the fallback order in `G2P.word`, `stems`, or `LetterRules.PATTERNS`. For Kitten-only IPA, edit `EspeakStyle`. Add a case to `G2PTest`/`EspeakStyleTest`. Prefer fixing the fallbacks: the dictionaries are downloaded data.
- **New state for the UI:** add the field to `TtsState`, set it in ReadAloud through a `main.post` guarded by `currentId == id`, then read it in DictationService / HomeScreen / ModelsScreen.

## Tests
In [app/src/test/java/app/voice/dictate/tts/](../../../../../../test/java/app/voice/dictate/tts/) (JVM unit tests, `gradlew testDebugUnitTest`; see [../../../../../../test/CLAUDE.md](../../../../../../test/CLAUDE.md)):
- `TextPrepTest.kt`: `TextPrepTest` checks `normalize`/`chunks` against `test/resources/textprep_golden.json` (20 cases from the JS prototype). `NumberWordsTest` covers cardinals, ordinals and `expand`.
- `G2PTest.kt`: `LexiconTest` (heteronyms/null/escapes, gold-over-silver), `G2PTest` (inline mini-lexicon: inflections, acronyms, camelCase, compounds, numbers, `LetterRules`), `EspeakStyleTest`. `realDictionaryCoverage` runs only when the env var `MISAKI_DIR` points at the misaki data; otherwise it is skipped.
- `VoiceEnginesTest.kt`: Kitten tokens/ids/symbol table, Kokoro ids and `styleRow`, `Npy`/`.npz` parsing, `TtsHost.trimSilence`.
- Not covered: `ReadAloud`, `TtsHost` threading/cancel, `TtsPlayer`, `SystemTts`, and real ONNX inference (these need a device).
