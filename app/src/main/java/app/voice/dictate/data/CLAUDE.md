# data: persisted settings, dictation history and the Talk-tab transcript

## Purpose
All user state that survives a restart: settings in Preferences DataStore, history and transcript as JSON files
in `filesDir`. Exposed as `StateFlow`s through the `VoiceApp` singletons (`settings`, `history`, `transcript`).

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `SettingsRepository.kt` | DataStore `"voice-prefs"`; typed `Settings` snapshot; setters; range constants and label helpers | `Settings`, `SettingsRepository.flow` / `.value` / `setX()`, `setBubbleDock`, `resetBubble`, `ThemeMode`, `BubbleSide`, `InsertMethod`, `TTS_SPEEDS`, `HISTORY_*`, `MODEL_UNLOAD_*`, `onFivePercentGrid`, `formatDuration`, `historyLabel` |
| `HistoryRepository.kt` | Bubble dictation history (`history.json`, newest first, max 200) and the in-app transcript (`transcript.json`) | `HistoryItem`, `HistoryRepository.add/delete/clear/prune/countRemovedBy`, `HistoryRepository.retain` (pure), `TranscriptStore.commit/setPartial/clear` |
| `BubblePalette.kt` | Preset bubble tints and bubble size limits | `BubblePalette.SWATCHES` (12), `nameOf`, `BUBBLE_SIZE_MIN/MAX/DEFAULT` (32/64/42 dp), `legacyBubbleSizeDp` |

## How it works
- `SettingsRepository` reads DataStore once with `runBlocking` at construction (so the bubble and model loader
  start with real values), then exposes `flow` (`stateIn(Eagerly)`). `read()` validates most keys: unknown enum
  names fall back to defaults, most numbers are clamped, `modelId` / `ttsModelId` must exist in `asr/ModelCatalog`.
  `language`, `ttsVoice`, `ttsSpeed` and `bubbleYFraction` are read unchecked.
- Setters launch `dataStore.edit` on the app scope: **writes are async**, `value` updates only after the flow emits.
- Consumers: `VoiceApp` (model/language/history collectors, idle unload), [../dictation/](../dictation/CLAUDE.md)
  (bubble look, insert method, dock position via `setBubbleDock` from `DictationService`), [../ui/](../ui/CLAUDE.md)
  (`SettingsScreen` writes most keys), [../tts/](../tts/CLAUDE.md) (voice, speed).
- `HistoryRepository.add` is called only by `dictation/DictationController.kt` (skipped for password fields);
  `result` is `"typed"`, `"pasted"` or `"copied"`. `prune` runs at load, on `historyDays` change (`VoiceApp`) and
  when the History tab opens.
- `TranscriptStore` backs the Talk tab: `ui/home/InAppDictation.kt` calls `setPartial`/`commit`; only committed
  paragraphs are saved, the partial line is memory-only.

## Invariants & gotchas
- DataStore key names are the on-disk format. Renaming a key silently resets that setting for existing installs;
  keep the old key and migrate in `read()` like `bubbleSize` → `bubbleSizeDp` (`legacyBubbleSizeDp`; `setBubbleSizeDp`
  removes the old key).
- Opacities are stored on a 5 % grid (`onFivePercentGrid` in `read()`); idle opacity is clamped to 0.2..1, glass
  opacity to `GlassStyle.MIN_OPACITY`..1 (constant lives in `dictation/GlassStyle.kt`).
- `historyDays`: `HISTORY_FOREVER = -1`, `HISTORY_NEVER = 0` (nothing saved, `add` is a no-op), else 1..3650 days.
  `modelUnloadSec` is clamped to 5..3600, default 60.
- `setTtsModelId` clears `ttsVoice` when the model changes, because voice ids are per model.
- `setBubbleColor` forces alpha to 0xFF; translucency comes from `bubbleGlassOpacity`, not the colour.
- `bubbleYFraction = -1` means "just above the keyboard" (see `dictation/BubbleGeometry.kt`).
- JSON files are rewritten whole on every change from a snapshot on `Dispatchers.IO`; errors are swallowed, and a
  corrupt file loads as empty. `HistoryItem.id` is the creation time in ms. `Json { ignoreUnknownKeys = true }`
  lets fields be added; keep new fields defaulted.
- `allowBackup="false"` in the manifest: nothing here is backed up.

## How to extend
- New setting: add a field with default to `Settings`, a key in `K` (new, unique name), a clamped read in `read()`,
  a setter; then the UI row in `ui/settings/SettingsScreen.kt` and the consumer (e.g. `dictation/`). Update
  [../ui/CLAUDE.md](../ui/CLAUDE.md) / the consumer folder's doc and the README if user-visible.
- New bubble preset: append to `BubblePalette.SWATCHES` (keep chroma ≤ 0.3 and names unique, `GlassStyleTest` checks).
- New `HistoryItem` field: add with a default value so old `history.json` still decodes.

## Tests
`HistoryRetentionTest` (`retain`, `historyLabel`, `formatDuration`); `GlassStyleTest` (`BubblePalette`,
`legacyBubbleSizeDp`). `SettingsRepository` I/O is untested. See [../../../../../../test/CLAUDE.md](../../../../../../test/CLAUDE.md).
