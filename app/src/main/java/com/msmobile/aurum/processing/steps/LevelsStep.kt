package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.OrderStatistics
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.toByteArray
import com.msmobile.aurum.processing.toMat
import com.msmobile.aurum.processing.truncateToU8
import org.opencv.core.CvType
import javax.inject.Inject
import kotlin.math.pow

/**
 * `levels(img, exposure, colour_stretch)`: stretches the 0.5 / 99.8 luma percentiles to the full range.
 *
 * ```
 * y = img.mean(2)
 * lo, hi = np.percentile(y, (0.5, 99.8))
 * lo *= 0.6
 * if colour_stretch >= 1:
 *     img = (img - lo) / max(hi - lo, 1e-6)
 *     return np.clip(img * (1 + exposure), 0, 1)
 * gain = (1 + exposure) / max(hi - lo, 1e-6)
 * grey = (y - lo) * gain
 * colour = (img - y[..., None]) * gain ** colour_stretch
 * return np.clip(grey[..., None] + colour, 0, 1)
 * ```
 *
 * `lo` and `hi` are float64 NumPy scalars, so the result is float64. With `colour_stretch` 1 every channel is a
 * function of its own sample, so it is a value table. Below 1 each pixel depends on its grey `y`, which mixes the
 * channels, so the result is computed per pixel and kept as the 8-bit samples `to_u8` would make of it: the next step
 * starts with `to_u8`, so that is exact.
 */
class LevelsStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val values = image.float32Values()
        val luma = luma(image, values)
        val (percentileLow, high) = OrderStatistics.percentile(luma, doubleArrayOf(0.5, 99.8))
        val low = percentileLow * 0.6
        val range = maxOf(high - low, 1e-6)
        if (params.stretchSaturation < 1) return stretchBrightness(image, values, luma, low, range, params)

        val gain = 1 + params.exposure
        return image.withFloat64Values(
            Array(3) { c ->
                DoubleArray(PhotoImage.LEVELS) { v -> ((values[c][v] - low) / range * gain).coerceIn(0.0, 1.0) }
            }
        )
    }

    /** The `colour_stretch < 1` branch, per pixel, as `to_u8` of its float64 result. */
    private fun stretchBrightness(
        image: PhotoImage,
        values: Array<FloatArray>,
        luma: FloatArray,
        low: Double,
        range: Double,
        params: EnhanceParams,
    ): PhotoImage {
        val gain = (1 + params.exposure) / range
        val colourGain = gain.pow(params.stretchSaturation)
        val samples = image.samples.toByteArray()
        val out = ByteArray(samples.size)
        for (p in luma.indices) {
            val y = luma[p]
            val grey = (y.toDouble() - low) * gain
            for (c in 0 until 3) {
                val i = p * 3 + c
                val colour = (values[c][samples[i].toInt() and 0xFF] - y).toDouble() * colourGain
                out[i] = truncateToU8((grey + colour).coerceIn(0.0, 1.0) * 255.0).toByte()
            }
        }
        return PhotoImage.fromBgr8(out.toMat(image.height, image.width, CvType.CV_8UC3))
    }

    /** `img.mean(2)`: float32 sum of B, G, R in order, divided by the count. */
    private fun luma(image: PhotoImage, values: Array<FloatArray>): FloatArray {
        val samples = image.samples.toByteArray()
        val (b, g, r) = values
        return FloatArray(image.pixelCount) { p ->
            val i = p * 3
            val sum = b[samples[i].toInt() and 0xFF] + g[samples[i + 1].toInt() and 0xFF] + r[samples[i + 2].toInt() and 0xFF]
            (sum.toDouble() / 3).toFloat()
        }
    }
}
