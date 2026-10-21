package com.msmobile.aurum.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceParamTest {

    @Test
    fun set_valueBetweenSteps_snapsToNearestStep() {
        // Arrange & Act
        val params = EnhanceParam.WARMTH.set(EnhanceParams.IPHONE, 0.0237)

        // Assert
        assertEquals(0.025, params.warmth, 0.0)
    }

    @Test
    fun set_valueOutsideRange_clampsToRange() {
        // Arrange & Act
        val params = EnhanceParam.GRAIN_SIZE.set(EnhanceParams.IPHONE, 0.1)

        // Assert
        assertEquals(0.3, params.grainSize, 0.0)
    }

    @Test
    fun set_presetValue_isNotModified() {
        // Arrange & Act
        val params = EnhanceParam.WHITE_BALANCE.set(EnhanceParams.IPHONE, EnhanceParams.IPHONE.whiteBalance)

        // Assert
        assertFalse(EnhanceParam.WHITE_BALANCE.isModified(params))
    }

    @Test
    fun isModified_otherValue_isModified() {
        // Arrange & Act
        val params = EnhanceParam.HDR.set(EnhanceParams.IPHONE, 0.8)

        // Assert
        assertTrue(EnhanceParam.HDR.isModified(params))
    }

    @Test
    fun key_multiWordName_isTheEnhanceParamsPropertyName() {
        // Assert
        assertEquals("claheWeight", EnhanceParam.CLAHE_WEIGHT.key)
        assertEquals("grainSize", EnhanceParam.GRAIN_SIZE.key)
    }
}
