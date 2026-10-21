package com.msmobile.aurum.processing

import android.graphics.Bitmap
import android.os.SystemClock
import com.msmobile.aurum.util.Logger
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoEnhancer"

/** Kotlin port of `corrigir_foto.py`: runs the [steps] in the script's order. */
@Singleton
class PhotoEnhancer @Inject constructor(
    private val steps: List<@JvmSuppressWildcards ImageProcessingStep>,
    private val logger: Logger,
) {
    init {
        check(OpenCVLoader.initLocal()) { "Could not load OpenCV" }
    }

    /** Enhances an 8-bit BGR image, like `cv2.imread` returns it. The caller keeps ownership of [bgr]. */
    fun enhance(bgr: Mat, params: EnhanceParams = EnhanceParams.IPHONE): Mat {
        var image = PhotoImage.fromBgr8(bgr.clone())
        for (step in steps) {
            val start = SystemClock.elapsedRealtime()
            val next = step.process(image, params)
            logger.debug(TAG, "${step::class.simpleName}: ${SystemClock.elapsedRealtime() - start} ms")
            image.close()
            image = next
        }
        return image.toU8().also { image.close() }
    }

    /** Enhances an opaque ARGB_8888 bitmap into a new bitmap of the same size. */
    fun enhance(bitmap: Bitmap, params: EnhanceParams = EnhanceParams.IPHONE): Bitmap {
        val bgr = Mat().use { rgba ->
            Utils.bitmapToMat(bitmap, rgba)
            Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2BGR) }
        }
        val enhanced = bgr.use { enhance(it, params) }
        return enhanced.use { result ->
            Mat().use { rgba ->
                Imgproc.cvtColor(result, rgba, Imgproc.COLOR_BGR2RGBA)
                Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
            }
        }
    }
}
