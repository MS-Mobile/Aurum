package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.PhotoImage
import com.msmobile.aurum.processing.clip
import com.msmobile.aurum.processing.toByteArray
import com.msmobile.aurum.processing.toMat
import com.msmobile.aurum.processing.truncateToU8
import com.msmobile.aurum.processing.use
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * `golden_tone(img, amount)`: golden toning in the midtones and highlights; shadows stay neutral.
 *
 * ```
 * lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
 * t = lab[..., 0] / 255.0
 * weight = np.clip((t - 0.25) / 0.5, 0, 1) * np.clip((1.0 - t) / 0.12, 0, 1)
 * lab[..., 2] += 9 * amount * weight   # yellow
 * lab[..., 1] += 2.5 * amount * weight  # a touch of red = gold, not lime green
 * lab = np.clip(lab, 0, 255)
 * return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32) / 255.0
 * ```
 */
class GoldenToneStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val amount = params.golden
        if (amount <= 0) return image.withFloat32Values(image.float32Values())

        val lab = image.toU8().use { u8 ->
            Mat().use { labMat ->
                Imgproc.cvtColor(u8, labMat, Imgproc.COLOR_BGR2Lab)
                labMat.toByteArray()
            }
        }
        val weight = FloatArray(PhotoImage.LEVELS) { l ->
            val t = l.toFloat() / 255f
            ((t - 0.25f) / 0.5f).clip(0f, 1f) * ((1f - t) / 0.12f).clip(0f, 1f)
        }
        val yellow = (9 * amount).toFloat()
        val red = (2.5 * amount).toFloat()
        var i = 0
        while (i < lab.size) {
            val w = weight[lab[i].toInt() and 0xFF]
            lab[i + 1] = truncateToU8((lab[i + 1].toInt() and 0xFF) + red * w).toByte()
            lab[i + 2] = truncateToU8((lab[i + 2].toInt() and 0xFF) + yellow * w).toByte()
            i += 3
        }
        val bgr = Mat()
        lab.toMat(image.height, image.width, CvType.CV_8UC3).use { Imgproc.cvtColor(it, bgr, Imgproc.COLOR_Lab2BGR) }
        return PhotoImage.fromBgr8(bgr)
    }
}
