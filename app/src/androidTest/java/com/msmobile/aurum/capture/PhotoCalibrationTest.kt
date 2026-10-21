package com.msmobile.aurum.capture

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msmobile.aurum.processing.EnhanceParam
import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ProcessingSettings
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Processes photos exactly as the camera saves them ([PhotoProcessor.toJpeg]: [PhotoPipeline], JPEG encode, EXIF), so
 * a preset can be calibrated on the phone's real pipeline. Run it with `tools/run-calibration.sh`.
 *
 * Reads every `.jpg` in the app's `files/calibration/in` and writes the results to `files/calibration/out`. Skipped
 * when there are no photos. Params default to [EnhanceParams.IPHONE]; any of them can be overridden with an
 * instrumentation argument named after its [EnhanceParam.key] (`-e golden 0.8`), plus `-e scaleHdrBySceneLight false`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PhotoCalibrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var processor: PhotoProcessor

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun processCalibrationPhotos() = runBlocking {
        val dir = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("calibration"))
        val photos = File(dir, "in").listFiles { file -> file.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        assumeTrue("No photos in ${File(dir, "in")}", photos.isNotEmpty())
        val out = File(dir, "out").apply { deleteRecursively(); mkdirs() }

        val settings = settingsFromArguments()
        File(out, "settings.txt").writeText(describe(settings))
        report(describe(settings))
        for (file in photos) {
            val photo = CapturedPhoto(jpeg = file.readBytes(), rotationDegrees = 0)
            val start = SystemClock.elapsedRealtime()
            val result = processor.toJpeg(photo, settings)
            File(out, file.name).writeBytes(result.jpeg)
            report("${file.name}: ${result.describe(photo)}, ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    private fun settingsFromArguments(): ProcessingSettings {
        val arguments = InstrumentationRegistry.getArguments()
        val params = EnhanceParam.entries.fold(EnhanceParams.IPHONE) { params, param ->
            arguments.getString(param.key)?.let { param.set(params, it.toDouble()) } ?: params
        }
        val scaleHdr = arguments.getString(SCALE_HDR_ARGUMENT)?.toBooleanStrict() ?: true
        return ProcessingSettings(params = params, scaleHdrBySceneLight = scaleHdr)
    }

    private fun describe(settings: ProcessingSettings): String =
        EnhanceParam.entries.joinToString(", ") { "${it.key} ${it.get(settings.params)}" } +
            ", $SCALE_HDR_ARGUMENT ${settings.scaleHdrBySceneLight}"

    private fun report(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    private companion object {
        const val TAG = "PhotoCalibration"
        const val SCALE_HDR_ARGUMENT = "scaleHdrBySceneLight"
    }
}
