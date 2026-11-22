# dictation: the accessibility-service bubble that dictates into other apps' text fields and reads text aloud

## Purpose
`DictationService` (an `AccessibilityService`) floats a glass bubble over other apps while you type. Holding or tapping it records through [../asr](../asr/CLAUDE.md), and the text is typed into the focused field of the app in front. Its speaker button reads the selection or the clipboard through [../tts](../tts/CLAUDE.md).

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `DictationService.kt` | The service: lifecycle, accessibility events, show/hide decision, target-field lookup, speaker button, mic foreground state. Glue to `VoiceApp` singletons | `DictationService` (`onServiceConnected`, `onAccessibilityEvent`, `refreshInput`, `updateVisibility`, `findTarget`, `onSpeaker`, `idleMode`, `start/stopMicForeground`); companion `isRunning`, `bubbleStatus`, `isEnabled(ctx)`; `enum BubbleStatus` |
| `DictationController.kt` | One dictation from start to delivery: target capture, engine choice, silent-mic retry, 5-min cap, insert + History | `DictationController : asr.DictationListener` (`onHoldStart/onHoldEnd/onHandsFree/onStopTap`, `stop`, `cancel`, `deliverPending`, `active`) |
| `BubbleController.kt` | Overlay windows, gesture state machine, menu open/close, drag/snap/tuck, idle fade, live-text pill | `BubbleController` + `Callbacks`; `setVisible`, `setDock`, `setKeyboardTop`, `setMode`, `setReading`, `showPreview/hidePreview`, `setStyle/setSize/setIdleOpacity/setMenuSpeaker`, `onScreenChanged`, `destroy` |
| `BubbleView.kt` | The disc: mic, waveform + halo, stop square, spinner, pulsing speaker, mic-to-× morph | `BubbleView`, `Mode {IDLE, LOADING, LISTENING, LOCKED, FINISHING, UNAVAILABLE}`, `menuProgress`, `reading`, `tuckHint`, `setLevel` |
| `MenuButtonView.kt` | The menu's mic and speaker glass buttons | `MenuButtonView`, `Mode {IDLE, LOADING, ACTIVE}` (ACTIVE = stop square) |
| `EdgeTabView.kt` | The thin tab shown when tucked (5×40 dp pill in a 24 dp-wide grab strip) | `EdgeTabView` (`onRight`, `viewWidth`) |
| `GlassPainter.kt` | Paints the glass (no real blur): fill, highlight, rim, hairline, clipped shadow; crisp pixel-aligned tab | `GlassPainter.circle`, `crispPill`, `prepare(view)` |
| `GlassStyle.kt` | Pure Kotlin: all glass colours from tint + opacity, WCAG choice of light or dark marks | `GlassStyle(color, opacity)`, `GRAPHITE`, `DEFAULT_OPACITY`=0.7, `MIN_OPACITY`=0.3, `TAB_MIN_FILL`=0.85, `prefersDarkMarks` |
| `BubbleGeometry.kt` | Pure Kotlin: layout maths in screen px | `Pt`, `Box`, `BubbleGeometry` (`dockCenter`, `clampY`, `yFraction`, `shouldTuck`, `fan`, `menuBox`, `collapsedBox`, `tuckedBox`, `previewAbove`) |
| `ScreenWatcher.kt` | Lock / screen-off state (broadcasts + keyguard) and full-screen state | `ScreenWatcher` (`start/stop`, `locked`, `fullscreen`, `refreshLock`, `updateWindows`) |
| `FullscreenDetector.kt` | Pure Kotlin: full screen = the status-bar window disappeared | `WindowSnapshot`, `FullscreenDetector.update`, `isStatusBar` |
| `TextInserter.kt` | Writes text into another app's `AccessibilityNodeInfo` | `TextInserter.insert` → `InsertResult {TYPED, PASTED, COPIED}`, `copy` |
| `TextPostProcessor.kt` | Pure Kotlin text functions | `TextPostProcessor.removeFillers`, `fit`; `TextSplicer.splice` → `Splice(text, cursor, inserted)` |

