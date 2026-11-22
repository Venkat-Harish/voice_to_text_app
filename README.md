# Voice for Android: a dictation bubble for every app

A native Kotlin app based on the web prototype in `../prototype`. Whenever you type, a small dark-glass
bubble appears by the keyboard. **Hold it, speak, and let go**: what you said is typed into the text
field you're in (WhatsApp, Gmail, Chrome, anything). Speech is turned into text on the phone, by the same open models as the prototype.
Android's built-in recognizer is also available, so it works before any download.

It also **reads text aloud**. Tap the bubble, tap the speaker, paste (or keep the text you copied or
selected), and press **Read aloud**.

## Run it

1. Open this folder (`android_prototype`) in Android Studio, and let Gradle sync.
2. Plug in your phone with USB debugging on (Settings → About phone → tap *Build number* 7 times → Developer options → USB debugging).
3. Press **Run ▶**.

From a terminal, `gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
`gradlew testDebugUnitTest` runs the unit tests.

## First-time setup (on the phone)

The **Talk** tab walks you through it:

1. **Allow the microphone.**
2. **Turn on the Voice bubble**: Settings → Accessibility → *Voice bubble* → on.
   On Android 13+, an app installed from Android Studio is "restricted" at first. If the switch is greyed out:
   App info → ⋮ (top right) → **Allow restricted settings**, then try again.
3. Optional: allow notifications (download progress), and let Voice ignore battery optimisation so Android doesn't close the bubble.
4. Optional: download an on-device model in **Models**.

## Using the bubble

The bubble appears when the keyboard opens and hides when it closes (Settings → Bubble → *Only while typing*).
It never shows on the lock screen or in password fields, stays out of full-screen apps, and fades to see-through
after a few idle seconds.

| Gesture | What happens |
|---|---|
| **Hold** the bubble | Records while you hold. Live text shows above the bubble. Let go and it is typed at the cursor. |
| **Tap** the bubble | Opens two glass buttons: **mic** (tap for hands-free, hold to talk) and **speaker** (read aloud). |
| Tap the bubble while hands-free | Finishes and types the text. |
| **Drag** | Moves the bubble. It snaps to the nearest edge and remembers where it is. |
| **Push it past the edge** | Tucks it into a thin tab at the edge. Tap the tab to bring it back. |

- Text goes in at the cursor, replacing any selected text. Spaces and capitals are fitted to the text around it.
- If an app refuses typed text, Voice pastes it instead. You can also set "Paste it" in Settings.
- If no text field has focus, the text is copied to the clipboard.
- Every dictation is kept in **History** (tap to copy again).

### Make it yours (Settings → Bubble)

- A live preview shows the bubble in use, faded, and tucked, as you change it.
- **Size**: 32–64 dp.
- **Glass opacity**: how see-through the glass is, 30–100%. Icons and text always stay solid.
- **Idle fade**: how faint it gets after a few idle seconds, 20–100% ("Never fades" at 100%). The tucked tab never fades below 85%, so it stays crisp.
- **Colour**: muted glass tints (Graphite, Slate, Smoke, Midnight, Ocean, Forest, Olive, Plum, Wine, Cocoa), light Frost and Sand, or **Custom…** for any colour (picker or hex code). Light tints get dark icons automatically.
- **Now:** under "Show the bubble" says whether the bubble is on screen and, if not, why (for example "Hidden until a keyboard opens").

## Read aloud (text to speech)

The read-aloud panel opens over whatever app you're in:
- **Filling the text:** the box starts with the text you selected in the focused field, otherwise with what you last copied. **Paste**, **From the text field** and **Clear** change it.
- **Voice and speed:** pick a voice and a speed (0.8×, 1×, 1.2×, 1.5×), then press **Read aloud**.
- **Closing the panel** keeps the reading going. Tap the speaker button, or start dictating, to stop it.

The **Talk** tab's speaker button reads the transcript back, as in the prototype. Voices, speed and the speaker button are in **Settings → Read aloud**.

| Voice engine | Download | Voices | Notes |
|---|---|---|---|
| Android built-in voice | none | the phone's own | works at once, in any language the phone's text-to-speech has |
| Kitten TTS Nano v0.8 (recommended) | 60 MB + 6 MB dictionary | 8, US English | small and fast: reads without pauses on most phones |
| Kokoro-82M v1.0 (q8) | 97 MB + 13 MB dictionaries | 8, US + UK English | the most natural; may pause between sentences on mid-range phones |

How it works:
1. **Text preparation** matches the prototype's `text.js`. A unit test checks it against the JS output. Quotes, titles, years, times, money and decimals are spelled out, and the text is cut into sentences. The first sentence plays while the rest is still being made.
2. **Phonemes:** the prototype uses eSpeak NG for this step. Here words are looked up in Kokoro's own pronunciation dictionaries ([misaki](https://github.com/hexgrad/misaki), Apache-2.0, about 183,000 US words), downloaded with the voice.
3. **Words not in the dictionary** go through inflection stems (cats, walked, running), acronyms, compound and camelCase splits ("WhatsApp"), and finally spelling-to-sound rules. Rare names may sound a little off.
4. **Kitten** needs eSpeak-style phonemes, so the dictionary phonemes are converted for it.

## Speech engines

| Engine | Mode | Download | Languages | Good for |
|---|---|---|---|---|
| Android built-in | Live | none | many | instant start, any phone |
| Nemotron Streaming EN 0.6B | Live, word by word | 716 MB | English | best live English, flagship phones |
| Nemotron 3.5 Streaming 0.6B | Live, word by word | 781 MB | 40, incl. Hindi | multilingual, flagship phones |
| Parakeet Unified EN 0.6B | Near-live, settles on pause | 445 MB | English | most accurate English, fast phones |
| Moonshine Base (Lite) | Near-live, settles on pause | 67 MB | English | most phones |

- The on-device models run on the phone's CPU with ONNX Runtime 1.31.1. That is the same runtime version as the prototype, so the same pinned model files are used.
- The **Talk** tab shows the speed while you dictate. Above 1× keeps up with speech; if it's below, pick a lighter model.
- The **Recommended for this phone** tag is based on RAM.
- Downloads continue in the background and resume where they stopped. Models stay until you delete them or uninstall the app.

## How it maps to the prototype

```
mic ─► AudioRecord 16 kHz ─► AsrHost (one thread) ─► ONNX Runtime Android (CPU)
                                   │                   Nemotron: log-mel → cache-aware encoder → RNNT
                                   │                   Parakeet / Moonshine: VAD segments → re-transcribe
                                   └─► partial / committed text ─► bubble preview ─► focused text field
