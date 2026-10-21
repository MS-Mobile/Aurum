package com.msmobile.aurum.di

import com.msmobile.aurum.processing.ImageProcessingStep
import com.msmobile.aurum.processing.steps.DynamicRangeStep
import com.msmobile.aurum.processing.steps.GoldenToneStep
import com.msmobile.aurum.processing.steps.LevelsStep
import com.msmobile.aurum.processing.steps.LocalHdrStep
import com.msmobile.aurum.processing.steps.MatteStep
import com.msmobile.aurum.processing.steps.SkinTextureStep
import com.msmobile.aurum.processing.steps.VibranceStep
import com.msmobile.aurum.processing.steps.WhiteBalanceStep
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class ProcessingModule {
    /** The pipeline in `corrigir_foto.py`'s `main()` order; texture and grain always last. */
    @Provides
    fun imageProcessingSteps(
        whiteBalance: WhiteBalanceStep,
        levels: LevelsStep,
        dynamicRange: DynamicRangeStep,
        localHdr: LocalHdrStep,
        vibrance: VibranceStep,
        goldenTone: GoldenToneStep,
        matte: MatteStep,
        skinTexture: SkinTextureStep,
    ): List<ImageProcessingStep> =
        listOf(whiteBalance, levels, dynamicRange, localHdr, vibrance, goldenTone, matte, skinTexture)
}
