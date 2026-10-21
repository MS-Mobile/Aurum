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
 * `vibrance(img, amount)`: boosts low saturation more than high saturation, at half strength on skin hues.
 *
 * ```
 * hsv = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2HSV).astype(np.float32)
 * hue, sat = hsv[..., 0], hsv[..., 1] / 255.0
 * skin = ((hue <= 25) | (hue >= 170)) & (sat > 0.08)
 * boost = amount * (1 - sat)
 * boost = np.where(skin, boost * 0.5, boost)
 * hsv[..., 1] = np.clip(sat * (1 + boost), 0, 1) * 255
 * return cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR).astype(np.float32) / 255.0
 * ```
 *
 * The new saturation depends only on the 8-bit saturation and whether the pixel is skin, so it is looked up.
 */
class VibranceStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val hsv = image.toU8().use { u8 ->
            Mat().use { hsvMat ->
                Imgproc.cvtColor(u8, hsvMat, Imgproc.COLOR_BGR2HSV)
                hsvMat.toByteArray()
            }
        }
        val amount = params.vibrance.toFloat()
        val other = saturationTable(amount, skin = false)
        val skin = saturationTable(amount, skin = true)
        var i = 0
        while (i < hsv.size) {
            val hue = hsv[i].toInt() and 0xFF
            val sat = hsv[i + 1].toInt() and 0xFF
            val isSkin = (hue <= 25 || hue >= 170) && sat.toFloat() / 255f > 0.08f
            hsv[i + 1] = (if (isSkin) skin[sat] else other[sat]).toByte()
            i += 3
        }
        val bgr = Mat()
        hsv.toMat(image.height, image.width, CvType.CV_8UC3).use { Imgproc.cvtColor(it, bgr, Imgproc.COLOR_HSV2BGR) }
        return PhotoImage.fromBgr8(bgr)
    }

    private fun saturationTable(amount: Float, skin: Boolean) = IntArray(PhotoImage.LEVELS) { s ->
        val sat = s.toFloat() / 255f
        var boost = amount * (1f - sat)
        if (skin) boost *= 0.5f
        truncateToU8((sat * (1f + boost)).clip(0f, 1f) * 255f)
    }
}
