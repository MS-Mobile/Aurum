package com.msmobile.aurum.capture

import android.content.Context
import android.graphics.ImageFormat
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX wrapper: preview, full resolution JPEG capture, flash and tap to focus. A singleton, so the processing
 * tuner can take its preview shot with the camera the camera screen bound.
 */
@Singleton
class CameraSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest

    private val _lens = MutableStateFlow(Lens.BACK)

    /** The lens to use. The camera screen binds it; the processing tuner can change it too. */
    val lens: StateFlow<Lens> = _lens

    private val _boundLens = MutableStateFlow<Lens?>(null)

    /** The lens currently bound, or null while (re)binding or unbound. */
    val boundLens: StateFlow<Lens?> = _boundLens

    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var meteringPointFactory: SurfaceOrientedMeteringPointFactory? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null

    private val preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
        )
        .build()
        .apply {
            setSurfaceProvider { request ->
                _surfaceRequest.value = request
                meteringPointFactory = SurfaceOrientedMeteringPointFactory(
                    request.resolution.width.toFloat(),
                    request.resolution.height.toFloat(),
                )
            }
        }

    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
        )
        .build()

    /** The UI is portrait only; this keeps landscape photos upright. */
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            imageCapture.targetRotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }

    suspend fun availableLenses(): Set<Lens> {
        val provider = provider()
        return Lens.entries.filterTo(mutableSetOf()) { provider.hasCamera(it.selector) }
    }

    /** Binds preview and capture to [lifecycleOwner] using [lens], replacing any previous binding. */
    suspend fun bind(lifecycleOwner: LifecycleOwner, lens: Lens): CameraCapabilities {
        val provider = provider()
        _boundLens.value = null
        provider.unbindAll()
        val bound = provider.bindToLifecycle(lifecycleOwner, lens.selector, preview, imageCapture)
        camera = bound
        _boundLens.value = lens
        orientationListener.enable()
        return CameraCapabilities(hasFlashUnit = bound.cameraInfo.hasFlashUnit())
    }

    fun unbind() {
        orientationListener.disable()
        provider?.unbindAll()
        camera = null
        _boundLens.value = null
        _surfaceRequest.value = null
    }

    fun selectLens(lens: Lens) {
        _lens.value = lens
    }

    fun setFlashMode(flashMode: FlashMode) {
        imageCapture.flashMode = flashMode.cameraXMode
    }

    /** Focuses and meters on a point in the coordinates of the current [surfaceRequest]. */
    fun focus(x: Float, y: Float) {
        val point = meteringPointFactory?.createPoint(x, y) ?: return
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /** Captures a JPEG in memory. Returns as soon as the camera has the frame, so the next shot can follow. */
    suspend fun takePicture(): CapturedPhoto = suspendCancellableCoroutine { continuation ->
        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val photo = image.use {
                    check(it.format == ImageFormat.JPEG) { "Unexpected capture format ${it.format}" }
                    val buffer = it.planes[0].buffer
                    CapturedPhoto(
                        jpeg = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) },
                        rotationDegrees = it.imageInfo.rotationDegrees,
                    )
                }
                continuation.resume(photo)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        })
    }

    private suspend fun provider(): ProcessCameraProvider =
        provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }

    enum class Lens(val selector: CameraSelector) {
        BACK(CameraSelector.DEFAULT_BACK_CAMERA),
        FRONT(CameraSelector.DEFAULT_FRONT_CAMERA),
        ;

        fun other(): Lens = if (this == BACK) FRONT else BACK
    }

    enum class FlashMode(val cameraXMode: Int) {
        OFF(ImageCapture.FLASH_MODE_OFF),
        AUTO(ImageCapture.FLASH_MODE_AUTO),
        ON(ImageCapture.FLASH_MODE_ON),
    }

    data class CameraCapabilities(val hasFlashUnit: Boolean)
}

class CapturedPhoto(val jpeg: ByteArray, val rotationDegrees: Int)
