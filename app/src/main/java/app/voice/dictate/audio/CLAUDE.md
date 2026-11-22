# audio: microphone capture and WAV decoding into 16 kHz mono floats

## Purpose
Turns the mic (or a WAV file) into the exact sample format every ASR engine expects: 16 kHz mono `FloatArray`
blocks of 1280 samples (80 ms). Android port of the prototype's `capture-worklet.js`.

## Files
| File | Role | Key types / entry points |
|---|---|---|
| `AudioCapture.kt` | `AudioRecord` on a dedicated `"mic"` thread; emits blocks, RMS level, and a one-shot "silenced" signal | `AudioCapture.start(onAudio, onLevel, onSilenced): Boolean`, `stop()`, `BLOCK = 1280` |
| `WavReader.kt` | Parses RIFF/WAVE (8/16/32-bit int, 32-bit float), mixes to mono, linear-resamples to the target rate | `WavReader.decode(bytes, targetRate = 16000)` |

## How it works
- `asr/Dictation.kt` (`OnnxDictation`) owns an `AudioCapture`: `start(onAudio = host::accept, …)` feeds
  `AsrHost` straight from the mic thread; level and silenced callbacks are posted to main. `stop()` runs on a
  background executor, then `host.stop`. See [../asr/CLAUDE.md](../asr/CLAUDE.md).
- `onSilenced` fires once if the first 1.5 s (`SAMPLE_RATE * 3 / 2` samples) has peak exactly 0, which is what
  Android delivers when it blocks background mic access. `dictation/DictationController.onSilenced` reacts by
  making the service a microphone foreground service and restarting capture once ([../dictation/CLAUDE.md](../dictation/CLAUDE.md)).
- `WavReader` is used by the Talk tab's sample button (`ui/home/InAppDictation.kt`, `assets/samples/jfk.wav`) and by
  `LogMelTest`; both chunk the result in `AudioCapture.BLOCK`-sized pieces to mimic the mic.

## Invariants & gotchas
- Sample rate comes from `asr/LogMel.kt` (`SAMPLE_RATE = 16000`); `AudioRecord` records at 16 kHz directly, there is
  no resampler on the live path. Engines and LogMel assume 16 kHz.
- Block size 1280 matches the prototype; `LogMelTest`'s golden output was produced with the same block size, so
  changing `BLOCK` can shift frame boundaries and break parity.
- Prefers `ENCODING_PCM_FLOAT`, falls back to 16-bit PCM (scaled by 1/32768). Source is `VOICE_RECOGNITION`.
- `onAudio` gets a copy of each block and is called on the mic thread: receivers must be thread-safe.
- `start()` calls `stop()` first; `stop()` joins the thread for up to 1 s so the last block is delivered before
  the host is told to finish.
- `RECORD_AUDIO` is not checked here (`@SuppressLint`); callers must have the permission or `start()` returns false.

## How to extend
- Different capture source or gain: change `AudioCapture.start` only; keep block size and rate.
- New WAV encodings: add a branch in `WavReader.decode`'s sample `when`.

## Tests
`app/src/test/.../asr/LogMelTest.kt` uses `WavReader` and `AudioCapture.BLOCK` indirectly. `AudioCapture` itself
is untested (needs a device). See [../../../../../../test/CLAUDE.md](../../../../../../test/CLAUDE.md).