## How it works
**Config.** [AndroidManifest.xml](../../../../../AndroidManifest.xml) declares `.dictation.DictationService`: exported, guarded by `BIND_ACCESSIBILITY_SERVICE`, `foregroundServiceType="microphone"`. Its meta-data points at [accessibility_service_config.xml](../../../../../res/xml/accessibility_service_config.xml):
- events `typeViewFocused|typeViewTextSelectionChanged|typeViewTextChanged|typeWindowStateChanged|typeWindowsChanged`
- flags `flagRetrieveInteractiveWindows|flagReportViewIds`, plus `canRetrieveWindowContent=true` and `notificationTimeout=50`
- `settingsActivity=MainActivity`

The user turns the service on in Android's Accessibility settings ([../ui/SystemLinks.kt](../ui/SystemLinks.kt) `openAccessibility`). `isEnabled` parses `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. `ui/home/HomeScreen.kt` reads `isRunning` and `isEnabled`.

**Lifecycle.** `onServiceConnected` builds `BubbleController`, `DictationController` and `ScreenWatcher`, then calls `start()` on the watcher, which registers SCREEN_OFF, SCREEN_ON and USER_PRESENT. It sets `isRunning=true` and applies the look and dock settings. It then collects these flows on a `MainScope`:
- `settings.flow`: look, dock, visibility
- `readAloud.state`: `setReading`, visibility
- `readAloud.messages`: shown in the pill for 3.5 s
- `combine(asr.state, settings)`: the idle look while not recording

Last, it calls `app.loadSelected()`. `onUnbind` and `onDestroy` both call `shutdown()`. `shutdown()` cancels the dictation, removes the windows, unregisters the receiver, sets `bubbleStatus=SERVICE_OFF` and cancels the scope. `onConfigurationChanged` calls only `bubble.onScreenChanged()`, which re-docks the bubble after rotation; it does not re-run `updateVisibility`.

**Show/hide.** `updateVisibility` runs after every event. The first matching rule wins:
1. `!bubbleEnabled` → TURNED_OFF (this also stops an active dictation)
2. `screen.locked` → LOCKED
3. `controller.active` → SHOWING
4. `readAloud.reading` → SHOWING
5. `passwordFocused` → PASSWORD
6. `bubbleHideFullscreen && fullscreen` → FULLSCREEN
7. `bubbleOnlyWhenTyping && !typing` → WAITING_FOR_KEYBOARD
8. otherwise SHOWING

`typing` means a `TYPE_INPUT_METHOD` window exists. Until the phone has ever reported a keyboard window (`sawKeyboard`), an input-focused editable node counts instead. `refreshInput()` runs on focus and window events and does three things:
- reads `windows`
- passes `WindowSnapshot`s to `ScreenWatcher.updateWindows`
- passes the keyboard's top edge to `bubble.setKeyboardTop`; it counts only if `top > 0.35 × screen height` (a window reaching higher is not treated as the keyboard), otherwise −1

The status goes out as `DictationService.bubbleStatus`, which drives the Settings "Now:" line in [../ui/settings/BubbleLook.kt](../ui/settings/BubbleLook.kt). When a dictation ends, `onDictationEnded` checks visibility again 3 s later, so messages stay readable.

**Lock detection.** `locked = !isInteractive || isKeyguardLocked`. SCREEN_OFF sets it. SCREEN_ON and USER_PRESENT check at once and again 600 ms later. Every window event also calls `refreshLock()`, which catches fingerprint unlock.

**Full-screen detection.** `FullscreenDetector` returns true only when all of these hold:
- it has seen a status bar before: a TYPE_SYSTEM window with top ≤ 0, height ≤ 12 % of the screen and width ≥ 90 %
- no status bar is in the current window list
- an active TYPE_APPLICATION window is in front
- on API 30+, `WindowMetrics` insets confirm that the status bar is hidden

**Windows (`BubbleController`).** There are three `TYPE_ACCESSIBILITY_OVERLAY` windows, so no overlay permission is needed. All three use `FLAG_NOT_FOCUSABLE|FLAG_LAYOUT_NO_LIMITS|FLAG_LAYOUT_IN_SCREEN` and gravity TOP|START in raw screen px. Version-specific params: cutout `SHORT_EDGES` on API 28+, `fitInsetsTypes=0` on 30+, `setCanPlayMoveAnimation(false)` on 34+.
- **Bubble window** (`root`: orb + tab): `FLAG_NOT_TOUCH_MODAL`. It keeps a fixed size and only ever moves. `setMode` adds `FLAG_KEEP_SCREEN_ON` while the mode is LISTENING, LOCKED or FINISHING.
- **Menu window** (`menuRoot`: mic + speaker): a fixed square (`menuBox`) centred on the disc. It is added first so that it sits underneath. Closed: `FLAG_NOT_TOUCHABLE` and INVISIBLE. Open: `FLAG_NOT_TOUCH_MODAL|FLAG_WATCH_OUTSIDE_TOUCH`; ACTION_OUTSIDE collapses the menu.
- **Live-text pill** (`preview` TextView): `FLAG_NOT_TOUCHABLE`. `previewAbove` puts it above or below the disc. It shows at most 4 lines; text longer than 220 chars is cut from the front.

