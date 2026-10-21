package com.msmobile.aurum.processing

/**
 * Parameters of `corrigir_foto.py`. The defaults are the validated `--iphone` preset, i.e. the script defaults
 * with `--wb 0.25 --calor 0.045 --clahe 0.35 --curva 0.2 --vibrance 0.28 --hdr 0.85 --dourado 1 --grao 0.8
 * --grao-tamanho 0.3`: a warm golden look with open shadows, punchy contrast and light grain.
 *
 * Values are doubles because the script receives them as Python floats; each step narrows them to float32 at
 * the same point NumPy does.
 */
data class EnhanceParams(
    /** `--wb`: white balance strength (0-1). */
    val whiteBalance: Double = 0.25,
    /** `--calor`: colour warming (-0.05 to 0.05). */
    val warmth: Double = 0.045,
    /** `--brilho`: exposure adjustment (-0.2 to 0.2). */
    val exposure: Double = 0.0,
    /** `--clahe`: weight of the local contrast (0-1). */
    val claheWeight: Double = 0.35,
    /** `--curva`: S-curve strength (0-0.4). */
    val curve: Double = 0.2,
    /** `--vibrance`: vibrance (0-0.5). */
    val vibrance: Double = 0.28,
    /** `--textura`: texture enhancement multiplier (0-2). */
    val texture: Double = 1.0,
    /** `--grao`: standard deviation of the skin grain (0-5). 0 disables it. */
    val grain: Double = 0.8,
    /** `--grao-tamanho`: grain size (0.3 fine to 1.0 coarse). */
    val grainSize: Double = 0.3,
    /** `--hdr`: Smart HDR style local tone mapping (0-1). */
    val hdr: Double = 0.85,
    /** `--dourado`: golden toning (0-1). */
    val golden: Double = 1.0,
    /** `--qualidade`: JPEG quality of the saved photo. */
    val jpegQuality: Int = 95,
) {
    companion object {
        val IPHONE = EnhanceParams()
    }
}
