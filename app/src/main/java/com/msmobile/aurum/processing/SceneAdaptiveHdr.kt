package com.msmobile.aurum.processing

import kotlin.math.ln

/**
 * Scales the HDR strength with the scene light: the validated `--iphone` strength in dim scenes, less in good
 * lighting, where local tone mapping has little to recover and mostly flattens the photo.
 *
 * Light is measured as EV100 from the capture's EXIF (aperture, exposure time and ISO).
 */
object SceneAdaptiveHdr {
    /** At or below this EV100 (dim indoor) the HDR strength is unchanged. */
    const val DIM_EV100 = 7.0

    /** At or above this EV100 (bright indoor, overcast or sunny outdoor) HDR is at [BRIGHT_SCALE] of its strength. */
    const val BRIGHT_EV100 = 11.0

    const val BRIGHT_SCALE = 0.4

    /** Exposure value normalised to ISO 100, or null when the EXIF lacks any of the values. */
    fun ev100(fNumber: Double?, exposureSeconds: Double?, iso: Double?): Double? {
        if (fNumber == null || exposureSeconds == null || iso == null) return null
        if (fNumber <= 0 || exposureSeconds <= 0 || iso <= 0) return null
        return log2(fNumber * fNumber / exposureSeconds) - log2(iso / 100)
    }

    /** [params] with the HDR strength for a scene at [ev100]; unchanged when the light is unknown. */
    fun adapt(params: EnhanceParams, ev100: Double?): EnhanceParams {
        if (ev100 == null) return params
        val brightness = ((ev100 - DIM_EV100) / (BRIGHT_EV100 - DIM_EV100)).coerceIn(0.0, 1.0)
        val scale = 1 - (1 - BRIGHT_SCALE) * brightness
        return params.copy(hdr = params.hdr * scale)
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
