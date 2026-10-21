package com.msmobile.aurum.camera

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.msmobile.aurum.ui.theme.Amber
import com.msmobile.aurum.ui.theme.Cream
import com.msmobile.aurum.ui.theme.Honey
import com.msmobile.aurum.ui.theme.Ink
import com.msmobile.aurum.ui.theme.Night
import com.msmobile.aurum.ui.theme.Paper
import com.msmobile.aurum.ui.theme.Sand

/**
 * The camera screen's own colours: honey in the light theme, night in the dark one, matching the tuner rather than
 * the Material colour scheme, which is dynamic on Android 12+.
 */
internal data class CameraColors(
    val background: Color,
    val icon: Color,
    val pillBackground: Color,
    val pillContent: Color,
    val spinner: Color,
    val shutterRing: Color,
    val shutterFill: Color,
    val thumbnailRing: Color,
    val thumbnailEmpty: Color,
    val focusRing: Color,
    val primaryBackground: Color,
    val primaryContent: Color,
    val grain: Color,
    val grainAlpha: Float,
    val grainBlendMode: BlendMode,
) {
    companion object {
        val Light = CameraColors(
            background = Cream,
            icon = Ink,
            pillBackground = Sand,
            pillContent = Ink,
            spinner = Amber,
            shutterRing = Ink,
            shutterFill = Honey,
            thumbnailRing = Ink,
            thumbnailEmpty = Sand,
            focusRing = Honey,
            primaryBackground = Ink,
            primaryContent = Honey,
            grain = Amber,
            grainAlpha = 0.18f,
            grainBlendMode = BlendMode.Multiply,
        )

        val Dark = CameraColors(
            background = Night,
            icon = Paper,
            pillBackground = Ink,
            pillContent = Paper,
            spinner = Honey,
            shutterRing = Paper,
            shutterFill = Honey,
            thumbnailRing = Sand,
            thumbnailEmpty = Ink,
            focusRing = Honey,
            primaryBackground = Honey,
            primaryContent = Ink,
            grain = Honey,
            grainAlpha = 0.06f,
            grainBlendMode = BlendMode.Screen,
        )
    }
}
