package com.msmobile.aurum.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random

/** A fine, warm paper grain behind the content, like the design's. Seeded, so screenshots stay stable. */
fun Modifier.paperGrain(color: Color, alpha: Float, blendMode: BlendMode): Modifier = drawWithCache {
    val noise = grainTile(color)
    val brush = ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind { drawRect(brush = brush, alpha = alpha, blendMode = blendMode) }
}

private fun grainTile(color: Color): ImageBitmap {
    val random = Random(GRAIN_SEED)
    val rgb = color.toArgb() and 0x00FFFFFF
    val pixels = IntArray(GRAIN_TILE_PX * GRAIN_TILE_PX) { (random.nextInt(256) shl 24) or rgb }
    return Bitmap.createBitmap(pixels, GRAIN_TILE_PX, GRAIN_TILE_PX, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private const val GRAIN_TILE_PX = 128
private const val GRAIN_SEED = 7
