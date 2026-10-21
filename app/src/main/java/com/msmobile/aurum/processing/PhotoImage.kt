package com.msmobile.aurum.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * The image as `corrigir_foto.py` holds it between steps: a BGR float image with values in [0, 1].
 *
 * Every value the script produces is a per-channel function of an 8-bit sample. White balance and levels are point
 * operations on the decoded photo, and every later step returns `u8 / 255`. So the image is stored as 8-bit BGR
 * [samples] plus one 256-entry value table per channel, instead of a 3-channel float image. This is exact, and
 * avoids 150 MB float buffers for a 12 MP photo.
 *
 * The tables also remember the NumPy dtype of the values, because `to_u8` rounds differently in float32 and
 * float64 (`levels` returns float64, because `np.percentile` yields float64 scalars).
 */
class PhotoImage private constructor(
    /** 8-bit BGR samples (CV_8UC3, continuous). Owned by this image. */
    val samples: Mat,
    private val values: Array<DoubleArray>,
    val precision: Precision,
) : AutoCloseable {

    enum class Precision { FLOAT32, FLOAT64 }

    val width: Int get() = samples.cols()
    val height: Int get() = samples.rows()
    val pixelCount: Int get() = width * height

    /** Value tables as float32, indexed `[channel][sample]`. Only valid for [Precision.FLOAT32] images. */
    fun float32Values(): Array<FloatArray> {
        check(precision == Precision.FLOAT32) { "Image values are float64" }
        return Array(CHANNELS) { c -> FloatArray(LEVELS) { values[c][it].toFloat() } }
    }

    /** Value tables as float64, indexed `[channel][sample]`. */
    fun float64Values(): Array<DoubleArray> = Array(CHANNELS) { values[it].copyOf() }

    fun withFloat32Values(table: Array<FloatArray>): PhotoImage =
        PhotoImage(samples.clone(), Array(CHANNELS) { c -> DoubleArray(LEVELS) { table[c][it].toDouble() } }, Precision.FLOAT32)

    fun withFloat64Values(table: Array<DoubleArray>): PhotoImage =
        PhotoImage(samples.clone(), Array(CHANNELS) { table[it].copyOf() }, Precision.FLOAT64)

    /** The script's `to_u8(img)`: `np.clip(img * 255, 0, 255).astype(np.uint8)`, truncating. Caller releases the result. */
    fun toU8(): Mat {
        val lut = ByteArray(LEVELS * CHANNELS)
        var identity = true
        for (c in 0 until CHANNELS) {
            for (v in 0 until LEVELS) {
                val q = when (precision) {
                    Precision.FLOAT32 -> truncateToU8(values[c][v].toFloat() * 255f)
                    Precision.FLOAT64 -> truncateToU8(values[c][v] * 255.0)
                }
                if (q != v) identity = false
                lut[v * CHANNELS + c] = q.toByte()
            }
        }
        if (identity) return samples.clone()
        val lutMat = Mat(1, LEVELS, CvType.CV_8UC3)
        lutMat.put(0, 0, lut)
        val out = Mat()
        Core.LUT(samples, lutMat, out)
        lutMat.release()
        return out
    }

    override fun close() = samples.release()

    companion object {
        const val CHANNELS = 3
        const val LEVELS = 256

        /** Wraps 8-bit BGR samples (takes ownership) as the script's `u8.astype(np.float32) / 255.0`. */
        fun fromBgr8(samples: Mat): PhotoImage {
            require(samples.type() == CvType.CV_8UC3 && samples.isContinuous) { "Expected continuous CV_8UC3" }
            val table = DoubleArray(LEVELS) { (it.toFloat() / 255f).toDouble() }
            return PhotoImage(samples, Array(CHANNELS) { table.copyOf() }, Precision.FLOAT32)
        }
    }
}

/** `np.clip(x, 0, 255).astype(np.uint8)` for a float32 value. */
internal fun truncateToU8(x: Float): Int = when {
    x >= 255f -> 255
    x > 0f -> x.toInt()
    else -> 0
}

/** `np.clip(x, 0, 255).astype(np.uint8)` for a float64 value. */
internal fun truncateToU8(x: Double): Int = when {
    x >= 255.0 -> 255
    x > 0.0 -> x.toInt()
    else -> 0
}

/** `np.clip(x, lo, hi)` in float32. */
internal fun Float.clip(lo: Float, hi: Float): Float = if (this < lo) lo else if (this > hi) hi else this
