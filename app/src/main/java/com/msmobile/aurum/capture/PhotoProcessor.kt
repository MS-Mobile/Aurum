package com.msmobile.aurum.capture

import android.net.Uri
import android.os.SystemClock
import com.msmobile.aurum.processing.PhotoEnhancer
import com.msmobile.aurum.processing.ProcessingSettings
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

    suspend fun process(photo: CapturedPhoto, settings: ProcessingSettings): Uri =
        withContext(dispatcherProvider.default) {
            mutex.withLock {
                val start = SystemClock.elapsedRealtime()
                val params = settings.params
                val exif = CaptureExif.read(photo)
                val ev100 = exif.ev100
                val sceneParams = if (settings.scaleHdrBySceneLight) SceneAdaptiveHdr.adapt(params, ev100) else params
                val rotation = exif.rotationDegrees

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