```

| Prototype (JS) | Android (Kotlin) |
|---|---|
| `src/models/catalog.js` | [`asr/ModelCatalog.kt`](app/src/main/java/app/voice/dictate/asr/ModelCatalog.kt) |
| `src/asr/logmel.js` | [`asr/LogMel.kt`](app/src/main/java/app/voice/dictate/asr/LogMel.kt). A unit test checks it against `logmel.js` output on `jfk.wav`. |
| `src/asr/nemotron.js` | [`asr/NemotronEngine.kt`](app/src/main/java/app/voice/dictate/asr/NemotronEngine.kt) |
| `src/asr/parakeet.js` | [`asr/ParakeetEngine.kt`](app/src/main/java/app/voice/dictate/asr/ParakeetEngine.kt) |
| `src/asr/moonshine.js` (Transformers.js) | [`asr/MoonshineEngine.kt`](app/src/main/java/app/voice/dictate/asr/MoonshineEngine.kt) (direct ONNX port) |
| `src/asr/segmenter.js`, `tokens.js` | [`asr/Segmenter.kt`](app/src/main/java/app/voice/dictate/asr/Segmenter.kt), [`asr/Tokens.kt`](app/src/main/java/app/voice/dictate/asr/Tokens.kt) |
| `src/asr/worker.js` + `src/lib/asr.js` | [`asr/AsrHost.kt`](app/src/main/java/app/voice/dictate/asr/AsrHost.kt), [`asr/Dictation.kt`](app/src/main/java/app/voice/dictate/asr/Dictation.kt) |
| `src/lib/modelStore.js` | [`asr/ModelStore.kt`](app/src/main/java/app/voice/dictate/asr/ModelStore.kt), [`asr/ModelDownloads.kt`](app/src/main/java/app/voice/dictate/asr/ModelDownloads.kt) |
| `src/tts/text.js` | [`tts/TextPrep.kt`](app/src/main/java/app/voice/dictate/tts/TextPrep.kt) (+ number words) |
| `phonemizer` (eSpeak NG) | [`tts/Lexicon.kt`](app/src/main/java/app/voice/dictate/tts/Lexicon.kt), [`tts/G2P.kt`](app/src/main/java/app/voice/dictate/tts/G2P.kt) (misaki dictionaries) |
| `src/tts/kitten.js`, `kokoro.js` | [`tts/VoiceEngines.kt`](app/src/main/java/app/voice/dictate/tts/VoiceEngines.kt) |
| `src/tts/worker.js` + `src/lib/tts.js` | [`tts/TtsHost.kt`](app/src/main/java/app/voice/dictate/tts/TtsHost.kt), [`tts/TtsPlayer.kt`](app/src/main/java/app/voice/dictate/tts/TtsPlayer.kt), [`tts/ReadAloud.kt`](app/src/main/java/app/voice/dictate/tts/ReadAloud.kt) |
| `src/pages/*.jsx` | [`ui/`](app/src/main/java/app/voice/dictate/ui) (Jetpack Compose, same palette), [`ReadAloudActivity.kt`](app/src/main/java/app/voice/dictate/ReadAloudActivity.kt) |
| (new) the bubble | [`dictation/`](app/src/main/java/app/voice/dictate/dictation): `DictationService` (accessibility service), `BubbleController` (window and gestures), `BubbleView` / `MenuButtonView` / `EdgeTabView` (the glass), `BubbleGeometry`, `ScreenWatcher` (lock screen, full screen), `TextInserter` |

## Notes

- **Why an accessibility service:** it is the only way for an app to type into other apps' text fields without being the keyboard. It also lets the bubble float without the separate "display over other apps" permission. Voice reads the focused field only to insert text at its cursor.
- **Distribution:** Google Play restricts accessibility services that aren't accessibility tools. This build is meant for installing from Android Studio.
- **Background microphone:** if a phone silences the mic for the service, Voice notices the silence. It then shows a small "Voice is listening" notification while recording and restarts the recording automatically.
