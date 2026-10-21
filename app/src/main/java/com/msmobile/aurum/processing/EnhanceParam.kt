package com.msmobile.aurum.processing

/**
 * The tunable [EnhanceParams] fields, with the ranges from the script's help text (see `docs/processing.md`).
 * `jpegQuality` is left out: it doesn't change how the photo looks.
 */
enum class EnhanceParam(
    val min: Double,
    val max: Double,
    val step: Double,
    private val read: (EnhanceParams) -> Double,
    private val write: (EnhanceParams, Double) -> EnhanceParams,
) {
    WHITE_BALANCE(0.0, 1.0, 0.01, { it.whiteBalance }, { p, v -> p.copy(whiteBalance = v) }),
    WARMTH(-0.05, 0.05, 0.005, { it.warmth }, { p, v -> p.copy(warmth = v) }),
    VIBRANCE(0.0, 0.5, 0.01, { it.vibrance }, { p, v -> p.copy(vibrance = v) }),
    GOLDEN(0.0, 1.0, 0.01, { it.golden }, { p, v -> p.copy(golden = v) }),
    EXPOSURE(-0.2, 0.2, 0.01, { it.exposure }, { p, v -> p.copy(exposure = v) }),
    CLAHE_WEIGHT(0.0, 1.0, 0.01, { it.claheWeight }, { p, v -> p.copy(claheWeight = v) }),
    CURVE(0.0, 0.4, 0.01, { it.curve }, { p, v -> p.copy(curve = v) }),
    HDR(0.0, 1.0, 0.01, { it.hdr }, { p, v -> p.copy(hdr = v) }),
    TEXTURE(0.0, 2.0, 0.05, { it.texture }, { p, v -> p.copy(texture = v) }),
    GRAIN(0.0, 5.0, 0.1, { it.grain }, { p, v -> p.copy(grain = v) }),
    GRAIN_SIZE(0.3, 1.0, 0.05, { it.grainSize }, { p, v -> p.copy(grainSize = v) }),
    ;

    /** The `EnhanceParams` property name, e.g. `claheWeight`. */
    val key: String = name.lowercase().split('_').let { parts ->
        parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }

    fun get(params: EnhanceParams): Double = read(params)

    /** [params] with this field set to [value], clamped to the range and snapped to the step. */
    fun set(params: EnhanceParams, value: Double): EnhanceParams = write(params, snap(value))

    fun isModified(params: EnhanceParams, preset: EnhanceParams = EnhanceParams.IPHONE): Boolean =
        kotlin.math.abs(get(params) - get(preset)) > 1e-9

    private fun snap(value: Double): Double {
        val steps = Math.round((value.coerceIn(min, max) - min) / step)
        // Rounded to 4 decimals so 0.35 stays 0.35 rather than 0.35000000000000003.
        return Math.round((min + steps * step) * 10_000) / 10_000.0
    }
}
