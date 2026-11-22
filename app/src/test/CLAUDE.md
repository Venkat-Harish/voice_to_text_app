# test: plain-JVM unit tests for the pure logic, incl. golden parity with the JS prototype

## Purpose
JUnit 4 tests run on the host JVM (no Robolectric, no emulator). They cover only Android-free code: DSP, text
processing, geometry, colour maths, tokenizers. Two golden files pin parity with the web prototype in `../prototype`.

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `java/app/voice/dictate/asr/LogMelTest.kt` | Kotlin `LogMel` vs prototype `logmel.js` on `jfk.wav` (max abs diff < 2e-3, sum rel. diff < 1e-5); silence = `SILENT_LOG` | `matchesPrototypeFrames`, `silenceIsTheSilentLogValue` |
| `java/app/voice/dictate/asr/TokensTest.kt` | `Tokens` (piece-id parsing, join, argmax) and `MoonshineTokenizer` (SentencePiece byte-fallback, ByteLevel) | `TokensTest` |
| `java/app/voice/dictate/data/HistoryRetentionTest.kt` | `HistoryRepository.retain`, `historyLabel`, `formatDuration` | `HistoryRetentionTest` |
| `java/app/voice/dictate/dictation/BubbleGeometryTest.kt` | Dock position, edge snap, tuck threshold, fan layout, window boxes, preview placement (1080×2400 phone) | `BubbleGeometryTest` |
| `java/app/voice/dictate/dictation/FullscreenDetectorTest.kt` | Status-bar heuristics, fail-open, second-signal agreement | `FullscreenDetectorTest` |
| `java/app/voice/dictate/dictation/GlassStyleTest.kt` | Glass fill/icon colours, WCAG mark choice, opacity clamp, `BubblePalette` presets, `legacyBubbleSizeDp` | `GlassStyleTest` |
| `java/app/voice/dictate/dictation/TextPostProcessorTest.kt` | `TextSplicer.splice` (spacing, capitals, selection), `TextPostProcessor.removeFillers`, `asr.TextAccumulator` | `TextPostProcessorTest` |
| `java/app/voice/dictate/tts/G2PTest.kt` | Three classes: `LexiconTest` (streaming JSON parse, heteronyms), `G2PTest` (inflections, acronyms, compounds, numbers, letter rules), `EspeakStyleTest` (misaki → eSpeak IPA) | `LexiconTest`, `G2PTest`, `EspeakStyleTest` |
| `java/app/voice/dictate/tts/TextPrepTest.kt` | `TextPrep.normalize/chunks` vs prototype `text.js`; `NumberWords` | `TextPrepTest`, `NumberWordsTest` |
| `java/app/voice/dictate/tts/VoiceEnginesTest.kt` | Kitten tokens/ids, Kokoro ids + style row, `Npy`/npz parsing, `TtsHost.trimSilence` | `VoiceEnginesTest` |
| `resources/logmel_golden.json` | `{count, sum, frames{index: [mel…]}}` from the prototype's `logmel.js` (Node) on `jfk.wav` in 1280-sample blocks | — |
| `resources/textprep_golden.json` | `[{text, normalized, chunks}]` from the prototype's `normalize()`/`chunks()` (Node) | — |

## How it works
- Run all: `.\gradlew testDebugUnitTest` (from the repo root). One class:
  `.\gradlew testDebugUnitTest --tests "app.voice.dictate.tts.TextPrepTest"`.
- Test packages mirror main packages (`app.voice.dictate.<folder>`).
- `app/build.gradle.kts` sets `unitTests.isReturnDefaultValues = true`: stray `android.util.Log` etc. return
  defaults instead of throwing. Real Android behaviour is never exercised here.

## Invariants & gotchas
- `LogMelTest` opens `src/main/assets/samples/jfk.wav` by a path relative to the working directory, so it only
  passes when run with the `app/` module as working dir (Gradle's default).
- No generator script for the golden files is checked in (neither here nor in `../prototype`). If prototype
  `logmel.js` or `text.js` changes, regenerate by running those functions in Node and dump JSON in the same shape.
  Changing the Kotlin side alone to "fix" a golden diff breaks parity with the prototype's models.
- `G2PTest.realDictionaryCoverage` is skipped unless env `MISAKI_DIR` points at a folder with `us_gold.json` and
  `us_silver.json` (the downloaded misaki dictionaries).
- Kitten symbol ids asserted in `VoiceEnginesTest` come from the prototype's `kitten.js` table; they must match the
  ONNX model's vocabulary, not be "tidied".

## How to extend
- New pure helper in main: put the test in the same package under `java/app/voice/dictate/<folder>/`, name it
  `<Thing>Test.kt`, list it in the table above and in that folder's CLAUDE.md "Tests" section.
- Anything needing `Context`, `AccessibilityService`, ONNX sessions or `AudioRecord` is out of scope here; extract
  the pure part (as `BubbleGeometry`, `FullscreenDetector`, `TextSplicer` were) and test that.

## Tests
This is the test folder. Main-code docs link back here: [asr](../main/java/app/voice/dictate/asr/CLAUDE.md),
[data](../main/java/app/voice/dictate/data/CLAUDE.md), [dictation](../main/java/app/voice/dictate/dictation/CLAUDE.md),
[tts](../main/java/app/voice/dictate/tts/CLAUDE.md).
