# Photo processing

Every photo Aurum takes goes through a Kotlin/OpenCV port of [`tools/corrigir_foto.py`](../tools/corrigir_foto.py) (the
"reference script") before it is saved. This document explains each stage, its parameters, the fixed constants inside it,
and how to change any of them safely.

The script is the source of truth. Each step class quotes the Python it ports in its KDoc, and the port is validated to
match it pixel for pixel (see [Validation](#validation)).

## Where things live

| What | Where |
|---|---|
| Parameters and the iPhone preset | `processing/EnhanceParams.kt` |
| One class per stage | `processing/steps/*Step.kt`, each an `ImageProcessingStep` |
| Stage order | `di/ProcessingModule.kt` |
| Runs the stages, logs per-stage timings | `processing/PhotoEnhancer.kt` |
| Per-photo HDR adjustment from the scene light | `processing/SceneAdaptiveHdr.kt` |
| Decode, rotate, scene params, enhance (shared by saved photos and the tuner preview) | `capture/PhotoPipeline.kt` |
| Encode, EXIF, save | `capture/PhotoProcessor.kt` |
| Reference script | `tools/corrigir_foto.py` |

## Pipeline

The image is BGR float in [0, 1] between stages (see `PhotoImage`). Stages run in this order, the same as the script's
`main()`:

| # | Stage | Step class | Params | Domain |
|---|---|---|---|---|
| 1 | White balance | `WhiteBalanceStep` | `whiteBalance`, `warmth` | colour |
| 2 | Levels | `LevelsStep` | `exposure` | exposure |
| 3 | Dynamic range | `DynamicRangeStep` | `claheWeight`, `curve` | contrast |
| 4 | Local HDR | `LocalHdrStep` | `hdr` | dynamic range |
| 5 | Vibrance | `VibranceStep` | `vibrance` | colour |
| 6 | Golden tone | `GoldenToneStep` | `golden` | colour |
| 7 | Texture and grain | `SkinTextureStep` | `texture`, `grain`, `grainSize` | detail / noise |

Texture and grain must stay last: the grain should sit on top of the final tones, not be processed by later stages.

Stages 3–7 each convert to 8-bit and back (`to_u8` truncates), so every one of them quantises the image to 8 bits.

## Parameters

App values default to the `--iphone` preset (`EnhanceParams.IPHONE`). They can be changed on the phone in the
processing tuner (see [Tuning on the phone](#tuning-on-the-phone)). The script's own defaults
differ for some params, and are listed for reference.

| Param (`EnhanceParams`) | Script flag | Range | App (iPhone) | Script default |
|---|---|---|---|---|
| `whiteBalance` | `--wb` | 0–1 | 0.25 | 0.35 |
| `warmth` | `--calor` | -0.05–0.05 | 0.045 | 0.015 |
| `exposure` | `--brilho` | -0.2–0.2 | 0.0 | 0.0 |
| `claheWeight` | `--clahe` | 0–1 | 0.35 | 0.3 |
| `curve` | `--curva` | 0–0.4 | 0.2 | 0.13 |
| `vibrance` | `--vibrance` | 0–0.5 | 0.28 | 0.25 |
| `texture` | `--textura` | 0–2 | 1.0 | 1.0 |
| `grain` | `--grao` | 0–5 | 0.8 | 2.6 |
| `grainSize` | `--grao-tamanho` | 0.3–1.0 | 0.3 | 0.5 |
| `hdr` | `--hdr` | 0–1 | 0.85, scaled per photo (see [Adaptive HDR](#adaptive-hdr)) | 0.0 |
| `golden` | `--dourado` | 0–1 | 1.0 | 0.0 |
| `jpegQuality` | `--qualidade` | 1–100 | 95 | 95 |

The ranges are the script's help text, not enforced limits.

The preset was picked by eye on the reference photo: a warm golden glow (full golden tone, extra warmth and less
white balance correction, so the scene keeps its warmth), open shadows (strong HDR), punchy contrast (curve and local
contrast above the script defaults) and light grain.

## Stages

Each stage lists what it does visually, the parameters and the fixed constants it uses. The constants are in the step code,
not in `EnhanceParams`; changing one means changing the port and the script together (see
[Changing the processing](#changing-the-processing)).

### 1. White balance — `whiteBalance`, `warmth`

Gray-world correction: the per-channel means are pulled towards their common mean, then the image is warmed.

```
gain = 1 + whiteBalance * (mean(B,G,R) / [meanB, meanG, meanR] - 1)
gain *= [1 - warmth, 1, 1 + warmth]   # B, G, R
img = clip(img * gain, 0, 1)
```

- `whiteBalance` 0 leaves the colours alone; 1 is full gray-world, which can neutralise intended colour casts (sunsets,
  tungsten light). 0.25 removes part of a cast while keeping the mood.
- `warmth` > 0 adds red and removes blue; < 0 cools. 0.045 is a clear warm shift.

### 2. Levels — `exposure`

Stretches the luma histogram so the darkest 0.5% and brightest 0.2% of pixels clip, then applies the exposure gain.

```
y = mean of B, G, R per pixel
lo, hi = percentile(y, 0.5), percentile(y, 99.8)
lo *= 0.6                          # keeps some shadow detail instead of crushing to black
img = clip((img - lo) / (hi - lo) * (1 + exposure), 0, 1)
```

- `exposure` is a multiplicative gain: 0.1 brightens by 10%, -0.1 darkens.
- Constants: percentiles 0.5 / 99.8, black-point factor 0.6.

### 3. Dynamic range — `claheWeight`, `curve`

Local contrast (CLAHE) on the Lab L channel, then a global S-curve with highlight protection. `a` and `b` (colour) are
untouched.

```
L2 = claheWeight * CLAHE(L) + (1 - claheWeight) * L     # CLAHE clipLimit 1.2, 8x8 tiles
x = L2 / 255
x = (1 - curve) * x + curve * (0.5 - 0.5 * cos(pi * x)) # S-curve
x -= 0.04 * clip((x - 0.85) / 0.15, 0, 1)                # pulls back the top 15% (forehead/nose shine)
```

- `claheWeight` adds local contrast and clarity; high values look gritty and haloed.
- `curve` adds global contrast (darker shadows, brighter highlights).
- Constants: CLAHE clip limit 1.2 and 8×8 tiles; highlight protection from 0.85, at most -0.04.

### 4. Local HDR — `hdr`

"Smart HDR" style local tone mapping on L: splits the log luminance into a smooth base (illumination) and detail,
compresses the base, then opens shadows and rolls off highlights. Skipped when `hdr` is 0.

```
logL = log(L/255 + 0.01)
base = upscale(bilateral(downscale(logL, 1/4), d=15, sigmaColor=0.4, sigmaSpace=max(side)/60))
detail = logL - base
base = median(base) + (base - median(base)) * (1 - 0.45 * hdr)   # compress tonal range
out = exp(base + detail * (1 + 0.15 * hdr)) - 0.01                 # boost detail
out += 0.08 * hdr * clip(1 - out / 0.45, 0, 1)^2                    # lift shadows below 0.45
out = out > 0.9 ? 0.9 + (out - 0.9) * (1 - 0.5 * hdr) : out          # soft highlight roll-off
```

- `hdr` flattens the lighting: brighter shadows, softer highlights, more local detail. Too much looks flat and "HDR-ish",
  especially in good light, which is why the app scales it per photo.
- Constants: 1/4 working scale; bilateral d 15, sigmaColor 0.4, sigmaSpace = longest side / 60; base compression 0.45;
  detail boost 0.15; shadow lift 0.08 below 0.45; highlight knee 0.9 with roll-off 0.5.
- The slowest stage, because of the bilateral filter and the median.

### 5. Vibrance — `vibrance`

Saturation boost that favours muted colours and protects skin.

```
boost = vibrance * (1 - S)                 # S in [0, 1]
boost *= 0.5 if skin hue                   # H <= 25 or H >= 170 (OpenCV 0-180), and S > 0.08
S = clip(S * (1 + boost), 0, 1)
```

- `vibrance` 0.28 brings back washed-out colour without making already saturated colour or skin garish.
- Constants: skin hue ≤ 25 or ≥ 170, minimum saturation 0.08, skin factor 0.5.

### 6. Golden tone — `golden`

Warm, golden cast in the midtones and highlights; shadows stay neutral. Skipped when `golden` is 0.

```
t = L / 255
weight = clip((t - 0.25) / 0.5, 0, 1) * clip((1 - t) / 0.12, 0, 1)
b += 9   * golden * weight    # yellow
a += 2.5 * golden * weight    # a touch of red, so gold rather than lime green
```

- The weight ramps up from L 0.25 to 0.75, then fades out over the top 12% so whites stay white.
- Constants: Lab b +9, a +2.5 at full strength; ramp 0.25–0.75; white fade 0.88–1.

### 7. Texture and grain — `texture`, `grain`, `grainSize`

Local texture sharpening, then film-like grain. Both are stronger on skin, using a skin mask.

```
mask = GaussianBlur(inRange(YCrCb, (40,135,85), (255,175,130)), sigma 6)   # soft skin mask, 0-1
L += (L - GaussianBlur(L, sigma 1.2)) * texture * (0.25 + 0.35 * mask)

noise = GaussianBlur(normal(0, 1), sigma grainSize); noise /= std(noise)
midtones = clip(1 - |L/255 - 0.55| / 0.45, 0, 1)
L += noise * grain * midtones * (0.35 + 0.65 * mask)
```

- `texture` brings back fine detail (pores, hair, fabric): 0.25× off skin, 0.6× on skin at `texture` 1.
- `grain` is the noise standard deviation in L units (0–255), strongest at mid-grey and fading to 0 at black and white;
  0.35× off skin, 1× on skin. It hides smoothing and banding and gives skin a film look. 0 turns it off.
- `grainSize` is the noise blur sigma: 0.3 is the finest grain; 1.0 is coarse and blotchy.
- Constants: skin YCrCb box Y ≥ 40, Cr 135–175, Cb 85–130; mask blur 6; texture blur 1.2; midtone centre 0.55, width
  0.45; skin weights above.
- The grain is random and unseeded, both in the script and in the app, so it differs between runs and can't be
  validated pixel for pixel.

## Adaptive HDR

The app adds one thing the script doesn't have: `hdr` is scaled per photo by the scene light, because the full strength looks
flat in good lighting. `PhotoPipeline` reads the capture's EXIF and calls `SceneAdaptiveHdr`:

```
EV100 = log2(fNumber² / exposureSeconds) - log2(ISO / 100)
scale = 1                            if EV100 <= 7   (dim indoor)
        0.4                          if EV100 >= 11  (bright indoor, overcast, sun)
        linear from 1 to 0.4         in between
hdr = 0.85 * scale                   # 0.85 down to 0.34
```

When any of the EXIF values is missing, `hdr` stays at 0.85. Only `hdr` changes; every other param is the preset. The
thresholds (`DIM_EV100`, `BRIGHT_EV100`, `BRIGHT_SCALE`) were picked from a handful of phone photos and still need checking
against more bright-light shots. `SceneAdaptiveHdrTest` covers the formula.

## Tuning on the phone

The tune button on the camera opens the processing tuner (`tuner/`). It takes a preview shot with the bound camera, keeps it
in memory only (never on disk), and renders it through `PhotoPipeline`, the same full resolution path a saved photo
takes, with the same decoder, rotation, scene light scaling and steps (`PreviewRenderer`). Only the result is
downscaled, to a long side of 2048 px, for the screen. Every slider change renders again, newest value only, and takes
about as long as processing a photo (1–2 s at 12 MP). A render waits while a photo is being processed, since both
need several hundred MB. **Hold to compare** shows the unprocessed shot. The switch button
alternates between the back and front camera (for the camera screen too) and shoots again once the camera has rebound;
front shots are shown mirrored, like the viewfinder, although the saved photo isn't.

- **Done** saves the params and the *Scale by scene light* flag in `ProcessingSettingsStore` (SharedPreferences), and
  every following photo uses them. The ✕ button and Back discard the changes.
- *Reset to default preset* goes back to `EnhanceParams.IPHONE`. Values that differ from it are highlighted, and each tab
  shows how many of its params changed.
- Turning off *Scale by scene light* skips [Adaptive HDR](#adaptive-hdr), so `hdr` is used as set.
- What the preview can't match: the grain is random, so its pattern differs from the saved photo's (its strength and
  size don't), and the saved photo is JPEG-encoded at `jpegQuality`, which doesn't show at screen size.
- The preview shows the photo downscaled, so fine grain looks softer than when the saved photo is viewed at 100%; the
  zoom button enlarges the 2048 px preview, which is still not 1:1.
- The ranges and steps come from `processing/EnhanceParam.kt`.

## Calibrating on the phone

`tools/run-calibration.sh` processes photos on the connected phone exactly as the camera saves them
(`PhotoProcessor.toJpeg`: `PhotoPipeline`, JPEG encode and EXIF, without the gallery) through the instrumented
`PhotoCalibrationTest`, and pulls the results:

```
sh tools/run-calibration.sh out/ a.jpg b.jpg -e golden 0.8 -e grain 0.5
```

- Params not given keep the preset; `-e scaleHdrBySceneLight false` turns off [Adaptive HDR](#adaptive-hdr), which
  otherwise uses each photo's EXIF like a real capture.
- `out/settings.txt` records the params used. The test is skipped when the phone has no photos to process, so it
  doesn't affect other instrumented runs.

## Validation

`PhotoEnhancerReferenceTest` (instrumented) runs `PhotoEnhancer` with the preset and `grain = 0` on a reference photo and
compares it with the script's output for `--iphone --grao 0`. It must stay above **40 dB PSNR**; the port currently scores
60 dB (max difference 12 levels, 1.4% of samples differ).

- Generate the expected images with `python tools/generate_references.py` (needs `opencv-python` 5.0.0.93 and
  `tools/foto_teste.jpg`; writes `app/src/androidTest/assets/reference/`).
- Run the test on a connected phone with `sh tools/run-reference-test.sh`.
- The test photo and references are personal and gitignored, so CI cannot run this test; run it locally.

What the test does **not** cover:

- Grain (`grain`, `grainSize`), since it's random. Check it by eye.
- Adaptive HDR, since the test calls `PhotoEnhancer` directly with a fixed `hdr`.
- Any param value other than the iPhone preset.

## Changing the processing

- **Tuning a param value** (e.g. a lower `curve`): change the default in `EnhanceParams`, and the matching value in the
  script's `--iphone` defaults so the reference still matches the app. Then regenerate the references and re-run the
  reference test.
- **Changing a constant or a formula**: change the script and the step together, regenerate the references, and re-run the
  test. Changing only the Kotlin side makes the test fail by design.
- **Deliberately diverging from the script** (like adaptive HDR): do it outside the steps, by adjusting `EnhanceParams`
  before calling `PhotoEnhancer`, so the port stays testable against the script.
- **Port fidelity**: the steps reproduce NumPy's float32/float64 behaviour exactly (accumulation order of means,
  `np.percentile` interpolation, truncating `to_u8`). Read a step's KDoc before rewriting its arithmetic; a "cleaner"
  version can cost tens of dB of PSNR.
- **Adding a stage**: implement `ImageProcessingStep` and insert it in `ProcessingModule` in the right position.
  It must not modify its input image.
