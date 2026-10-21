package com.msmobile.aurum.tuner

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.msmobile.aurum.ui.theme.Amber
import com.msmobile.aurum.ui.theme.Bark
import com.msmobile.aurum.ui.theme.Clay
import com.msmobile.aurum.ui.theme.Cream
import com.msmobile.aurum.ui.theme.Honey
import com.msmobile.aurum.ui.theme.Ink
import com.msmobile.aurum.ui.theme.Night
import com.msmobile.aurum.ui.theme.Paper
import com.msmobile.aurum.ui.theme.Rust
import com.msmobile.aurum.ui.theme.Sand
import com.msmobile.aurum.ui.theme.Umber

/**
 * The tuner's own colours: it has a fixed honey look in both themes rather than following the Material colour
 * scheme, which is dynamic on Android 12+.
 */
internal data class TunerColors(
    val panel: Color,
    val previewBackground: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val tabTrack: Color,
    val tabSelectedBackground: Color,
    val tabSelectedContent: Color,
    val tabContent: Color,
    val slider: Color,
    val modified: Color,
    val link: Color,
    val primaryBackground: Color,
    val primaryContent: Color,
    val chipBackground: Color,
    val chipContent: Color,
    val chipOnBackground: Color,
    val chipOnContent: Color,
    val renderVeil: Color,
    val grain: Color,
    val grainAlpha: Float,
    val grainBlendMode: BlendMode,
) {
    companion object {
        val Light = TunerColors(
            panel = Cream,
            previewBackground = Sand,
            text = Ink,
            muted = Bark,
            faint = Clay,
            tabTrack = Sand,
            tabSelectedBackground = Ink,
            tabSelectedContent = Honey,
            tabContent = Umber,
            slider = Amber,
            modified = Rust,
            link = Rust,
            primaryBackground = Ink,
            primaryContent = Honey,
            chipBackground = Ink.copy(alpha = 0.72f),
            chipContent = Paper,
            chipOnBackground = Honey,
            chipOnContent = Ink,
            renderVeil = Cream.copy(alpha = 0.3f),
            grain = Color(0xFFC27A1A),
            grainAlpha = 0.18f,
            grainBlendMode = BlendMode.Multiply,
        )

        val Dark = TunerColors(
            panel = Night,
            previewBackground = Color.Black,
            text = Paper,
            muted = Sand,
            faint = Sand,
            tabTrack = Ink,
            tabSelectedBackground = Honey,
            tabSelectedContent = Ink,
            tabContent = Cream,
            slider = Amber,
            modified = Honey,
            link = Honey,
            primaryBackground = Honey,
            primaryContent = Ink,
            chipBackground = Color.Black.copy(alpha = 0.65f),
            chipContent = Paper,
            chipOnBackground = Honey,
            chipOnContent = Ink,
            renderVeil = Color.Black.copy(alpha = 0.25f),
            grain = Honey,
            grainAlpha = 0.06f,
            grainBlendMode = BlendMode.Screen,
        )
    }
}
