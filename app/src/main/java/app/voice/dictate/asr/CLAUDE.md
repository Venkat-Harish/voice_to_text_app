# asr: on-device speech-to-text engines, the model catalog, and model download/storage

## Purpose
Runs the speech models (Nemotron, Parakeet, Moonshine via ONNX Runtime, or Android's `SpeechRecognizer`) and turns mic
audio into `AsrEvent`s. Also owns the catalog, disk store and WorkManager downloads for **every** model, read-aloud
voices included ([../tts/CLAUDE.md](../tts/CLAUDE.md) consumes those).

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `AsrHost.kt` | Owns the one loaded on-device model; runs everything on a single `asr` thread; publishes state | `AsrHost.load/unload/unloadIfIdle/setLanguage/start/accept/stop`, `state: StateFlow<AsrState>`, `errors: SharedFlow<String>`, `AsrStatus` |
| `Engines.kt` | Engine interfaces, event types, shared ONNX Runtime helpers (also used by `tts/`) | `AsrEvent.Partial/Commit`, `Emit`, `StreamTarget`, `ClipEngine`, `Ort` (`env`, `bigThreads`, `session`, tensor builders), `Result.get(name, fallbackIndex)` |
| `Dictation.kt` | One push-to-talk session, either backend; main-thread callbacks | `DictationListener`, `DictationSession`, `OnnxDictation`, `SystemDictation`, `TextAccumulator` |
| `NemotronEngine.kt` | Cache-aware streaming RNNT (word-by-word, never rewritten) | `NemotronEngine.create(model, dir, stage)`, implements `StreamTarget` |
| `ParakeetEngine.kt` | Full-utterance greedy RNNT clip engine (preprocessor → encoder → decoder_joint) | `ParakeetEngine.create(dir, stage)`, `ClipEngine.transcribe` |
| `MoonshineEngine.kt` | Encoder + merged KV-cache decoder clip engine; its tokenizer | `MoonshineEngine.create(dir, stage)`, `MoonshineTokenizer.load/decode` |
| `Segmenter.kt` | Energy VAD that makes a `ClipEngine` near-live (re-transcribe while speaking, commit on pause) | `Segmenter(engine): StreamTarget` |
| `LogMel.kt` | Incremental 128-bin log-mel features for Nemotron; global audio constants | `LogMel.accept`, `SAMPLE_RATE`, `MEL_BINS`, `SILENT_LOG` |
| `Tokens.kt` | SentencePiece `piece id` vocab parsing, piece→text, argmax | `Tokens.parse/piecesToText/argmax` |
| `ModelCatalog.kt` | Static list of all speech + voice models, languages, RAM recommendation | `SpeechModel`, `ModelFile`, `EngineKind`, `ModelKind`, `Voice`, `Language`, `NemotronConfig`, `ModelCatalog.byId/speechById/voiceModelById/recommendedFor`, `fmtBytes` |
| `ModelStore.kt` | On-disk layout, readiness, resumable HTTP download, pruning | `ModelStore.dir/status/isReady/download/delete/pruneStale/usedBytes/freeBytes`, `StoredStatus` |
| `ModelDownloads.kt` | UI-facing download state + the WorkManager worker | `ModelRepository.states/storage/download/cancel/delete/deleteAll/refresh`, `ModelDownloadWorker`, `ModelStatus`, `ModelState` |

## How it works
**Wiring** ([../VoiceApp.kt](../VoiceApp.kt)): `onCreate` builds `ModelStore` → `ModelRepository(this, store, scope)`
→ `AsrHost(store)`. `scope` is `Main.immediate + SupervisorJob`. VoiceApp collects `settings.modelId` →
`loadSelected()` (calls `asr.load(id)` on `Dispatchers.IO`), `settings.language` → `asr.setLanguage`, and unloads
after `modelUnloadSec` of `READY && backend != "system"` (`asr.unloadIfIdle()`, also on `TRIM_MEMORY_COMPLETE`).
`wakeSpeechModel()` reloads when `IDLE` (it is called from `dictation/DictationService` on focus/keyboard/bubble touch
and from `ui/home/HomeScreen`). Settings keys (`modelId` default `"system"`, `language` default `"en-US"`) live in
[../data/CLAUDE.md](../data/CLAUDE.md); `modelId` is validated with `ModelCatalog.speechById`.

**Backend choice** (in callers, not here): `dictation/DictationController.begin()` and
`ui/home/InAppDictation.start()` pick `SystemDictation` when `ModelCatalog.byId(modelId)?.engine` is `SYSTEM` or null,
else `OnnxDictation(app.asr, this)`. If the host is `IDLE`/`LOADING` but the files are ready, the bubble calls
`wakeSpeechModel()` and asks the user to retry (it doesn't wait).

**Load** (`AsrHost.load` → posted `loadNow`): `SYSTEM` → state `READY` with `backend="system"` and frees any loaded
model (the host is unused for it). If files are missing → `IDLE`. Otherwise `LOADING` (with `stage` strings from
`create`) → `unloadNow()` → engine `create()` (sessions + warm-up run) → `READY`. `NEMOTRON` is the `StreamTarget`
itself; `PARAKEET`/`MOONSHINE` get wrapped in `Segmenter`.

**Streaming path (ONNX)**, in thread order:
1. `OnnxDictation.start()` → `host.start(emit)` (`@Synchronized`; sets `LISTENING` synchronously; posts
   `target.reset()` and the listener) → `AudioCapture.start(onAudio = host::accept)`
   ([../audio/CLAUDE.md](../audio/CLAUDE.md)). The `mic` thread delivers 1280-sample (80 ms) float blocks at 16 kHz.
2. `host.accept(block)` posts to the `asr` thread → `target.accept(samples, emit)`. Nemotron: `LogMel` frames → every
   56 new frames run the encoder on a 65-frame window (9 left-context) → greedy RNNT (≤10 symbols/frame) →
   `Partial(stable=true)`; commit after ≥1.5 s of token-less chunks or at a word boundary once the line is >320 chars
   and ends in `.?!。？！`. Segmenter: 20 ms frames, VAD → `Partial(stable=false)` re-decodes every max(0.8 s, 1.5× last
   decode time) → `Commit` after 0.6 s silence or 15 s.
3. Every ≥1 s of audio, `state.speed` = audio seconds / compute seconds (shown on the Talk screen; <1 means falling
   behind).
4. `emit` runs on the `asr` thread; `OnnxDictation` re-posts to the main `Handler`, gated by `active`.
5. `OnnxDictation.stop()` on the shared `dictation-stop` thread: `capture.stop()` (joins the mic thread, ≤1 s)
   **then** `host.stop { … }` → on `asr`: `target.finish` (Nemotron feeds 1.2 s of zeros then commits; Segmenter pads
   and commits) → `listener = null`, `READY` → `onDone` → main `listener.onFinished()`.

**Consumers of events**: `TextAccumulator` (bubble preview, then `TextInserter`, see
[../dictation/CLAUDE.md](../dictation/CLAUDE.md)); `InAppDictation` → `app.transcript.setPartial/commit`
(`TranscriptStore` in `data/HistoryRepository.kt`). `InAppDictation.transcribeSample` calls `host.start/accept/stop`
directly with `assets/samples/jfk.wav`. Semantics: `Partial.inline` means it continues the last paragraph,
`Commit.newParagraph` starts one, and an empty `Partial` clears the live line.

**System recognizer** (`SystemDictation`): main thread only. On-device recognizer if API 33+ and
`isOnDeviceRecognitionAvailable`, else the default (possibly networked) one. It restarts after every `onResults` /
`NO_MATCH` / `SPEECH_TIMEOUT` while held, so text accumulates as `Commit(newParagraph=false)`. Errors 12/13 (language
unsupported) drop on-device mode and retry. `stop()` → `stopListening()` + a 4 s fallback timer. Language goes to
`EXTRA_LANGUAGE` unless `"auto"`. `onRmsChanged` dB is mapped onto AudioCapture's 0–0.08 RMS scale. The manifest
`<queries>` for `RecognitionService` is required.

**Downloads**: UI (`ui/models/ModelsScreen`) → `ModelRepository.download(id)` → unique work `"download-<id>"`,
`ExistingWorkPolicy.KEEP`, `NetworkType.CONNECTED` → `ModelDownloadWorker` (foreground,
`FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Q+, channel `VoiceApp.CHANNEL_DOWNLOADS`, notification id 4100 + index in
`ALL`) → `app.modelStore.download()`. Per file: `<name>.part` + `Range: bytes=<have>-` resume. A 200 reply to a Range
request restarts the file. It is renamed only when its size is exact. Progress goes through
`setProgressAsync(KEY_BYTES)` (throttled 400 ms). `ModelRepository` collects `getWorkInfosForUniqueWorkFlow` per
downloadable model into `states`. "Pause" = `cancel()` (`.part` kept). On success → `app.onModelDownloaded(id)`, which
auto-selects it if the current choice is system or not ready. On failure → `Result.failure(KEY_ERROR)`, no automatic
retry. `pruneStale()` runs at `ModelRepository` init.

## Invariants & gotchas
- **Single ASR thread.** Engines, `target`, `listener` and the stats are touched only inside `post {}` on the `asr`
  executor (priority NORM+2). ONNX sessions are not thread-safe here, so never call an engine from another thread.
  `state` is a `MutableStateFlow` written from both caller and `asr` threads (`load/unload/start/stop` set it
  synchronously before posting).
- **FIFO ordering is the protocol.** `start`'s post sets `listener` before any `accept` runs, and `accept` blocks
  after `stop`'s post are dropped (`listener == null`). That is why `OnnxDictation` must `capture.stop()` before
  `host.stop()`, or the tail of the audio is lost.
- **No backpressure.** If inference is slower than real time, `accept` posts queue without bound (memory grows, text
  lags). Segmenter's `transcribe` runs synchronously inside `accept`. Only `speed` signals this.
- **Exceptions in posted blocks** go to `onError`. While `LOADING`: unload, set `ERROR` with "Couldn't load the model:
  …" (OOM gets its own message). Otherwise: `errors.tryEmit` (buffer 8, extras are dropped) and the status stays
  `LISTENING`/`READY`. `stop()` always runs `onDone` (try/finally).
- **`backend == "system"` means `READY` but unusable.** `isReady` and `unloadIfIdle` exclude it. UI code checking
  `status == READY` must also check `modelId`/backend.
- **Never pass a voice model id to `AsrHost.load`.** `byId` searches `ALL`; a Kitten/Kokoro/system-tts id passes the
  readiness check and then `loadNow` hits `else -> return`, leaving the state stuck at `LOADING`. Settings validation
  (`speechById`) is the only guard.
- **`start`/`unloadIfIdle` aren't mutually locked.** That is safe only because both are called on the main thread.
  Keep it that way.
- **ORT tensor lifetimes**: cache/state tensors live inside the previous `OrtSession.Result`. Close the old result
  only after the next `run()` has consumed it (`releaseEncoder/releaseDecoder` in Nemotron, `stateResult` in Parakeet,
  `first` in Moonshine, which holds the encoder KV cache until the end). Closing early is a native use-after-free.
- **Sessions load from a file path** (`Ort.session`) so weights are memory-mapped and `encoder.q4.data` (external
  data) is found next to `encoder.q4.onnx`. Don't switch to byte-array loading. `Ort.options`: `ALL_OPT`,
  `SEQUENTIAL`, inter-op 1. Encoders use `bigThreads = (cores-1).coerceIn(1,4)`; predictor/joiner/Parakeet
  decoder_joint use 1. Changing `Ort` also changes `tts/VoiceEngines.kt`.
- **Exports are matched by name with positional fallback** (`result[name, idx]`). Nemotron decoder inputs/outputs go
  purely by position (`prednet_lengths` filtered out). Nemotron blank = last id in `tokens.txt`. Parakeet
  `BLANK = 1024` and state `[2,1,640]` are hard-coded. Clip engines return `""` for <1600 samples (0.1 s).
- **Nemotron tensor shapes** come from `NemotronConfig`: channel cache `[1,24,C,1024]` (C = 70 for `nemotron-en`, 56
  for `nemotron-35`), time cache `[1,24,1024,8]`, predictor `[2,1,640]`. The encoder input is mel-major `[1,128,65]`.
  `prompt_index` is fed only when `prompts != null` (3.5 model). `"auto"` maps to 101, and an unknown language falls
  back to the default prompt, then 0.
- **Feature extraction must stay bit-compatible with `../prototype/src/asr/logmel.js`**: 16 kHz, 400-sample periodic
  Hann window, hop 160, NFFT 512, pre-emphasis 0.97, 128 Slaney mel bins 0–8 kHz, `ln(max(e, FLT_EPSILON))`, no
  dither/normalisation, Kaldi `snip_edges=false` (first frame at −120, reflected). Frames are emitted only once a full
  window exists. The constants in `NemotronEngine`/`Segmenter` mirror `nemotron.js`/`segmenter.js`; change both sides
  together.
- **Readiness = every catalog file exists with exactly `ModelFile.size` bytes.** There are no hashes. A wrong size
  means the model is never `READY` (or the download fails "larger than expected"). File names in `files` must equal
  the literals the engine's `create()` opens.
- **On-disk layout**: `filesDir/models/<id>@<first 12 chars of the HF commit>/<name>` (names may contain subdirs:
  `onnx/`, `voices/`, `lexicon/`). Changing `base` creates a new folder, and `pruneStale()` deletes every folder not
  in the current catalog on next start, so old downloads are lost. The US lexicon is stored separately under both
  Kitten and Kokoro.
- The download needs free space ≥ 1.05× the remaining bytes. The check is skipped if `StatFs` returns 0. Downloads
  send UA `Voice-Android/0.1` with 20 s connect / 30 s read timeouts.
- `recommendedFor`: `totalMem ≥ 7 GiB` → `nemotron-en`, else `moonshine-base`. Voices use the static `recommended`
  flag (Kitten).
- `LANGUAGES` has 31 languages + `auto` (the same list as the prototype), even though `nemotron-35` says "40
  languages". Only Nemotron 3.5 and `SystemDictation` use the setting; Parakeet/Moonshine/`nemotron-en` ignore it.
  `setLanguage` reaches the engine at the next encoder chunk.

## How to extend
- **New model for an existing engine** (e.g. a new Nemotron export): first check the export matches the engine's
  hard-coded file names and shapes (gotchas above). **Append** a `SpeechModel` to `ModelCatalog.MODELS` (download
  notification id = 4100 + index in `ALL`, so inserting shifts ids): `id` unique across `ALL`, `base` pinned to
  `…/resolve/<commit sha>`, exact byte sizes, and the fields the Models screen shows (`vendor`, `params`, `live`,
  `languages`, `wer`, `license`, `blurb`, `runsOn`, `source`). Nemotron models **require** `nemotron =
  NemotronConfig(...)` (`requireNotNull` in `create`). Set `multilingual = true` if it takes a language; the hint under
  Settings → Language (`ui/settings/SettingsScreen.kt`) also names the multilingual models in hard-coded text.
  `heavy` is set on `nemotron-35` but read nowhere. Never change an existing entry's `id`/`base` (the old folder is
  pruned). Settings, Models screen, downloads and pruning pick it up automatically. Update the README engine table,
  `recommendedFor` if it should be the default, and `../prototype/src/models/catalog.js` (both list the same speech models).
- **New engine kind**: add an `EngineKind`. Implement either `StreamTarget + AutoCloseable` (true streaming) or
  `ClipEngine` (it gets wrapped in `Segmenter`), with a `companion create(dir, stage)` that opens sessions via
  `Ort.session` and runs a warm-up. Add the branch in `AsrHost.loadNow`, then a catalog entry. Callers only
  special-case `EngineKind.SYSTEM`, so no `dictation/`/`ui/` change is needed. Port the matching prototype module if
  parity matters.
- **New dictation language**: add `Language(bcp47, label, promptId)` to `LANGUAGES`, with `promptId` taken from the
  Nemotron 3.5 encoder's `prompt_dictionary` metadata. The settings picker ([../ui/CLAUDE.md](../ui/CLAUDE.md)) and
  the `nemotron-35` prompts map derive from it. The value is also passed to Android's recognizer as `EXTRA_LANGUAGE`.
- **New read-aloud voice model**: add it to `VOICE_MODELS` (`kind = ModelKind.VOICE`, `voices`, lexicon files). The
  engine work is in `tts/` ([../tts/CLAUDE.md](../tts/CLAUDE.md)). Downloads/storage here need no change.
- **Change log-mel / VAD / chunking**: change `../prototype/src/asr/*.js` in lockstep, or regenerate
  `logmel_golden.json` (made by running the prototype's `logmel.js` under Node on `jfk.wav` in 1280-sample blocks; no
  generator script is checked in) and keep `LogMelTest` green.
- **Change download behaviour**: edit `ModelStore.downloadFile`. Keep the `.part` → exact-size → rename invariant,
  because `isReady`/`status` depend on it.

## Tests
See [../../../../../../test/CLAUDE.md](../../../../../../test/CLAUDE.md). Run with `gradlew testDebugUnitTest` (plain
JVM; `unitTests.isReturnDefaultValues = true`).
- [LogMelTest.kt](../../../../../../test/java/app/voice/dictate/asr/LogMelTest.kt): parity with the prototype. It
  feeds `src/main/assets/samples/jfk.wav` (a path relative to `app/`) in `AudioCapture.BLOCK` chunks and compares
  against [logmel_golden.json](../../../../../../test/resources/logmel_golden.json): 1099 frames, max abs diff < 2e-3,
  sum relative diff < 1e-5. It also checks that silence equals `SILENT_LOG`.
- [TokensTest.kt](../../../../../../test/java/app/voice/dictate/asr/TokensTest.kt):
  `Tokens.parse/piecesToText/argmax`, and `MoonshineTokenizer` (byte-fallback and ByteLevel).
- `dictation/TextPostProcessorTest.accumulatorJoinsCommitsAndPartial` covers `TextAccumulator`.
- Not covered (they need ORT natives or Android): engines, `Segmenter`, `AsrHost`, `SystemDictation`, `ModelStore`,
  `ModelRepository`/worker. Verify those on a device, e.g. with Talk → sample (`transcribeSample`).
