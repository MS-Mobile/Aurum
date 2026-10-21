package com.msmobile.aurum.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneAdaptiveHdrTest {

    @Test
    fun ev100_indoorCapture_matchesExposureFormula() {
        // Arrange & Act: f/2.2, 1/100 s, ISO 513 (an indoor photo from the phone)
        val ev = SceneAdaptiveHdr.ev100(fNumber = 2.2, exposureSeconds = 0.01, iso = 513.0)

        // Assert
        assertEquals(6.56, requireNotNull(ev), 0.01)
    }

    @Test
    fun ev100_missingExif_returnsNull() {
        // Arrange & Act
        val ev = SceneAdaptiveHdr.ev100(fNumber = 2.2, exposureSeconds = null, iso = 100.0)

        // Assert
        assertNull(ev)
    }

    @Test
    fun adapt_dimScene_keepsPresetStrength() {
        // Arrange & Act
        val params = SceneAdaptiveHdr.adapt(EnhanceParams.IPHONE, ev100 = 5.0)

        // Assert
        assertEquals(0.6, params.hdr, 1e-9)
    }

    @Test
    fun adapt_brightScene_reducesStrength() {
        // Arrange & Act
        val params = SceneAdaptiveHdr.adapt(EnhanceParams.IPHONE, ev100 = 14.0)

        // Assert
        assertEquals(0.6 * SceneAdaptiveHdr.BRIGHT_SCALE, params.hdr, 1e-9)
    }

    @Test
    fun adapt_inBetween_rampsLinearly() {
        // Arrange & Act: halfway between the dim and bright thresholds
        val params = SceneAdaptiveHdr.adapt(EnhanceParams.IPHONE, ev100 = 9.0)

        // Assert
        assertEquals(0.6 * (1 + SceneAdaptiveHdr.BRIGHT_SCALE) / 2, params.hdr, 1e-9)
    }

    @Test
    fun adapt_unknownLight_keepsParams() {
        // Arrange & Act
        val params = SceneAdaptiveHdr.adapt(EnhanceParams.IPHONE, ev100 = null)

        // Assert
        assertEquals(EnhanceParams.IPHONE, params)
    }

    @Test
    fun adapt_brightScene_changesOnlyHdr() {
        // Arrange & Act
        val params = SceneAdaptiveHdr.adapt(EnhanceParams.IPHONE, ev100 = 14.0)

        // Assert
        assertEquals(EnhanceParams.IPHONE, params.copy(hdr = EnhanceParams.IPHONE.hdr))
    }
}
