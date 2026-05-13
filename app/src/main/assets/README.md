# Mythara wake-word assets

This directory holds the **Lumi** wake-word file plus a short note on
the AccessKey path. The build doesn't crash without the file — the
Settings panel shows a "missing" state and the toggle is inert — but
you need it for actual on-device wake detection.

## What goes here

| Filename       | Size  | Source                                  |
|----------------|-------|-----------------------------------------|
| `Lumi_en.ppn`  | ~10K  | exported from console.picovoice.ai      |

## Generating `Lumi_en.ppn`

Picovoice's console handles the training side; you don't need a GPU,
Colab, or Python. Free-tier accounts can generate custom wake words
for personal sideload use.

1. Sign up at **https://console.picovoice.ai** (free tier).
2. From the console dashboard, open **Porcupine** → **Train Wake Word**.
3. Type the phrase: `Lumi` (case doesn't matter to the trainer; the
   pronunciation you described — "Loomi" — is the natural English
   reading of these four letters).
4. Pick platform **Android**, language **English**.
5. Click **Train** — finishes in ~30 seconds.
6. Download the zip; inside you'll find a file named something like
   `Lumi_en_android_v3_0_0.ppn`.
7. **Rename it to `Lumi_en.ppn`** and drop it in this directory next
   to this README.
8. Rebuild the debug APK (`./gradlew :app:assembleDebug`) and reinstall.

## The AccessKey

Porcupine needs a one-time **AccessKey** to initialise at runtime. From
the same console:

1. Click your profile name top-right → **AccessKey**.
2. Copy the displayed key (~50 chars, looks like base64).
3. In Mythara: main Settings → 'Lumi' wake word panel → paste into the
   AccessKey field → tap **save key**. It's Tink-encrypted at rest.

The Picovoice SDK uses the key locally for signature validation; the
runtime works without internet once the key is set.

## Verifying

After install + key paste:

- Open Mythara → main Settings → "Lumi wake word" panel.
- Grant RECORD_AUDIO when prompted.
- Toggle ON. Status pill should show `● listening for 'Lumi'`.
- Speak the phrase — fires log to `Mythara/Wake` in logcat with an
  index (always 0 for our single keyword) and timestamp.

## Why Porcupine over openWakeWord?

We tried openWakeWord first (Apache 2.0, fully open) but training a
custom phrase needs a ~45-minute Colab run plus three bundled ONNX
files. Porcupine's free tier delivers the same result with a 30-second
console export, one .ppn file, and a paste-once AccessKey. Trade-off:
soft dependency on Picovoice for the SDK + AccessKey, but the runtime
itself is fully on-device. The AccessKey lives Tink-encrypted at rest;
it's never sent anywhere by Mythara's code path.