**Gesture state machine.** States are `Touch {NONE, PENDING, HOLDING, DRAGGING}` × `State {COLLAPSED, EXPANDED, TUCKED}`.
- **Orb DOWN** → PENDING, and posts `holdTask` after `HOLD_MS`=180 ms (not in LOCKED or FINISHING).
- **Move beyond 1.5 × scaledTouchSlop** cancels the hold. It becomes DRAGGING only if the bubble is COLLAPSED and the menu isn't closing; otherwise NONE.
- **Hold fires** → HOLDING: strong haptic, collapse, `onHoldStart`. Releasing calls `onHoldEnd`.
- **Release after a drag** → `settle()`. If `shouldTuck` (centre within 0.3 × diameter of an edge), it calls `tuck()`. Otherwise it snaps to `dockCenter` in 220 ms, then calls `onDockChanged`.
- **Release while PENDING** → `performClick` → `onOrbClick`, which picks the first match:
  - LOCKED → `onStopTap`
  - LISTENING or FINISHING → ignored
  - EXPANDED → collapse
  - `!menuSpeaker` → `onHandsFree`
  - otherwise `expand()`
- **Menu mic**: tap → `onHandsFree`; hold 180 ms → push-to-talk.
- **Menu speaker**: tap → `onSpeaker`.
- **Tab**: tap, or drag past the slop → `untuck()`.

Timers: the menu collapses by itself after 4 s (`MENU_MS`). After 3 s idle (`IDLE_MS`) the bubble fades to `idleOpacity` over 450 ms. The tucked tab never fades below 0.85, and nothing fades unless `canFade()` passes. When the keyboard moves, `glideToDock` runs over 160 ms; while the menu is open it waits until the menu has closed (`redockAfterMenu`).

**Dictation (`DictationController`).** `start(lock)` runs these steps:
1. Checks the RECORD_AUDIO permission.
2. Calls `readAloud.stop()`.
3. Captures the target now: `target = service.findTarget()` returns the input-focused editable, or else the last editable if it is still focused.
4. Calls `begin()`, which picks the engine:
   - no model, or `EngineKind.SYSTEM` → `SystemDictation`
   - `asr` is IDLE or LOADING and the model is downloaded → `app.wakeSpeechModel()` and "Loading the model… try again"; nothing starts
   - otherwise → `OnnxDictation(app.asr, this)`. See [../asr/Dictation.kt](../asr/Dictation.kt): the `AudioCapture` "mic" thread feeds `AsrHost.accept` on the "asr" thread, and events are posted to main.

The mode becomes LISTENING (hold) or LOCKED (hands-free), with a 5-min `MAX_MS` cap. `onEvent` feeds `TextAccumulator` and the pill; `onLevel` drives the waveform. `stop()` sets FINISHING and calls `session.stop()`; `onFinished` then calls `deliver(acc.text())`. `onError` still delivers any partial text.

**Silent-mic recovery.** `AudioCapture` calls `onSilenced` once if the first 1.5 s are exact zeros; only the on-device path has this check. The controller then does three things:
- sets `service.micForegroundNeeded=true`, which stays set for the service's lifetime and is not persisted
- cancels the session and runs `begin()` again, at most once per dictation
- queues a finger release that happens during the restart in `pendingStop`

`begin()` calls `startMicForeground()` when that flag is set. The notification has id 4200, uses `VoiceApp.CHANNEL_DICTATION`, says "Voice is listening" and is silent and ongoing. It uses `FOREGROUND_SERVICE_TYPE_MICROPHONE` on API 30+. `stopMicForeground()` runs on finish, error or cancel.

