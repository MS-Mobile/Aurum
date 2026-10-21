package com.msmobile.aurum.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import javax.inject.Inject

/**
 * Validates the Kotlin port against `corrigir_foto.py --iphone --grao 0` (see `tools/generate_references.py`).
 * Grain is disabled on both sides so the comparison is deterministic.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PhotoEnhancerReferenceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var enhancer: PhotoEnhancer

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun iphonePresetMatchesPythonReference() {
        val input = loadAsset("reference/input.png")
        val expected = loadAsset("reference/expected_iphone_no_grain.png")
        val params = EnhanceParams.IPHONE.copy(grain = 0.0)

        enhancer.enhance(input, params).recycle() // warm-up: JIT and OpenCV initialisation
        val start = SystemClock.elapsedRealtime()
        val actual = enhancer.enhance(input, params)
        val elapsedMs = SystemClock.elapsedRealtime() - start

        val actualRgb = actual.toRgbMat()
        val expectedRgb = expected.toRgbMat()
        val psnr = Core.PSNR(actualRgb, expectedRgb)
        val diff = Mat().also { Core.absdiff(actualRgb, expectedRgb, it) }
        val maxDiff = Core.minMaxLoc(diff.reshape(1)).maxVal
        val differing = Core.countNonZero(diff.reshape(1)).toDouble() / (diff.total() * diff.channels())
        saveForInspection(actualRgb)

        val report = "PSNR %.2f dB, max diff %.0f, %.3f%% of samples differ, %d ms for %dx%d"
            .format(psnr, maxDiff, differing * 100, elapsedMs, input.width, input.height)
        Log.i(TAG, report)
        println(report)
        assertTrue("Port deviates from the Python reference: $report", psnr > MIN_PSNR_DB)
    }

    private fun loadAsset(path: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode $path")
    }

    private fun Bitmap.toRgbMat(): Mat {
        val rgba = Mat().also { Utils.bitmapToMat(this, it) }
        return Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2RGB); rgba.release() }
    }

    /** Writes the Kotlin output to the app's external files dir so it can be pulled with adb. */
    private fun saveForInspection(rgb: Mat) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
        val bgr = Mat().also { Imgproc.cvtColor(rgb, it, Imgproc.COLOR_RGB2BGR) }
        Imgcodecs.imwrite(File(dir, "kotlin_iphone_no_grain.png").absolutePath, bgr)
        bgr.release()
    }

    private companion object {
        const val TAG = "PhotoEnhancerReference"
        const val MIN_PSNR_DB = 40.0
    }
}
