package com.msmobile.aurum.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.android.tools.screenshot.PreviewTest
import com.msmobile.aurum.ui.theme.PreviewPhone

class CameraScreenScreenshotTest {

    @PreviewTest
    @PreviewPhone
    @Composable
    internal fun CameraScreenPreviewTest(
        @PreviewParameter(CameraPreviewConfigProvider::class) config: CameraPreviewConfig,
    ) {
        CameraScreenPreview(config)
    }
}