**Delivery.** If the phone is locked, `deliver` keeps the text in a single slot, `pendingText`. On unlock, `deliverPending()` types it with `retarget=false`. Otherwise it continues:
1. Trims the text, then runs `removeFillers` if that setting is on. Empty text shows "Didn't catch that." and stops here.
2. Picks the node: `target.refresh()`, or, when retargeting is allowed, `findTarget()`.
3. Calls `TextInserter.insert(node, text, insertMethod, smartSpacing)`:
   - no node → raw text to the clipboard (COPIED) and a pill message
   - otherwise it reads the field's text and selection (hint text counts as empty) and calls `TextSplicer.splice`
   - with `InsertMethod.AUTO` it tries `ACTION_SET_TEXT` with the whole new field text, then `ACTION_SET_SELECTION` to the caret (TYPED)
   - if that fails, or the method is PASTE: puts `splice.inserted` on the clipboard and calls `ACTION_PASTE`. If the old clip was readable, it is put back 800 ms later (PASTED)
   - if pasting also fails → raw text to the clipboard (COPIED)
4. Calls `app.history.add(text, package, result.name.lowercase())`, except for password fields.

**Read aloud (`onSpeaker`).** If it is already reading, it calls `readAloud.stop()`. Otherwise it tries these in order:
1. The selection in the target field → `readAloud.speak`. Never from password fields or hint text.
2. Otherwise `readAloud.prepare()`, then it starts [../ClipboardGrabActivity.kt](../ClipboardGrabActivity.kt) with `NEW_TASK|NO_ANIMATION|EXCLUDE_FROM_RECENTS` and a main-looper `ResultReceiver`:
   - RESULT_TEXT → speak
   - RESULT_FAILED (no focus within 1.5 s) or RESULT_EMPTY → a hint in the pill

There is no read-aloud panel or activity; voice and speed come from Settings. While reading, the bubble stays visible, the disc pulses a speaker, and the menu's speaker becomes a stop square (a spinner while the voice loads).

**Model wake.** Three things call `app.wakeSpeechModel()`, which reloads the model after an idle unload and restarts the countdown:
- an editable gaining focus
- the keyboard opening
- a finger touching the bubble

It is skipped when the bubble is off, a password field has focus, or the phone is locked.

## Invariants & gotchas
- **Main thread only.** Window calls, `AccessibilityNodeInfo` calls and controller state all live on the main looper. asr guarantees that `DictationListener` callbacks arrive on main. Never touch `wm` or `session` from engine threads.
- **Never take focus.** Every overlay must keep `FLAG_NOT_FOCUSABLE`. Without it, the target app loses the keyboard and its focused field. Only `ClipboardGrabActivity` takes focus, and only briefly, on purpose.
- **Lock screen privacy.** LOCKED outranks every other rule. A dictation stops when the phone locks, and its text is never typed behind the lock. After unlock, pending text is not retargeted; if the original field is gone, it is copied instead.
- **Password fields.** The bubble hides, the model is not woken, `selectedText` returns nothing, and History gets nothing. However, a running dictation or read-aloud outranks PASSWORD, so a bubble already on screen stays for that run.
- **Two fixed-size windows.** Never resize and move a window in one step: Android's ~400 ms move animation makes the bubble jump. Opening the menu never touches the bubble window.
- **Deferred window changes.** While a finger is down, window changes wait (`layoutPending`, `menuClosePending`), because changing a window under a live touch breaks the gesture.
- **Generation counters.** `menuGen` and `tuckGen` cancel stale animation end-callbacks; bump them on every reset. `tuck()` hides the orb and waits two `postOnAnimation` frames before switching to the tab, so no ghost disc flashes.
- **Dock writes.** `setDock` runs on every settings emission. It ignores repeats (`lastSavedDock`) and skips while dragging or animating. Only `onDockChanged` → `settings.setBubbleDock` and `resetBubble` may write the dock.
- **Dock persistence.** The dock is stored in DataStore `voice-prefs` under `bubbleSide` (enum name), `bubbleYFraction` (float; −1 means "just above the keyboard") and `bubbleTucked`. Any drag replaces −1 with a real, clamped fraction.
- `setVisible(true)` checks `added`, not `visible`, so a failed `addView` is retried on the next call. `showPreview` silently does nothing while the bubble is hidden.
- **Whole-field replace.** `ACTION_SET_TEXT` replaces the entire field with what the node reported. Hint detection (`isShowingHintText`, or text equal to `hintText`) must stay, or the hint gets spliced into the text. On Android 10+ the old clip usually can't be read, so it is usually not restored after a paste.
- `ScreenWatcher`'s receiver must be `RECEIVER_EXPORTED`, because USER_PRESENT comes from System UI's uid.
- **Keep the pure classes pure.** `GlassStyle`, `BubbleGeometry`, `FullscreenDetector` and `TextPostProcessor` must stay free of `android.*` so the JVM tests run. That is why `TYPE_APPLICATION=1` and `TYPE_SYSTEM=3` are copied constants and colours are plain ARGB ints.
- **Unknown is not closed.** If `windows` can't be read, the last keyboard state is kept. `flagRetrieveInteractiveWindows` in the XML is required, or `windows` returns nothing.
- **One ASR session at a time.** `AsrHost` serves a single session. If the Talk screen ([../ui/home/InAppDictation.kt](../ui/home/InAppDictation.kt)) is listening, the bubble fails with "Voice is already listening". Starting a dictation always stops read-aloud, so the phone's own voice is never recorded.
- **TalkBack.** Taps go through `performClick`, where TalkBack's double-tap also arrives. Hold-to-talk can't be reached with TalkBack; the accessible path is the menu mic (hands-free).
- `GlassPainter.prepare` switches to a software layer before API 28, because `BlurMaskFilter` shadows need it.

