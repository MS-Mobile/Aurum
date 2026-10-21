package com.msmobile.aurum.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

internal fun Mat.toByteArray(): ByteArray {
    check(isContinuous && depth() == CvType.CV_8U)
    return ByteArray(total().toInt() * channels()).also { get(0, 0, it) }
}

internal fun Mat.toFloatArray(): FloatArray {
    check(isContinuous && depth() == CvType.CV_32F)
    return FloatArray(total().toInt() * channels()).also { get(0, 0, it) }
}

internal fun ByteArray.toMat(rows: Int, cols: Int, type: Int): Mat = Mat(rows, cols, type).also { it.put(0, 0, this) }

internal fun FloatArray.toMat(rows: Int, cols: Int): Mat = Mat(rows, cols, CvType.CV_32FC1).also { it.put(0, 0, this) }

/** `cv2.split`. Caller releases the planes. */
internal fun Mat.splitChannels(): List<Mat> = ArrayList<Mat>(channels()).also { Core.split(this, it) }

/** `cv2.merge`, releasing the planes. */
internal fun List<Mat>.mergeAndRelease(): Mat {
    val out = Mat()
    Core.merge(this, out)
    forEach { it.release() }
    return out
}

/** A 1x256 single-channel lookup table for `cv2.LUT`, from 8-bit outputs. */
internal fun lutOf(table: IntArray): Mat {
    require(table.size == PhotoImage.LEVELS)
    return ByteArray(table.size) { table[it].toByte() }.toMat(1, table.size, CvType.CV_8UC1)
}

/** A 1x256 float lookup table for `cv2.LUT`. */
internal fun lutOf(table: FloatArray): Mat {
    require(table.size == PhotoImage.LEVELS)
    return table.toMat(1, table.size)
}

internal inline fun <M : Mat, T> M.use(block: (M) -> T): T = try {
    block(this)
} finally {
    release()
}
