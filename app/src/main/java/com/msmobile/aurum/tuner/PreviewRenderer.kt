package com.msmobile.aurum.tuner

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.msmobile.aurum.capture.CaptureExif
import com.msmobile.aurum.capture.CapturedPhoto
import com.msmobile.aurum.capture.PhotoPipeline
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.use
import com.msmobile.aurum.util.DispatcherProvider
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** A capture ready to be rendered with different settings. */
class PreviewShot(
    /** The unprocessed shot, for hold to compare. */
    val original: ImageBitmap,
    /** The scene light from the capture's EXIF, or null when unknown. */
    val ev100: Double?,
    internal val photo: CapturedPhoto,
)

class RenderedPreview(val image: ImageBitmap, val millis: Long)

/**
 * Renders the processing tuner's preview through [PhotoPipeline], the same full resolution path a saved photo takes, so
 * the preview is the photo the current settings would save. Only the result is downscaled, for the screen.
 */
class PreviewRenderer @Inject constructor(
    private val pipeline: PhotoPipeline,
    private val dispatcherProvider: DispatcherProvider,
) {
    suspend fun prepare(photo: CapturedPhoto): PreviewShot = withContext(dispatcherProvider.default) {
        val original = pipeline.decode(photo).use { toDisplayBitmap(it) }
        PreviewShot(original = original.asImageBitmap(), ev100 = CaptureExif.read(photo).ev100, photo = photo)
    }

    /** [settings] are applied as a photo would be, including scaling `hdr` by the scene light when enabled. */
    suspend fun render(shot: PreviewShot, settings: ProcessingSettings): RenderedPreview =
        withContext(dispatcherProvider.default) {
            val start = SystemClock.elapsedRealtime()
            val image = pipeline.process(shot.photo, settings).bgr.use { toDisplayBitmap(it) }
            RenderedPreview(image = image.asImageBitmap(), millis = SystemClock.elapsedRealtime() - start)
        }

    /** [bgr] downscaled by area averaging, as a photo viewer would show it, so the grain doesn't alias. */
    private fun toDisplayBitmap(bgr: Mat): Bitmap {
        val scale = MAX_SIDE_PX.toDouble() / max(bgr.cols(), bgr.rows())
        val sized = if (scale >= 1) {
            bgr.clone()
        } else {
            val size = Size((bgr.cols() * scale).roundToInt().toDouble(), (bgr.rows() * scale).roundToInt().toDouble())
            Mat().also { Imgproc.resize(bgr, it, size, 0.0, 0.0, Imgproc.INTER_AREA) }
        }
        return sized.use { small ->
            Mat().use { rgba ->
                Imgproc.cvtColor(small, rgba, Imgproc.COLOR_BGR2RGBA)
                Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
            }
        }
    }

    private companion object {
        /** Long side of the displayed preview: enough for a phone screen and the 2x zoom. */
        const val MAX_SIDE_PX = 2048
    }
}