## How to extend
- **A bubble setting:** add the field, key and setter in [../data/SettingsRepository.kt](../data/SettingsRepository.kt), and a control in `ui/settings/SettingsScreen.kt` (look settings go in `BubbleLook.kt`). Then read it in `DictationService.applyBubbleSettings` (look) or `updateVisibility` (show/hide), with a setter on `BubbleController` if it needs one.
- **A hide reason:** add a `BubbleStatus` with a user-facing label. Slot it into `updateVisibility` at the right precedence; anything ranked above `controller.active` hides the bubble mid-dictation, so stop the dictation there as TURNED_OFF does. Feed the signal from `refreshInput`, `ScreenWatcher`, or `onConfigurationChanged` for orientation/size (add an `updateVisibility()` call there), and add any new event type to `accessibility_service_config.xml`. Add the `BubbleStatus` label; Settings' "Now:" line shows it automatically.
- **A menu button:** add a `MenuButtonView` to `menuRoot` in `BubbleController`. Extend `BubbleGeometry.fan` and `menuBox` (reach) so it fits every tilt, and the loops in `expand`, `collapse` and `resetButtons`. Add a `Callbacks` method, implement it in `DictationService`, and update `BubbleGeometryTest`.
- **A bubble mode:** add it to `BubbleView.Mode` and draw it in `onDraw`. Then decide whether it counts as recording in `BubbleController.setMode` (keep-screen-on, collapse) and in `canFade`, and whether `DictationService.idleMode` should return it.
- **An ASR engine:** usually nothing changes here, because `begin()` only tells SYSTEM from on-device. See [../asr/CLAUDE.md](../asr/CLAUDE.md). Mirror any change to session start-up in `ui/home/InAppDictation.kt`.
- **A text clean-up:** write a pure function in `TextPostProcessor`, gate it with a setting in `DictationController.deliver`, and add cases to `TextPostProcessorTest`.
- **An insertion strategy:** add an `InsertMethod` in data and, if needed, an `InsertResult` (its lowercase name is stored as History `result`). Then branch in `TextInserter.insert`.

## Tests
These tests live in [test/.../dictation](../../../../../../test/java/app/voice/dictate/dictation/). They are plain JVM tests, with no Robolectric:
- `TextPostProcessorTest`: splicing, spacing, capitals and fillers, plus `asr.TextAccumulator`.
- `BubbleGeometryTest`: docking, keyboard clearance, the tuck threshold, fan tilts, window boxes and pill side.
- `FullscreenDetectorTest`: fail-open, the second signal and the status-bar shape.
- `GlassStyleTest`: mark contrast, opacity, pill and tab solidity, plus `data.BubblePalette` presets and `legacyBubbleSizeDp`.

Nothing tests `DictationService`, `DictationController`, `BubbleController`, `TextInserter`, `ScreenWatcher` or the views; check those on a device. See [../../../../../../test/CLAUDE.md](../../../../../../test/CLAUDE.md).
