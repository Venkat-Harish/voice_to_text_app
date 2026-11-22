# dictate (package root): process-wide singletons and the two activities

## Purpose
`VoiceApp` owns every long-lived object (settings, models, ASR host, history, read-aloud) and is shared by the
Compose UI and the bubble accessibility service, which run in the same process. Subfolders hold the real logic.

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `VoiceApp.kt` | `Application`: builds singletons, auto-loads the chosen speech model, unloads idle models, notification channels | `VoiceApp.instance`, `loadSelected()`, `selectModel()`, `wakeSpeechModel()`, `onModelDownloaded()`, `recommendedModelId`, `CHANNEL_DOWNLOADS` / `CHANNEL_DICTATION` |
| `MainActivity.kt` | Launcher activity (`singleTask`), hosts the 4 bottom tabs: Talk, Models, History, Settings | `VoiceAppUi`, private `enum Tab`, `EXTRA_TAB` |
| `ClipboardGrabActivity.kt` | Invisible activity that takes focus for a moment so the bubble can read the clipboard (Android 10+ rule) | `RESULT_TEXT` / `RESULT_EMPTY` / `RESULT_FAILED`, `EXTRA_RECEIVER`, `KEY_TEXT` |

## How it works
- `VoiceApp.onCreate` builds, in order: `SettingsRepository` → `ModelStore` → `ModelRepository` → `AsrHost` →
  `HistoryRepository` → `TranscriptStore` → `ReadAloud` (see [data/](data/CLAUDE.md), [asr/](asr/CLAUDE.md), [tts/](tts/CLAUDE.md)).
- Three collectors on `settings.flow`: `modelId` change → `loadSelected()` (loads on `Dispatchers.IO`);
  `language` → `asr.setLanguage`; `historyDays` → `history.prune`.
- Idle unload: when the speech model is READY and not the `"system"` backend (or the read-aloud voice is READY and
  not reading) for `modelUnloadSec` (setting, default 60 s), `asr.unloadIfIdle()` / `readAloud.unloadIfIdle()`.
  `wakeSpeechModel()` restarts the countdown and reloads if status is IDLE. Callers: [dictation/](dictation/CLAUDE.md)
  (field focus, keyboard open, bubble touch) and the Talk screen in [ui/](ui/CLAUDE.md). `onTrimMemory(COMPLETE)` also unloads.
- `onModelDownloaded(id)` (called by the download worker in `asr/ModelDownloads.kt`): switches to the new model only
  if the current choice is the system engine or not ready; voice models switch `ttsModelId` the same way.
- `MainActivity` reads `settings.theme` for `VoiceTheme`; Models tab badge shows while any model is DOWNLOADING.
- `ClipboardGrabActivity` is started by `dictation/DictationService.kt` with a `ResultReceiver`; it reads the clip in
  `onWindowFocusChanged(true)`, replies once, and finishes. Gives up after 1500 ms (`RESULT_FAILED`).

## Invariants & gotchas
- Everything assumes one process: the service reaches state through `VoiceApp.instance`. Don't add
  `android:process` to the service or activities.
- `scope` is `SupervisorJob + Dispatchers.Main.immediate`: collectors run on main; heavy work must switch dispatcher.
- `selectModel()` writes the setting asynchronously, so its `loadSelected()` check usually falls through; the
  `modelId` collector does the actual reload.
- `EXTRA_TAB` ("tab"="models") has no sender in the code today; the dictation notification opens `MainActivity` plainly.
- `ClipboardGrabActivity` must stay invisible and non-touchable (`FLAG_NOT_TOUCHABLE | FLAG_ALT_FOCUSABLE_IM`, theme
  `Theme.Voice.Invisible`, own `taskAffinity`) or it steals the keyboard / touches from the app underneath.

## How to extend
- New app-wide singleton: add a `lateinit` in `VoiceApp`, build it in `onCreate` after what it depends on.
- New tab: add to `Tab` in `MainActivity.kt` and its `when`; put the screen under `ui/<name>/`; update [ui/CLAUDE.md](ui/CLAUDE.md).
- New notification channel: constant + `createChannels()` + a string in `res/values/strings.xml`.

## Tests
None (Android framework classes; unit tests are plain JVM).
