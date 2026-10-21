package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.OrderStatistics
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.toByteArray
import javax.inject.Inject

/**
 * `levels(img, exposure)`: stretches the 0.5 / 99.8 luma percentiles to the full range.
 *
 * ```
 * y = img.mean(2)
 * lo, hi = np.percentile(y, (0.5, 99.8))
 * lo *= 0.6
 * img = (img - lo) / max(hi - lo, 1e-6)
 * return np.clip(img * (1 + exposure), 0, 1)
 * ```
 *
 * `lo` and `hi` are float64 NumPy scalars, so the result is float64.
 */
class LevelsStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val values = image.float32Values()
        val (percentileLow, high) = OrderStatistics.percentile(luma(image, values), doubleArrayOf(0.5, 99.8))
        val low = percentileLow * 0.6
        val range = maxOf(high - low, 1e-6)
        val gain = 1 + params.exposure
        return image.withFloat64Values(
            Array(3) { c ->
                DoubleArray(PhotoImage.LEVELS) { v -> ((values[c][v] - low) / range * gain).coerceIn(0.0, 1.0) }
            }
        )
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
