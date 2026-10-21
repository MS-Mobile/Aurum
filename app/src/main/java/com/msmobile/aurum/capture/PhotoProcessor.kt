package com.msmobile.aurum.capture

import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.PhotoEnhancer
import com.msmobile.aurum.processing.SceneAdaptiveHdr
import com.msmobile.aurum.processing.use
import com.msmobile.aurum.util.DispatcherProvider
import com.msmobile.aurum.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoProcessor"

/**
 * Captured JPEG → upright BGR (as `cv2.imread` would decode it) → [PhotoEnhancer] → JPEG with the original EXIF → gallery.
 *
 * Photos are processed one at a time, since a full resolution photo needs several hundred MB while being processed.
 */
@Singleton
class PhotoProcessor @Inject constructor(
    private val enhancer: PhotoEnhancer,
    private val gallery: PhotoGallery,
    private val dispatcherProvider: DispatcherProvider,
    private val logger: Logger,
) {
    private val mutex = Mutex()

    suspend fun process(photo: CapturedPhoto, params: EnhanceParams = EnhanceParams.IPHONE): Uri =
        withContext(dispatcherProvider.default) {
            mutex.withLock {
                val start = SystemClock.elapsedRealtime()
                val exif = ExifInterface(ByteArrayInputStream(photo.jpeg))
                val ev100 = SceneAdaptiveHdr.ev100(
                    fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN).takeUnless { it.isNaN() },
                    exposureSeconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN).takeUnless { it.isNaN() },
                    iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.toDouble(),
                )
                val sceneParams = SceneAdaptiveHdr.adapt(params, ev100)
                // EXIF describes these bytes; fall back to CameraX when the HAL left it out. Mirroring flags are
                // ignored on purpose so selfies are saved as others see the scene, not mirrored.
                val rotation = if (exif.hasAttribute(ExifInterface.TAG_ORIENTATION) &&
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) != ExifInterface.ORIENTATION_UNDEFINED
                ) exif.rotationDegrees else photo.rotationDegrees

                val upright = decodeUpright(photo.jpeg, rotation)
                val width = upright.cols()
                val height = upright.rows()
                val enhanced = upright.use { enhancer.enhance(it, sceneParams) }
                val encoded = enhanced.use { encodeJpeg(it, params.jpegQuality) }
                val jpeg = ExifTransplant.transplant(source = photo.jpeg, target = encoded)
                val uri = gallery.save(jpeg, width, height)
                logger.info(TAG, "${width}x$height, EXIF rotation $rotation (CameraX ${photo.rotationDegrees}), " +
                    "EV100 ${ev100?.let { "%.1f".format(it) }}, HDR ${"%.2f".format(sceneParams.hdr)}, " +
                    "${SystemClock.elapsedRealtime() - start} ms -> $uri")
                uri
            }
        }

    private fun decodeUpright(jpeg: ByteArray, rotationDegrees: Int): Mat {
        val decoded = MatOfByte(*jpeg).use {
            Imgcodecs.imdecode(it, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        }
        check(!decoded.empty()) { "Could not decode the captured JPEG" }
        val rotateCode = when (rotationDegrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return decoded
        }
        return Mat().also { Core.rotate(decoded, it, rotateCode); decoded.release() }
    }

    /** `cv2.imwrite(path, result, [cv2.IMWRITE_JPEG_QUALITY, quality])`, in memory. */
    private fun encodeJpeg(bgr: Mat, quality: Int): ByteArray =
        MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality).use { options ->
            MatOfByte().use { out ->
                check(Imgcodecs.imencode(".jpg", bgr, out, options)) { "Could not encode JPEG" }
                out.toArray()
            }
        }
}
