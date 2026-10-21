package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.clip
import com.msmobile.aurum.processing.toByteArray
import javax.inject.Inject

/**
 * `white_balance(img, strength, warmth)`: gray-world gains pulled towards 1 by `strength`, then warmed.
 *
 * ```
 * means = img.reshape(-1, 3).mean(0)
 * gain = 1 + strength * (means.mean() / means - 1)
 * gain *= np.array([1 - warmth, 1.0, 1 + warmth])  # BGR
 * return np.clip(img * gain, 0, 1)
 * ```
 */
class WhiteBalanceStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val values = image.float32Values()
        val means = channelMeans(image, values)
        val meanOfMeans = (((means[0] + means[1]) + means[2]).toDouble() / 3).toFloat()
        val strength = params.whiteBalance.toFloat()
        val warmth = doubleArrayOf(1 - params.warmth, 1.0, 1 + params.warmth)
        val gain = FloatArray(3) { c ->
            val g = 1f + strength * (meanOfMeans / means[c] - 1f)
            (g.toDouble() * warmth[c]).toFloat() // float32 *= float64 array
        }
        return image.withFloat32Values(
            Array(3) { c -> FloatArray(PhotoImage.LEVELS) { v -> (values[c][v] * gain[c]).clip(0f, 1f) } }
        )
    }

    /**
     * NumPy reduces axis 0 of the (N, 3) float32 array by adding pixel after pixel into a float32 accumulator, which
     * drifts by over 1 % on a 12 MP photo. The validated output depends on that drift, so it is reproduced exactly.
     */
    private fun channelMeans(image: PhotoImage, values: Array<FloatArray>): FloatArray {
        val samples = image.samples.toByteArray()
        val (b, g, r) = values
        var sumB = 0f
        var sumG = 0f
        var sumR = 0f
        var i = 0
        while (i < samples.size) {
            sumB += b[samples[i].toInt() and 0xFF]
            sumG += g[samples[i + 1].toInt() and 0xFF]
            sumR += r[samples[i + 2].toInt() and 0xFF]
            i += 3
        }
        val n = image.pixelCount.toDouble()
        return floatArrayOf((sumB / n).toFloat(), (sumG / n).toFloat(), (sumR / n).toFloat())
    }
}
