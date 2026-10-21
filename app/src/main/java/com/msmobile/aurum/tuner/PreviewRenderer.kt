package com.msmobile.aurum.tuner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.msmobile.aurum.capture.CaptureExif
import com.msmobile.aurum.capture.CapturedPhoto
import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.PhotoEnhancer
import com.msmobile.aurum.util.DispatcherProvider
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max

/** A capture decoded upright at preview size, ready to be rendered with different params. */
class PreviewShot(
    /** The unprocessed shot, for hold to compare. */
    val original: ImageBitmap,
    /** The scene light from the capture's EXIF, or null when unknown. */
    val ev100: Double?,
    internal val source: Bitmap,
)

class RenderedPreview(val image: ImageBitmap, val millis: Long)

/**
 * Renders the processing tuner's preview with the real pipeline, on a downscaled copy of the capture so a render
 * takes about a second instead of several.
 */
class PreviewRenderer @Inject constructor(
    private val enhancer: PhotoEnhancer,
    private val dispatcherProvider: DispatcherProvider,
) {
    suspend fun prepare(photo: CapturedPhoto): PreviewShot = withContext(dispatcherProvider.default) {
        val exif = CaptureExif.read(photo)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, bounds)
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_SIDE_PX) sampleSize *= 2
        val decoded = checkNotNull(
            BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        ) { "Could not decode the preview shot" }
        val upright = if (exif.rotationDegrees == 0) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(exif.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }
        }
        PreviewShot(original = upright.asImageBitmap(), ev100 = exif.ev100, source = upright)
    }

    /** [params] are applied as given: the caller scales `hdr` by the scene light when it should. */
    suspend fun render(shot: PreviewShot, params: EnhanceParams): RenderedPreview = withContext(dispatcherProvider.default) {
        val start = SystemClock.elapsedRealtime()
        val enhanced = enhancer.enhance(shot.source, params)
        RenderedPreview(image = enhanced.asImageBitmap(), millis = SystemClock.elapsedRealtime() - start)
    }

    private companion object {
        /** Long side of the preview: enough for a phone screen, and 1:1 still shows the grain. */
        const val MAX_SIDE_PX = 1600
    }
}
