# Voice (Android prototype)

## What this is
Native Kotlin app: an accessibility-service glass "bubble" sits by the keyboard in any app; hold it, speak, release,
and the text is typed into the focused field. Speech-to-text runs on-device (ONNX Runtime: Nemotron, Parakeet,
Moonshine) or via Android's recognizer. It also reads text aloud (Kitten / Kokoro ONNX voices or system TTS).
It ports the JS web prototype in `../prototype`. User-facing description and setup: [README.md](README.md).

## Stack and commands
- One Gradle module `:app`, package `app.voice.dictate`. Versions in [gradle/libs.versions.toml](gradle/libs.versions.toml):
  Kotlin 2.0.21, AGP 8.7.3, Gradle wrapper 8.10.2, JDK 17, compileSdk/targetSdk 35, minSdk 26, Compose BOM 2024.12.01
  (Material3), DataStore Preferences 1.1.1, WorkManager 2.10.0, kotlinx.serialization 1.7.3, coroutines 1.9.0,
  **ONNX Runtime Android 1.31.1** (same as the prototype, so the same pinned model files load), JUnit 4.13.2.
- ABIs: arm64-v8a, armeabi-v7a, x86_64. Release = minify off, signed with the debug key (sideload only).
- No CI, no ktlint/detekt. Commands (PowerShell, repo root; `local.properties` must point at the Android SDK):

```powershell
.\gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew installDebug         # install on the connected phone
.\gradlew testDebugUnitTest    # JVM unit tests; one class: --tests "app.voice.dictate.tts.TextPrepTest"
.\gradlew lintDebug            # Android Lint
```

## Architecture
```
VoiceApp (Application; one process; singletons: settings, modelStore, models, asr, history, transcript, readAloud)
 │
 ├─ DictationService (AccessibilityService) ── shows/hides ─► BubbleController (overlay windows, gestures)
 │    hold ─► DictationController ─► asr/Dictation: OnnxDictation | SystemDictation (SpeechRecognizer, main thread)
 │              AudioCapture ("mic" thread, 16 kHz, 1280-sample blocks) ─► AsrHost ("asr" thread, ONNX CPU)
 │              ─► AsrEvent Partial/Commit (posted to main) ─► TextAccumulator ─► bubble live text
 │    release ─► TextPostProcessor / TextSplicer ─► TextInserter ─► focused field ─► HistoryRepository
 │    speaker ─► selection in field, else ClipboardGrabActivity (clipboard) ─► ReadAloud   (no read-aloud panel)
 │
 ├─ ReadAloud (main) ─► TextPrep ─► G2P/Lexicon ─► TtsHost ("tts" thread, Kitten/Kokoro ONNX)
 │                     ─► TtsPlayer ("tts-play", 24 kHz AudioTrack)          or SystemTts (no download)
 ├─ MainActivity (Compose, no ViewModels/Navigation): Talk, Models, History, Settings tabs collect the singletons'
 │    StateFlows directly; Talk tab records via ui/home/InAppDictation into TranscriptStore (never History)
 └─ ModelRepository ─► WorkManager ModelDownloadWorker ─► ModelStore (filesDir/models) ─► VoiceApp.onModelDownloaded
```

## Code map
- [app/src/main/java/app/voice/dictate/](app/src/main/java/app/voice/dictate/CLAUDE.md): `VoiceApp` singletons,
  model auto-load / idle unload, `MainActivity`, `ClipboardGrabActivity`.
- [asr/](app/src/main/java/app/voice/dictate/asr/CLAUDE.md): ASR engines, `AsrHost` thread, log-mel, model catalog,
  on-disk store and downloads (for TTS voices too).
- [audio/](app/src/main/java/app/voice/dictate/audio/CLAUDE.md): mic capture and WAV decoding.
- [data/](app/src/main/java/app/voice/dictate/data/CLAUDE.md): DataStore settings, history and transcript JSON, bubble palette.
- [dictation/](app/src/main/java/app/voice/dictate/dictation/CLAUDE.md): accessibility service, bubble windows and
  gestures, glass drawing, text insertion.
- [tts/](app/src/main/java/app/voice/dictate/tts/CLAUDE.md): read-aloud pipeline (text prep, G2P, voice engines, playback).
- [ui/](app/src/main/java/app/voice/dictate/ui/CLAUDE.md): Compose screens, theme, shared components.
- [app/src/test/](app/src/test/CLAUDE.md): JVM unit tests and golden files.
- No own doc: `app/src/main/res/` (strings, themes incl. `Theme.Voice.Invisible`, `xml/accessibility_service_config.xml`,
  covered in dictation/), `app/src/main/assets/samples/jfk.wav` (Talk-tab sample and test fixture, kept uncompressed
  by `noCompress "wav"`), `AndroidManifest.xml`.

## Rules that must always hold
- **One process.** The service and the UI share state only through `VoiceApp.instance`. Never add `android:process`.
- **Threads.** UI, overlay windows, `AccessibilityNodeInfo` calls, `ReadAloud`, settings collectors and dictation
  callbacks: main only. ONNX speech work only on `AsrHost`'s `asr` thread, TTS inference only on `TtsHost`'s `tts`
  thread (both share `Ort` from `asr/Engines.kt`). Mic on its own thread. File I/O on `Dispatchers.IO`.
- **One recording at a time.** `AsrHost` serves one session (bubble or Talk tab). Starting any dictation calls
  `readAloud.stop()` first so the mic never records the phone's own voice.
- **Overlays never take focus** (`TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_FOCUSABLE`), or the target app loses its
  keyboard. Only `ClipboardGrabActivity` takes focus, briefly. Pure helpers in `dictation/` (`GlassStyle`,
  `BubbleGeometry`, `FullscreenDetector`, `TextPostProcessor`) stay free of `android.*` so JVM tests run.
- **Prototype parity.** `LogMel`, `TextPrep`, tokenizers, Kitten symbol ids and model file pins must match
  `../prototype` and the ONNX exports. Golden tests (`LogMelTest`, `TextPrepTest`) guard this; change both sides
  together. Bumping ONNX Runtime means re-checking every model.
- **Models.** `asr/ModelCatalog.kt` is the single source of model ids, files, exact byte sizes and pinned HF commits.
  Readiness = exact sizes; folders not in the catalog are deleted at start.
- **Persistence formats.** DataStore `voice-prefs` key names are on-disk format: migrate, never rename.
  `history.json` / `transcript.json` are kotlinx JSON with `ignoreUnknownKeys`: new fields need defaults.
  Settings writes are async. `allowBackup="false"`.
- **Privacy.** On-device engines keep audio on the phone; network is for model downloads (Android's own recognizer
  may use the network when on-device recognition is unavailable). Lock screen: no bubble, dictation stops, text is
  held until unlock. Password fields: no bubble, no selection read, no History entry. The focused field is read only
  to insert at the cursor.
- **Distribution.** Accessibility services that aren't accessibility tools are restricted on Play: sideload only.

## Keeping docs current
- When you add, rename or delete a file, or change behaviour in a folder, update that folder's CLAUDE.md in the same change.
- A new folder with real logic gets its own CLAUDE.md (same headings) and a line in the code map.
- Edit this root file only when the architecture, commands or cross-cutting rules change. Keep it short.
- User-visible changes also update the README.
- Docs explain the why, the connections and the gotchas. Don't restate code line by line. Delete anything that's no longer true.
