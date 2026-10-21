package com.msmobile.aurum.camera

import android.net.Uri
import androidx.camera.core.SurfaceRequest
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msmobile.aurum.R
import com.msmobile.aurum.capture.CameraSession
import com.msmobile.aurum.capture.CameraSession.FlashMode
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.capture.PhotoGallery
import com.msmobile.aurum.capture.PhotoProcessor
import com.msmobile.aurum.util.Logger
import com.msmobile.aurum.util.StringResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CameraViewModel"

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraSession: CameraSession,
    private val photoProcessor: PhotoProcessor,
    private val photoGallery: PhotoGallery,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    val surfaceRequest: StateFlow<SurfaceRequest?> = cameraSession.surfaceRequest

    fun onEvent(uiEvent: UiEvent) {
        when (uiEvent) {
            is UiEvent.RefreshLastPhoto -> onRefreshLastPhoto()
            is UiEvent.CameraPermissionChanged -> onCameraPermissionChanged(uiEvent.granted)
            is UiEvent.TakePhoto -> onTakePhoto()
            is UiEvent.SwitchLens -> onSwitchLens()
            is UiEvent.CycleFlash -> onCycleFlash()
            is UiEvent.FocusAt -> cameraSession.focus(uiEvent.x, uiEvent.y)
            is UiEvent.EventStateHandled -> _uiState.update { it.copy(eventState = UiEventState.Idle) }
        }
    }

    /** Keeps the camera bound to [lifecycleOwner] until cancelled, rebinding whenever the lens changes. */
    suspend fun bindToCamera(lifecycleOwner: LifecycleOwner) {
        val lenses = cameraSession.availableLenses()
        _uiState.update { state ->
            state.copy(
                lens = if (state.lens in lenses) state.lens else lenses.firstOrNull() ?: state.lens,
                canSwitchLens = lenses.size > 1,
            )
        }
        try {
            _uiState.map { it.lens }.distinctUntilChanged().collectLatest { lens ->
                val capabilities = cameraSession.bind(lifecycleOwner, lens)
                cameraSession.setFlashMode(_uiState.value.flashMode)
                _uiState.update { it.copy(hasFlashUnit = capabilities.hasFlashUnit) }
            }
        } finally {
            cameraSession.unbind()
        }
    }

    private fun onRefreshLastPhoto() {
        viewModelScope.launch {
            val latest = photoGallery.latestPhoto()
            _uiState.update { it.copy(lastPhoto = latest) }
        }
    }

    private fun onCameraPermissionChanged(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    private fun onTakePhoto() {
        viewModelScope.launch {
            val photo = try {
                cameraSession.takePicture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(TAG, "Capture failed", e)
                showError(R.string.error_capture)
                return@launch
            }
            _uiState.update { it.copy(processingCount = it.processingCount + 1) }
            try {
                val saved = photoProcessor.process(photo)
                _uiState.update { it.copy(lastPhoto = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { // includes OutOfMemoryError on very large photos
                logger.error(TAG, "Processing failed", e)
                showError(R.string.error_processing)
            } finally {
                _uiState.update { it.copy(processingCount = it.processingCount - 1) }
            }
        }
    }

    private fun onSwitchLens() {
        _uiState.update { state ->
            if (!state.canSwitchLens) return@update state
            state.copy(lens = if (state.lens == Lens.BACK) Lens.FRONT else Lens.BACK)
        }
    }

    private fun onCycleFlash() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        cameraSession.setFlashMode(next)
        _uiState.update { it.copy(flashMode = next) }
    }

    private fun showError(textResId: Int) {
        _uiState.update { it.copy(eventState = UiEventState.Error(StringResource(textResId))) }
    }

    sealed class UiEvent {
        /** The screen resumed or the last photo couldn't be opened: the gallery may have changed meanwhile. */
        data object RefreshLastPhoto : UiEvent()
        data class CameraPermissionChanged(val granted: Boolean) : UiEvent()
        data object TakePhoto : UiEvent()
        data object SwitchLens : UiEvent()
        data object CycleFlash : UiEvent()

        /** A tap on the viewfinder, in the coordinates of the camera surface. */
        data class FocusAt(val x: Float, val y: Float) : UiEvent()
        data object EventStateHandled : UiEvent()
    }

    sealed class UiEventState {
        data object Idle : UiEventState()
        data class Error(val message: StringResource) : UiEventState()
    }

    data class UiState(
        val hasCameraPermission: Boolean = false,
        val lens: Lens = Lens.BACK,
        val canSwitchLens: Boolean = false,
        val flashMode: FlashMode = FlashMode.OFF,
        val hasFlashUnit: Boolean = false,
        val processingCount: Int = 0,
        val lastPhoto: Uri? = null,
        val eventState: UiEventState = UiEventState.Idle,
    )
}
