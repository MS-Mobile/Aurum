package com.msmobile.aurum.processing.steps

import com.msmobile.aurum.processing.toFloatArray
import com.msmobile.aurum.processing.use
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * `skin_mask(img)`, a soft 0-1 skin mask from the 8-bit image:
 * ```
 * ycc = cv2.cvtColor(to_u8(img), cv2.COLOR_BGR2YCrCb)
 * mask = cv2.inRange(ycc, (40, 135, 85), (255, 175, 130)).astype(np.float32) / 255
 * return cv2.GaussianBlur(mask, (0, 0), 6)
 * ```
 */
internal fun skinMask(u8: Mat): FloatArray = Mat().use { ycc ->
    Imgproc.cvtColor(u8, ycc, Imgproc.COLOR_BGR2YCrCb)
    Mat().use { inRange ->
        Core.inRange(ycc, Scalar(40.0, 135.0, 85.0), Scalar(255.0, 175.0, 130.0), inRange)
        Mat().use { mask ->
            inRange.convertTo(mask, CvType.CV_32F, 1.0 / 255)
            Mat().use { Imgproc.GaussianBlur(mask, it, Size(0.0, 0.0), 6.0); it.toFloatArray() }
        }
    }
}
