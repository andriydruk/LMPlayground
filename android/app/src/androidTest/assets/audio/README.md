# Speech fixtures

Audio for the speech-recognition (voice dictation) probes. Shared by the macOS
harness and the on-device instrumented test, so both listen to the same clips.

Run the speech probes on a Mac — no device, no emulator:

```
./gradlew :model-harness:capabilityReport --args="--probes asr"
```

It needs `tdt-0.6b-v3-q4_k.gguf` in the models cache
(`~/.cache/lmplayground/models`, or `$LMP_MODELS_DIR`); the probe reports
"not downloaded" and moves on if it is absent.

Parakeet picks its backend from `PARAKEET_DEVICE` (CPU unless set) and its
thread count from `LMP_ASR_THREADS`, so a Metal comparison needs no rebuild:

```
PARAKEET_DEVICE=MTL0 ./gradlew :model-harness:capabilityReport --args="--probes asr"
```

## jfk.wav

11 s, 16 kHz mono. An excerpt of John F. Kennedy's 1961 inaugural address, taken
from the `ggml-org/whisper.cpp` sample set. A US Government work, so it is in the
public domain. Real recorded speech — the only clip here that is — which makes it
the accuracy anchor rather than just a smoke test.

Reference transcript (see `transcripts.json`):

> And so my fellow Americans, ask not what your country can do for you, ask what
> you can do for your country.

## Generated clips

The multilingual clips are **not** committed: they are synthesized on demand by
`say` into `~/.cache/lmplayground/audio` (override with `$LMP_AUDIO_DIR`), which
keeps third-party recordings of uncertain licence out of the repo. A probe run
skips any language whose macOS voice is not installed.

Synthetic speech is easier to recognize than real speech, so treat those clips as
coverage that the multilingual path and language auto-detection work — not as
evidence of real-world accuracy. Only jfk.wav speaks to that.
