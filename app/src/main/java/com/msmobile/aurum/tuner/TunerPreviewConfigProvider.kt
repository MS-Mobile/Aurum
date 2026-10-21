package com.msmobile.aurum.tuner

import androidx.annotation.VisibleForTesting
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.msmobile.aurum.R
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.tuner.TunerViewModel.Tab
import com.msmobile.aurum.util.StringResource

@VisibleForTesting
internal class TunerPreviewConfigProvider : PreviewParameterProvider<TunerPreviewConfig> {

    override val values: Sequence<TunerPreviewConfig> = sequenceOf(
        TunerPreviewConfig(
            configName = "Colour Light",
            uiState = readyState,
            darkTheme = false,
        ),
        TunerPreviewConfig(
            configName = "Colour Dark",
            uiState = readyState,
            darkTheme = true,
        ),
        TunerPreviewConfig(
            configName = "Light Modified Rendering",
            uiState = readyState.copy(
                tab = Tab.LIGHT,
                draft = ProcessingSettings(params = EnhanceParams.IPHONE.copy(exposure = 0.05, hdr = 0.8)),
                isRendering = true,
            ),
            darkTheme = false,
        ),
        TunerPreviewConfig(
            configName = "Light Unknown Scene Dark",
            uiState = readyState.copy(tab = Tab.LIGHT, ev100 = null),
            darkTheme = true,
        ),
        TunerPreviewConfig(
            configName = "Detail Zoomed Comparing",
            uiState = readyState.copy(tab = Tab.DETAIL, isZoomed = true, isComparing = true),
            darkTheme = false,
        ),
        TunerPreviewConfig(
            configName = "Front Camera",
            uiState = readyState.copy(lens = Lens.FRONT),
            darkTheme = false,
        ),
        TunerPreviewConfig(
            configName = "Capturing",
            uiState = TunerViewModel.UiState(isCapturing = true),
            darkTheme = true,
        ),
        TunerPreviewConfig(
            configName = "Error",
            uiState = readyState.copy(error = StringResource(R.string.error_preview_capture)),
            darkTheme = false,
        ),
    )

    override fun getDisplayName(index: Int): String = values.elementAt(index).configName

    private companion object {
        val readyState = TunerViewModel.UiState(
            draft = ProcessingSettings(params = EnhanceParams.IPHONE.copy(warmth = 0.03)),
            ev100 = 9.0,
            renderMillis = 840,
            canSwitchLens = true,
        )
    }
}

@VisibleForTesting
internal data class TunerPreviewConfig(
    val configName: String,
    val uiState: TunerViewModel.UiState,
    val darkTheme: Boolean,
)
