package com.msmobile.aurum.processing

/** One function of `corrigir_foto.py`'s pipeline. Implementations must not modify [image]. */
interface ImageProcessingStep {
    fun process(image: PhotoImage, params: EnhanceParams): PhotoImage
}
