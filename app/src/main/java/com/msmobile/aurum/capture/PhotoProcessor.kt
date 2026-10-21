package com.msmobile.aurum.capture

import android.net.Uri
import android.os.SystemClock
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.use
import com.msmobile.aurum.util.DispatcherProvider
import com.msmobile.aurum.util.Logger
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhotoProcessor"

/** Captured JPEG → [PhotoPipeline] → JPEG with the original EXIF → gallery. */
@Singleton
class PhotoProcessor @Inject constructor(
    private val pipeline: PhotoPipeline,
    private val gallery: PhotoGallery,
    private val dispatcherProvider: DispatcherProvider,
    private val logger: Logger,
) {
    suspend fun process(photo: CapturedPhoto, settings: ProcessingSettings): Uri =
        withContext(dispatcherProvider.default) {
            val start = SystemClock.elapsedRealtime()
            val processed = pipeline.process(photo, settings)
            val width = processed.bgr.cols()
            val height = processed.bgr.rows()
            val encoded = processed.bgr.use { encodeJpeg(it, settings.params.jpegQuality) }
            val jpeg = ExifTransplant.transplant(source = photo.jpeg, target = encoded)
            val uri = gallery.save(jpeg, width, height)
            val exif = processed.exif
            logger.info(TAG, "${width}x$height, EXIF rotation ${exif.rotationDegrees} (CameraX ${photo.rotationDegrees}), " +
                "EV100 ${exif.ev100?.let { "%.1f".format(it) }}, HDR ${"%.2f".format(processed.params.hdr)}, " +
                "${SystemClock.elapsedRealtime() - start} ms -> $uri")
            uri
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
