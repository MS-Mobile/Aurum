package com.msmobile.aurum.camera

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.msmobile.aurum.R
import com.msmobile.aurum.capture.CameraSession.FlashMode
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.util.StringResource

@VisibleForTesting
internal class CameraPreviewConfigProvider : PreviewParameterProvider<CameraPreviewConfig> {

    override val values: Sequence<CameraPreviewConfig> = sequenceOf(
        CameraPreviewConfig(
            configName = "Ready",
            uiState = readyState,
        ),
        CameraPreviewConfig(
            configName = "Processing One",
            uiState = readyState.copy(processingCount = 1, flashMode = FlashMode.AUTO, lastPhoto = previewPhoto),
        ),
        CameraPreviewConfig(
            configName = "Processing Several",
            uiState = readyState.copy(processingCount = 3, flashMode = FlashMode.ON, lastPhoto = previewPhoto),
        ),
        CameraPreviewConfig(
            configName = "Front Camera Without Flash",
            uiState = readyState.copy(lens = Lens.FRONT, hasFlashUnit = false),
        ),
        CameraPreviewConfig(
            configName = "Error",
            uiState = readyState.copy(
                eventState = CameraViewModel.UiEventState.Error(StringResource(R.string.error_processing)),
            ),
        ),
        CameraPreviewConfig(
            configName = "Permission Missing",
            uiState = CameraViewModel.UiState(hasCameraPermission = false),
        ),
        CameraPreviewConfig(
            configName = "Ready Light",
            uiState = readyState,
            darkTheme = false,
        ),
        CameraPreviewConfig(
            configName = "Processing Several Light",
            uiState = readyState.copy(processingCount = 3, flashMode = FlashMode.ON, lastPhoto = previewPhoto),
            darkTheme = false,
        ),
        CameraPreviewConfig(
            configName = "Permission Missing Light",
            uiState = CameraViewModel.UiState(hasCameraPermission = false),
            darkTheme = false,
        ),
    )

    override fun getDisplayName(index: Int): String = values.elementAt(index).configName

    private companion object {
        val previewPhoto: Uri = Uri.parse("content://media/external_primary/images/media/1")
        val readyState = CameraViewModel.UiState(
            hasCameraPermission = true,
            lens = Lens.BACK,
            canSwitchLens = true,
            flashMode = FlashMode.OFF,
            hasFlashUnit = true,
        )
    }
}

@VisibleForTesting
internal data class CameraPreviewConfig(
    val configName: String,
    val uiState: CameraViewModel.UiState,
    val darkTheme: Boolean = true,
)
