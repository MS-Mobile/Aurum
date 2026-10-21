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
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.abs
import kotlin.random.Random

/**
 * `texture_and_grain(img, texture, grain, grain_size)`: local texture enhancement plus film-like grain, both
 * stronger on skin. Always the last step.
 *
 * ```
 * mask = skin_mask(img)
 * lab = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2LAB).astype(np.float32)
 * l = lab[..., 0]
 * detail = l - cv2.GaussianBlur(l, (0, 0), 1.2)
 * l = l + detail * texture * (0.25 + 0.35 * mask)
 *
 * rng = np.random.default_rng()
 * noise = cv2.GaussianBlur(rng.normal(0, 1, l.shape).astype(np.float32), (0, 0), grain_size)
 * noise /= noise.std()
 * midtones = np.clip(1 - np.abs(l / 255.0 - 0.55) / 0.45, 0, 1)
 * l = l + noise * grain * midtones * (0.35 + 0.65 * mask)
 *
 * lab[..., 0] = np.clip(l, 0, 255)
 * return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_LAB2BGR)
 * ```
 *
 * With `grain == 0` the noise term is exactly zero, so it is skipped. The script's grain is unseeded, so it is
 * random per photo here as well.
 */
class SkinTextureStep @Inject constructor() : ImageProcessingStep {

    override fun process(image: PhotoImage, params: EnhanceParams): PhotoImage {
        val u8 = image.toU8()
        val mask = skinMask(u8)
        val planes = Mat().use { lab ->
            Imgproc.cvtColor(u8, lab, Imgproc.COLOR_BGR2Lab)
            lab.splitChannels()
        }
        u8.release()
        val width = image.width
        val height = image.height

        val l = Mat().use { planes[0].convertTo(it, CvType.CV_32F); it.toFloatArray() }
        val blurred = l.toMat(height, width).use { lMat ->
            Mat().use { Imgproc.GaussianBlur(lMat, it, Size(0.0, 0.0), 1.2); it.toFloatArray() }
        }
        val texture = params.texture.toFloat()
        for (i in l.indices) {
            val detail = l[i] - blurred[i]
            l[i] = l[i] + detail * texture * (0.25f + 0.35f * mask[i])
        }
        if (params.grain > 0) addGrain(l, mask, width, height, params)

        val outL = ByteArray(l.size) { truncateToU8(l[it]).toByte() }
        planes[0].release()
        val bgr = Mat()
        listOf(outL.toMat(height, width, CvType.CV_8UC1), planes[1], planes[2]).mergeAndRelease().use {
            Imgproc.cvtColor(it, bgr, Imgproc.COLOR_Lab2BGR)
        }
        return PhotoImage.fromBgr8(bgr)
    }

    private fun addGrain(l: FloatArray, mask: FloatArray, width: Int, height: Int, params: EnhanceParams) {
        val noise = Mat(height, width, CvType.CV_32FC1).use { normal ->
            Core.setRNGSeed(Random.nextInt())
            Core.randn(normal, 0.0, 1.0)
            Mat().use { blurred ->
                Imgproc.GaussianBlur(normal, blurred, Size(0.0, 0.0), params.grainSize)
                val std = MatOfDouble().use { mean -> MatOfDouble().use { dev -> Core.meanStdDev(blurred, mean, dev); dev.get(0, 0)[0] } }
                blurred.toFloatArray().also { values -> val scale = std.toFloat(); for (i in values.indices) values[i] /= scale }
            }
        }
        val grain = params.grain.toFloat()
        for (i in l.indices) {
            val midtones = (1f - abs(l[i] / 255f - 0.55f) / 0.45f).clip(0f, 1f)
            l[i] = l[i] + noise[i] * grain * midtones * (0.35f + 0.65f * mask[i])
        }
    }
}
