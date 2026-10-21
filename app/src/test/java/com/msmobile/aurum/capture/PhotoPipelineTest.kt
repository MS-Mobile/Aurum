package com.msmobile.aurum.capture

import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.SceneAdaptiveHdr
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPipelineTest {

    @Test
    fun sceneParams_scalingOnBrightScene_scalesHdr() {
        // Arrange & Act
        val params = PhotoPipeline.sceneParams(ProcessingSettings(scaleHdrBySceneLight = true), ev100 = 14.0)

        // Assert
        assertEquals(EnhanceParams.IPHONE.hdr * SceneAdaptiveHdr.BRIGHT_SCALE, params.hdr, 1e-9)
    }

    @Test
    fun sceneParams_scalingOff_keepsParams() {
        // Arrange & Act
        val params = PhotoPipeline.sceneParams(ProcessingSettings(scaleHdrBySceneLight = false), ev100 = 14.0)

        // Assert
        assertEquals(EnhanceParams.IPHONE, params)
    }

    @Test
    fun sceneParams_unknownLight_keepsParams() {
        // Arrange & Act
        val params = PhotoPipeline.sceneParams(ProcessingSettings(scaleHdrBySceneLight = true), ev100 = null)

        // Assert
        assertEquals(EnhanceParams.IPHONE, params)
    }
}
