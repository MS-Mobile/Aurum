package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.clip
import com.msmobile.aurum.processing.lutOf
import com.msmobile.aurum.processing.mergeAndRelease
import com.msmobile.aurum.processing.splitChannels
import com.msmobile.aurum.processing.truncateToU8
import com.msmobile.aurum.processing.use
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos

/**
 * `dynamic_range(img, clahe_weight, curve)`: blends CLAHE into L, then applies an S-curve with highlight protection.
 *
 * ```
 * lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB)
 * l, a, b = cv2.split(lab)
 * clahe = cv2.createCLAHE(clipLimit=1.2, tileGridSize=(8, 8))
 * l2 = cv2.addWeighted(clahe.apply(l), clahe_weight, l, 1 - clahe_weight, 0)
 * x = l2.astype(np.float32) / 255.0
 * s_curve = 0.5 - 0.5 * np.cos(np.pi * x)
 * x = (1 - curve) * x + curve * s_curve
 * highlights = np.clip((x - 0.85) / 0.15, 0, 1)
 * x -= 0.04 * highlights
 * l2 = np.clip(x * 255, 0, 255).astype(np.uint8)
 * return cv2.cvtColor(cv2.merge([l2, a, b]), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0
 * ```
 *
 * The curve maps an 8-bit L to an 8-bit L, so it is applied as a 256-entry lookup table.
 */
class DynamicRangeStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val planes = image.toU8().use { u8 ->
            Mat().use { lab ->
                Imgproc.cvtColor(u8, lab, Imgproc.COLOR_BGR2Lab)
                lab.splitChannels()
            }
        }
        val l = planes[0]
        val blended = Mat()
        Mat().use { equalized ->
            Imgproc.createCLAHE(1.2, Size(8.0, 8.0)).apply(l, equalized)
            Core.addWeighted(equalized, params.claheWeight, l, 1 - params.claheWeight, 0.0, blended)
        }
        val curved = Mat()
        lutOf(curveTable(params.curve)).use { Core.LUT(blended, it, curved) }
        blended.release()
        l.release()

        val bgr = Mat()
        listOf(curved, planes[1], planes[2]).mergeAndRelease().use { Imgproc.cvtColor(it, bgr, Imgproc.COLOR_Lab2BGR) }
        return PhotoImage.fromBgr8(bgr)
    }

    private fun curveTable(curve: Double): IntArray {
        val pi = PI.toFloat()
        val keep = (1 - curve).toFloat()
        val bend = curve.toFloat()
        return IntArray(PhotoImage.LEVELS) { v ->
            var x = v.toFloat() / 255f
            val sCurve = 0.5f - 0.5f * cos(pi * x)
            x = keep * x + bend * sCurve
            val highlights = ((x - 0.85f) / 0.15f).clip(0f, 1f)
            x -= 0.04f * highlights
            truncateToU8(x * 255f)
        }
    }
}
