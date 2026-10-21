package com.msmobile.aurum.capture

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.PhotoEnhancer
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.SceneAdaptiveHdr
import com.msmobile.aurum.processing.use
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captured JPEG → upright BGR (as `cv2.imread` would decode it) → [PhotoEnhancer] at full resolution.
 *
 * The one path for both saved photos ([PhotoProcessor]) and the processing tuner's preview, so the preview shows exactly
 * what a photo taken with the same settings will look like. Photos are processed one at a time, previews included,
 * since a full resolution photo needs several hundred MB while being processed.
 */
@Singleton
class PhotoPipeline @Inject constructor(
    private val enhancer: PhotoEnhancer,
) {
    private val mutex = Mutex()

    /** [photo] decoded upright, without processing. The caller owns the returned Mat. */
    fun decode(photo: CapturedPhoto): Mat = decodeUpright(photo.jpeg, CaptureExif.read(photo).rotationDegrees)

    /** [photo] processed with [settings]. The caller owns [ProcessedPhoto.bgr]. */
    suspend fun process(photo: CapturedPhoto, settings: ProcessingSettings): ProcessedPhoto = mutex.withLock {
        val exif = CaptureExif.read(photo)
        val params = sceneParams(settings, exif.ev100)
        val enhanced = decodeUpright(photo.jpeg, exif.rotationDegrees).use { enhancer.enhance(it, params) }
        ProcessedPhoto(bgr = enhanced, exif = exif, params = params)
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

    companion object {
        /** The params a photo of a scene at [ev100] is processed with: `hdr` scaled by the light when enabled. */
        fun sceneParams(settings: ProcessingSettings, ev100: Double?): EnhanceParams =
            if (settings.scaleHdrBySceneLight) SceneAdaptiveHdr.adapt(settings.params, ev100) else settings.params
    }
}

/** A processed photo: the upright BGR result, the capture's EXIF and the params it was processed with. */
class ProcessedPhoto(val bgr: Mat, val exif: CaptureExif, val params: EnhanceParams)
