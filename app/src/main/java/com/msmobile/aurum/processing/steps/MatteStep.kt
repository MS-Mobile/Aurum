package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.clip
import com.msmobile.aurum.processing.mergeAndRelease
import com.msmobile.aurum.processing.splitChannels
import com.msmobile.aurum.processing.toFloatArray
import com.msmobile.aurum.processing.toMat
import com.msmobile.aurum.processing.truncateToU8
import com.msmobile.aurum.processing.use
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * `matte(img, amount)`: tones down shine on skin, darkening its highlights and pulling their colour towards the
 * surrounding skin. Skipped when `matte` is 0.
 *
 * ```
 * mask = skin_mask(img)
 * lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
 * t = lab[..., 0] / 255.0
 * shine = np.clip((t - 0.7) / 0.25, 0, 1)
 * h, w = t.shape
 * small = (w // 4, h // 4)
 * near = cv2.GaussianBlur(cv2.resize(mask, small, interpolation=cv2.INTER_AREA), (0, 0), 3)
 * near = cv2.resize(np.clip(near * 1.5, 0, 1), (w, h), interpolation=cv2.INTER_LINEAR)
 * amount_px = shine * near * amount
 * lab[..., 0] -= amount_px * (t - 0.7) * 255 * 0.6
 * weight = mask * (1 - shine) + 1e-3
 * weight_blur = cv2.GaussianBlur(cv2.resize(weight, small, interpolation=cv2.INTER_AREA), (0, 0), 10)
 * for c in (1, 2):
 *     local = cv2.GaussianBlur(cv2.resize(lab[..., c] * weight, small, interpolation=cv2.INTER_AREA), (0, 0), 10)
 *     local = cv2.resize(local / weight_blur, (w, h), interpolation=cv2.INTER_LINEAR)
 *     lab[..., c] += amount_px * 0.7 * (local - lab[..., c])
 * lab = np.clip(lab, 0, 255)
 * return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0
 * ```
 *
 * The wide blurs run at a quarter of the size, like [LocalHdrStep]'s base layer.
 */
class MatteStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        if (params.matte <= 0) return image.withFloat32Values(image.float32Values())

        val width = image.width
        val height = image.height
        val full = Size(width.toDouble(), height.toDouble())
        val small = Size((width / 4).toDouble(), (height / 4).toDouble())
        val u8 = image.toU8()
        val mask = skinMask(u8)
        val planes = Mat().use { lab ->
            Imgproc.cvtColor(u8, lab, Imgproc.COLOR_BGR2Lab)
            lab.splitChannels()
        }
        u8.release()
        val lab = Array(3) { c -> planes[c].use { plane -> Mat().use { plane.convertTo(it, CvType.CV_32F); it.toFloatArray() } } }
        val l = lab[0]

        val shine = FloatArray(l.size) { ((l[it] / 255f - 0.7f) / 0.25f).clip(0f, 1f) }
        val near = mask.toMat(height, width).use { maskMat ->
            val blurred = shrink(maskMat, small).use { shrunk -> Mat().also { Imgproc.GaussianBlur(shrunk, it, Size(0.0, 0.0), 3.0) } }
            val clipped = blurred.use { it.toFloatArray() }.let { values -> FloatArray(values.size) { (values[it] * 1.5f).clip(0f, 1f) } }
            grow(clipped.toMat(small.height.toInt(), small.width.toInt()), full)
        }
        val amount = params.matte.toFloat()
        val amountPx = FloatArray(l.size) { shine[it] * near[it] * amount }
        for (i in l.indices) {
            val t = l[i] / 255f
            l[i] = l[i] - amountPx[i] * (t - 0.7f) * 255f * 0.6f
        }

        val weight = FloatArray(l.size) { mask[it] * (1f - shine[it]) + 1e-3f }
        val weightBlur = weight.toMat(height, width).use { blurSmall(it, small) }
        for (c in 1..2) {
            val channel = lab[c]
            val weighted = FloatArray(channel.size) { channel[it] * weight[it] }
            val localSmall = weighted.toMat(height, width).use { blurSmall(it, small) }
            val ratio = FloatArray(localSmall.size) { localSmall[it] / weightBlur[it] }
            val local = grow(ratio.toMat(small.height.toInt(), small.width.toInt()), full)
            for (i in channel.indices) channel[i] = channel[i] + amountPx[i] * 0.7f * (local[i] - channel[i])
        }

        val bgr = Mat()
        lab.map { values -> ByteArray(values.size) { truncateToU8(values[it]).toByte() }.toMat(height, width, CvType.CV_8UC1) }
            .mergeAndRelease()
            .use { Imgproc.cvtColor(it, bgr, Imgproc.COLOR_Lab2BGR) }
        return PhotoImage.fromBgr8(bgr)
    }

    /** `cv2.resize(src, small, interpolation=cv2.INTER_AREA)`. The caller owns the result. */
    private fun shrink(src: Mat, small: Size): Mat = Mat().also { Imgproc.resize(src, it, small, 0.0, 0.0, Imgproc.INTER_AREA) }

    /** `cv2.GaussianBlur(cv2.resize(src, small, interpolation=cv2.INTER_AREA), (0, 0), 10)`. */
    private fun blurSmall(src: Mat, small: Size): FloatArray = shrink(src, small).use { shrunk ->
        Mat().use { Imgproc.GaussianBlur(shrunk, it, Size(0.0, 0.0), 10.0); it.toFloatArray() }
    }

    /** `cv2.resize(src, (w, h), interpolation=cv2.INTER_LINEAR)`, releasing [src]. */
    private fun grow(src: Mat, full: Size): FloatArray = src.use { values ->
        Mat().use { Imgproc.resize(values, it, full, 0.0, 0.0, Imgproc.INTER_LINEAR); it.toFloatArray() }
    }
}
