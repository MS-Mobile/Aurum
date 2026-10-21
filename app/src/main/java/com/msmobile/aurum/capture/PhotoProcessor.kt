package com.msmobile.aurum.capture

import android.net.Uri
import android.os.SystemClock
import com.msmobile.aurum.processing.EnhanceParams
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
            val result = toJpeg(photo, settings)
            val uri = gallery.save(result.jpeg)
            logger.info(TAG, "${result.describe(photo)}, ${SystemClock.elapsedRealtime() - start} ms -> $uri")
            uri
        }

    /**
     * The exact JPEG a photo is saved as: processed, encoded, with the capture's EXIF marked upright. Doesn't touch
     * the gallery.
     */
    suspend fun toJpeg(photo: CapturedPhoto, settings: ProcessingSettings): ProcessedJpeg =
        withContext(dispatcherProvider.default) {
            val processed = pipeline.process(photo, settings)
            val width = processed.bgr.cols()
            val height = processed.bgr.rows()
            val encoded = processed.bgr.use { encodeJpeg(it, settings.params.jpegQuality) }
            val jpeg = gallery.markUpright(ExifTransplant.transplant(source = photo.jpeg, target = encoded), width, height)
            ProcessedJpeg(jpeg = jpeg, width = width, height = height, exif = processed.exif, params = processed.params)
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

/** A processed photo as saved: the JPEG, its size, the capture's EXIF and the params it was processed with. */
class ProcessedJpeg(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val exif: CaptureExif,
    val params: EnhanceParams,
) {
    fun describe(photo: CapturedPhoto): String =
        "${width}x$height, EXIF rotation ${exif.rotationDegrees} (CameraX ${photo.rotationDegrees}), " +
            "EV100 ${exif.ev100?.let { "%.1f".format(it) }}, HDR ${"%.2f".format(params.hdr)}"
}
