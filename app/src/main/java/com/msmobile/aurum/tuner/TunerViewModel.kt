package com.msmobile.aurum.tuner

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msmobile.aurum.R
import com.msmobile.aurum.capture.CameraSession
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.processing.EnhanceParam
import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.ProcessingSettingsStore
import com.msmobile.aurum.processing.SceneAdaptiveHdr
import com.msmobile.aurum.util.Logger
import com.msmobile.aurum.util.StringResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "TunerViewModel"
private const val BIND_TIMEOUT_MS = 5_000L

/**
 * Edits a draft of the [ProcessingSettings] on a preview shot. Nothing is saved until [UiEvent.Done], and photos
 * taken meanwhile keep using the saved settings.
 */
@HiltViewModel
class TunerViewModel @Inject constructor(
    private val cameraSession: CameraSession,
    private val previewRenderer: PreviewRenderer,
    private val processingSettingsStore: ProcessingSettingsStore,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** The latest render wanted. A StateFlow conflates, so slider drags render the newest value, never a backlog. */
    private val renderRequests = MutableStateFlow<RenderRequest?>(null)
    private var captureJob: Job? = null

    init {
        viewModelScope.launch {
            renderRequests.filterNotNull().collect { render(it) }
        }
    }

    fun onEvent(uiEvent: UiEvent) {
        when (uiEvent) {
            is UiEvent.Opened -> onOpened()
            is UiEvent.TakePreviewShot -> onTakePreviewShot()
            is UiEvent.SwitchLens -> onSwitchLens()
            is UiEvent.ParamChanged -> updateDraft { it.copy(params = uiEvent.param.set(it.params, uiEvent.value)) }
            is UiEvent.ScaleHdrBySceneLightToggled -> updateDraft { it.copy(scaleHdrBySceneLight = !it.scaleHdrBySceneLight) }
            is UiEvent.ResetToPreset -> updateDraft { it.copy(params = EnhanceParams.IPHONE) }
            is UiEvent.TabSelected -> _uiState.update { it.copy(tab = uiEvent.tab) }
            is UiEvent.ComparingChanged -> _uiState.update { it.copy(isComparing = uiEvent.comparing) }
            is UiEvent.ZoomToggled -> _uiState.update { it.copy(isZoomed = !it.isZoomed) }
            is UiEvent.Done -> processingSettingsStore.save(_uiState.value.draft)
            is UiEvent.Closed -> onClosed()
        }
    }

    /** Starts over from the saved settings with a fresh shot, since this ViewModel outlives a closed tuner. */
    private fun onOpened() {
        _uiState.value = UiState(draft = processingSettingsStore.settings.value, lens = cameraSession.lens.value)
        viewModelScope.launch {
            val canSwitch = cameraSession.availableLenses().size > 1
            _uiState.update { it.copy(canSwitchLens = canSwitch) }
        }
        onTakePreviewShot()
    }

    /** Switches the camera for the camera screen too, and shoots again once it is bound. */
    private fun onSwitchLens() {
        if (!_uiState.value.canSwitchLens) return
        val lens = cameraSession.lens.value.other()
        cameraSession.selectLens(lens)
        _uiState.update { it.copy(lens = lens) }
        onTakePreviewShot()
    }

    /** Frees the preview: this ViewModel lives as long as the activity, the tuner doesn't. */
    private fun onClosed() {
        captureJob?.cancel()
        discardPreview()
        _uiState.update { it.copy(isCapturing = false, isRendering = false, isComparing = false, isZoomed = false) }
    }

    /**
     * Drops every reference to the current shot and its render, so they can be garbage collected before the next
     * shot is decoded. Shots only ever live in memory: the capture and the decode never touch the disk.
     */
    private fun discardPreview() {
        renderRequests.value = null
        _uiState.update { it.copy(shot = null, ev100 = null, rendered = null, isRendering = false) }
    }

    private fun onTakePreviewShot() {
        captureJob?.cancel()
        discardPreview()
        captureJob = viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, error = null) }
            val lens = cameraSession.lens.value
            val shot = try {
                // The camera screen rebinds after a lens switch: wait for it rather than shoot with the old lens.
                checkNotNull(withTimeoutOrNull(BIND_TIMEOUT_MS) { cameraSession.boundLens.first { it == lens } }) {
                    "$lens camera not bound"
                }
                previewRenderer.prepare(cameraSession.takePicture())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { // includes OutOfMemoryError
                logger.error(TAG, "Preview shot failed", e)
                _uiState.update { it.copy(isCapturing = false, error = StringResource(R.string.error_preview_capture)) }
                return@launch
            }
            _uiState.update { it.copy(shot = shot, ev100 = shot.ev100, isCapturing = false) }
            requestRender()
        }
    }

    private fun updateDraft(transform: (ProcessingSettings) -> ProcessingSettings) {
        _uiState.update { it.copy(draft = transform(it.draft)) }
        requestRender()
    }

    private fun requestRender() {
        val state = _uiState.value
        val shot = state.shot ?: return
        val request = RenderRequest(shot, state.draft)
        if (request == renderRequests.value && state.rendered != null) return
        _uiState.update { it.copy(isRendering = true) }
        renderRequests.value = request
    }

    private suspend fun render(request: RenderRequest) {
        val rendered = try {
            previewRenderer.render(request.shot, request.settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error(TAG, "Preview render failed", e)
            _uiState.update { it.copy(isRendering = false, error = StringResource(R.string.error_preview_render)) }
            return
        }
        // A newer shot replaces this result; newer params show it until their own render is done.
        _uiState.update { state ->
            if (state.shot !== request.shot) return@update state
            state.copy(
                rendered = rendered.image,
                renderMillis = rendered.millis,
                isRendering = renderRequests.value != request,
                error = null,
            )
        }
    }

    private data class RenderRequest(val shot: PreviewShot, val settings: ProcessingSettings)

    enum class Tab(val params: List<EnhanceParam>) {
        COLOUR(listOf(EnhanceParam.WHITE_BALANCE, EnhanceParam.WARMTH, EnhanceParam.VIBRANCE, EnhanceParam.GOLDEN)),
        LIGHT(listOf(EnhanceParam.EXPOSURE, EnhanceParam.CLAHE_WEIGHT, EnhanceParam.CURVE, EnhanceParam.HDR)),
        DETAIL(listOf(EnhanceParam.MATTE, EnhanceParam.TEXTURE, EnhanceParam.GRAIN, EnhanceParam.GRAIN_SIZE)),
    }

    sealed class UiEvent {
        /** The tuner was shown: load the saved settings and take a preview shot. */
        data object Opened : UiEvent()
        data object TakePreviewShot : UiEvent()
        data class ParamChanged(val param: EnhanceParam, val value: Double) : UiEvent()
        data object ScaleHdrBySceneLightToggled : UiEvent()
        data object ResetToPreset : UiEvent()
        data class TabSelected(val tab: Tab) : UiEvent()
        data class ComparingChanged(val comparing: Boolean) : UiEvent()
        data object ZoomToggled : UiEvent()

        /** Switches between the back and front camera and takes a new preview shot. */
        data object SwitchLens : UiEvent()

        /** Saves the draft for the next photos. */
        data object Done : UiEvent()

        /** The tuner was hidden, saved or not. */
        data object Closed : UiEvent()
    }

    data class UiState(
        val draft: ProcessingSettings = ProcessingSettings(),
        val tab: Tab = Tab.COLOUR,
        /** The camera the preview shot is taken with. */
        val lens: Lens = Lens.BACK,
        val canSwitchLens: Boolean = false,
        val shot: PreviewShot? = null,
        /** The preview shot's scene light, or null when unknown. */
        val ev100: Double? = null,
        val rendered: ImageBitmap? = null,
        val renderMillis: Long = 0,
        val isCapturing: Boolean = false,
        val isRendering: Boolean = false,
        val isComparing: Boolean = false,
        val isZoomed: Boolean = false,
        val error: StringResource? = null,
    ) {
        /** The factor `hdr` is scaled by for this shot, or null when it isn't scaled. */
        val hdrScale: Double?
            get() = ev100?.takeIf { draft.scaleHdrBySceneLight }?.let(SceneAdaptiveHdr::scale)

        fun modifiedCount(tab: Tab): Int = tab.params.count { it.isModified(draft.params) }
    }
}
