package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.OrderStatistics
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.clip
import com.msmobile.aurum.processing.lutOf
import com.msmobile.aurum.processing.mergeAndRelease
import com.msmobile.aurum.processing.splitChannels
import com.msmobile.aurum.processing.toFloatArray
import com.msmobile.aurum.processing.toMat
import com.msmobile.aurum.processing.truncateToU8
import com.msmobile.aurum.processing.use
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.ln

/**
 * `local_hdr(img, amount)`, Smart HDR: splits L into base (illumination) and detail, compresses the base and opens
 * the shadows.
 *
 * ```
 * lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
 * l = lab[..., 0] / 255.0
 * log_l = np.log(l + 0.01)
 * h, w = l.shape
 * small = cv2.resize(log_l, (w // 4, h // 4), interpolation=cv2.INTER_AREA)
 * small = cv2.bilateralFilter(small, d=15, sigmaColor=0.4, sigmaSpace=max(small.shape) / 60)
 * base = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
 * detail = log_l - base
 * mid = np.median(base)
 * base = mid + (base - mid) * (1 - 0.45 * amount)
 * out = np.exp(base + detail * (1 + 0.15 * amount)) - 0.01
 * shadows = np.clip(1 - out / 0.45, 0, 1) ** 2
 * out = out + 0.08 * amount * shadows
 * out = np.where(out > 0.9, 0.9 + (out - 0.9) * (1 - 0.5 * amount), out)
 * lab[..., 0] = np.clip(out, 0, 1) * 255
 * return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0
 * ```
 */
class LocalHdrStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val amount = params.hdr
        if (amount <= 0) return image.withFloat32Values(image.float32Values())

        val planes = image.toU8().use { u8 ->
            Mat().use { lab ->
                Imgproc.cvtColor(u8, lab, Imgproc.COLOR_BGR2Lab)
                lab.splitChannels()
            }
        }
        val l = planes[0]
        val width = l.cols()
        val height = l.rows()

        val logL = Mat(height, width, CvType.CV_32FC1)
        lutOf(FloatArray(PhotoImage.LEVELS) { ln(it.toFloat() / 255f + 0.01f) }).use { Core.LUT(l, it, logL) }

        val base = Mat()
        Mat().use { small ->
            Imgproc.resize(logL, small, Size((width / 4).toDouble(), (height / 4).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Mat().use { filtered ->
                val sigmaSpace = maxOf(small.rows(), small.cols()) / 60.0
                Imgproc.bilateralFilter(small, filtered, 15, 0.4, sigmaSpace)
                Imgproc.resize(filtered, base, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            }
        }
        val logValues = logL.use { it.toFloatArray() }
        val baseValues = base.use { it.toFloatArray() }

        val mid = OrderStatistics.median(baseValues)
        val compression = (1 - 0.45 * amount).toFloat()
        val detailBoost = (1 + 0.15 * amount).toFloat()
        val shadowLift = (0.08 * amount).toFloat()
        val highlightKeep = (1 - 0.5 * amount).toFloat()
        val outL = ByteArray(logValues.size)
        for (i in logValues.indices) {
            val detail = logValues[i] - baseValues[i]
            val compressed = mid + (baseValues[i] - mid) * compression
            var out = exp(compressed + detail * detailBoost) - 0.01f
            val shadow = (1f - out / 0.45f).clip(0f, 1f)
            out += shadowLift * (shadow * shadow)
            if (out > 0.9f) out = 0.9f + (out - 0.9f) * highlightKeep
            outL[i] = truncateToU8(out.clip(0f, 1f) * 255f).toByte()
        }
        l.release()

        val bgr = Mat()
        listOf(outL.toMat(height, width, CvType.CV_8UC1), planes[1], planes[2]).mergeAndRelease().use {
            Imgproc.cvtColor(it, bgr, Imgproc.COLOR_Lab2BGR)
        }
        return PhotoImage.fromBgr8(bgr)
    }
}
