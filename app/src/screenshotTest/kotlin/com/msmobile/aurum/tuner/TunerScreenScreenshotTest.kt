package com.msmobile.aurum.tuner

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.android.tools.screenshot.PreviewTest
import com.msmobile.aurum.ui.theme.PreviewPhone

class TunerScreenScreenshotTest {

    @PreviewTest
    @PreviewPhone
    @Composable
    internal fun TunerScreenPreviewTest(
        @PreviewParameter(TunerPreviewConfigProvider::class) config: TunerPreviewConfig,
    ) {
        TunerScreenPreview(config)
    }
}
