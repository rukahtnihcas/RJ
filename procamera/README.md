# ProCam

A manual-control Android camera (Android 10+) that builds every photo from a burst,
and uses free, on-device AI for detail at high zoom. Nothing is uploaded anywhere.

## What happens when you press the shutter

1. **Burst** – 6–12 full-resolution frames at one exposure (Camera2). With HDR on, the
   burst is deliberately under-exposed by a stop so highlights don't clip.
2. **Align** – coarse-to-fine block matching, sub-pixel Lucas-Kanade refinement and a
   robust whole-frame motion model (`imaging/Align.kt`).
3. **Merge** – frames are averaged where they agree with the sharpest frame and ignored
   where they don't, so noise drops without ghosting moving subjects (`Merge.kt`).
4. **Tone and detail** – edge-aware local tone mapping lifts shadows and rolls off
   highlights; chroma denoise; noise-aware sharpening (`Finish.kt`).

## Zoom

- The burst is shot at the nearest lens switch-point at or below the chosen zoom, so the
  pipeline starts from real sensor pixels; the rest of the zoom is a crop.
- **Multi-frame super-resolution (2x)**: hand shake offsets each frame by a different
  sub-pixel amount; the frames are combined on a 2x grid, recovering detail that was
  really measured.
- **AI detail (4x)**: a Real-ESRGAN compact network (`sr_x4.tflite`, BSD-3-Clause) run
  with LiteRT on the GPU, CPU as fallback. Its output is then corrected so that, averaged
  back down, it matches what the sensor recorded.

The label under the zoom slider shows which of these the current zoom will use.
AI-generated detail is plausible texture, not recovered information — turn **AI ZOOM**
off to get only measured pixels. **PNG** saves without compression loss.

## Build

    ./gradlew :imaging:test          # pipeline tests, any JDK 17+, no Android SDK needed
    ./gradlew :app:assembleDebug     # needs the Android SDK

GitHub Actions (`.github/workflows/procam.yml`) runs both and uploads the APK.
`tools/convert_model.py` regenerates the model from the original weights.
