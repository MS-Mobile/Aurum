package com.msmobile.aurum.capture

import androidx.exifinterface.media.ExifInterface
import com.msmobile.aurum.processing.SceneAdaptiveHdr
import java.io.ByteArrayInputStream

/** What processing needs from a capture's EXIF. */
class CaptureExif(
    /** Clockwise rotation that makes the JPEG's pixels upright. */
    val rotationDegrees: Int,
    /** The scene light, or null when the EXIF lacks any of the values. */
    val ev100: Double?,
) {
    companion object {
        fun read(photo: CapturedPhoto): CaptureExif {
            val exif = ExifInterface(ByteArrayInputStream(photo.jpeg))
            val ev100 = SceneAdaptiveHdr.ev100(
                fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN).takeUnless { it.isNaN() },
                exposureSeconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN).takeUnless { it.isNaN() },
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.toDouble(),
            )
            // EXIF describes these bytes; fall back to CameraX when the HAL left it out. Mirroring flags are
            // ignored on purpose so selfies are saved as others see the scene, not mirrored.
            val rotation = if (exif.hasAttribute(ExifInterface.TAG_ORIENTATION) &&
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) != ExifInterface.ORIENTATION_UNDEFINED
            ) exif.rotationDegrees else photo.rotationDegrees
            return CaptureExif(rotationDegrees = rotation, ev100 = ev100)
        }
    }
}
