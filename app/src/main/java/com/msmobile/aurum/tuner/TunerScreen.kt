package com.msmobile.aurum.tuner

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.msmobile.aurum.R
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.processing.EnhanceParam
import com.msmobile.aurum.processing.SceneAdaptiveHdr
import com.msmobile.aurum.tuner.TunerViewModel.Tab
import com.msmobile.aurum.tuner.TunerViewModel.UiEvent
import com.msmobile.aurum.ui.theme.AurumTheme
import com.msmobile.aurum.ui.theme.PreviewPhone
import com.msmobile.aurum.ui.theme.paperGrain
import java.util.Locale

/** Full-screen layer over the camera, which stays bound underneath so the tuner can take preview shots. */
@Composable
fun TunerScreen(
    onClose: () -> Unit,
    darkTheme: Boolean = isSystemInDarkTheme(),
    viewModel: TunerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val close = {
        viewModel.onEvent(UiEvent.Closed)
        onClose()
    }
    LaunchedEffect(Unit) { viewModel.onEvent(UiEvent.Opened) }
    BackHandler(onBack = close)
    TunerSystemBarIcons(lightNavigationBar = !darkTheme)

    TunerScreenContent(
        uiState = uiState,
        colors = if (darkTheme) TunerColors.Dark else TunerColors.Light,
        onEvent = viewModel::onEvent,
        onClose = close,
        onDone = {
            viewModel.onEvent(UiEvent.Done)
            close()
        },
        photo = { modifier ->
            val image = if (uiState.isComparing) uiState.shot?.original else uiState.rendered ?: uiState.shot?.original
            image?.let {
                Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
            }
        },
    )
}

/**
 * The status bar sits over the photo, so it needs light icons even when the camera's honey theme has dark ones; the
 * navigation bar sits over the panel, so it needs dark icons only on the light panel.
 */
@Composable
private fun TunerSystemBarIcons(lightNavigationBar: Boolean) {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    DisposableEffect(lightNavigationBar) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val previousStatusBar = controller.isAppearanceLightStatusBars
        val previousNavigationBar = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = lightNavigationBar
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatusBar
            controller.isAppearanceLightNavigationBars = previousNavigationBar
        }
    }
}

/**
 * @param photo draws the preview shot (processed, or the original while comparing) with the given modifier. A slot
 * so previews and screenshot tests can run without OpenCV.
 */
@Composable
private fun TunerScreenContent(
    uiState: TunerViewModel.UiState,
    colors: TunerColors,
    onEvent: (UiEvent) -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
    photo: @Composable (modifier: Modifier) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.panel)
            // Keeps touches from reaching the camera underneath.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        PreviewArea(
            uiState = uiState,
            colors = colors,
            onEvent = onEvent,
            onClose = onClose,
            photo = photo,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        ParamsPanel(
            uiState = uiState,
            colors = colors,
            onEvent = onEvent,
            onDone = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun PreviewArea(
    uiState: TunerViewModel.UiState,
    colors: TunerColors,
    onEvent: (UiEvent) -> Unit,
    onClose: () -> Unit,
    photo: @Composable (modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photoDescription = stringResource(
        if (uiState.isComparing) R.string.tuner_preview_original else R.string.tuner_preview_processed
    )
    Box(
        modifier = modifier
            .clip(RectangleShape)
            .background(colors.previewBackground)
    ) {
        photo(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = if (uiState.isZoomed) ZOOM_SCALE else 1f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.54f, 0.4f)
                }
                // Front shots are mirrored around the centre, like the camera's viewfinder; the saved photo isn't.
                .graphicsLayer { scaleX = if (uiState.lens == Lens.FRONT) -1f else 1f }
                .semantics { contentDescription = photoDescription }
        )
        if (uiState.isRendering || uiState.isCapturing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.renderVeil)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipIconButton(colors = colors, onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.tuner_close),
                    modifier = Modifier.size(20.dp),
                )
            }
            StatusChip(uiState = uiState, colors = colors)
            ZoomButton(isZoomed = uiState.isZoomed, colors = colors) { onEvent(UiEvent.ZoomToggled) }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChipIconButton(colors = colors, onClick = { onEvent(UiEvent.TakePreviewShot) }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.tuner_retake),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (uiState.canSwitchLens) {
                ChipIconButton(colors = colors, onClick = { onEvent(UiEvent.SwitchLens) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_switch_camera),
                        contentDescription = stringResource(
                            if (uiState.lens == Lens.FRONT) R.string.tuner_switch_to_back else R.string.tuner_switch_to_front
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        CompareButton(
            isComparing = uiState.isComparing,
            colors = colors,
            onComparingChange = { onEvent(UiEvent.ComparingChanged(it)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        )
    }
}

@Composable
private fun ChipIconButton(colors: TunerColors, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = colors.chipBackground,
            contentColor = colors.chipContent,
        ),
        content = content,
    )
}

@Composable
private fun StatusChip(uiState: TunerViewModel.UiState, colors: TunerColors) {
    val lens = stringResource(if (uiState.lens == Lens.FRONT) R.string.tuner_lens_front else R.string.tuner_lens_back)
    val status = when {
        uiState.error != null -> stringResource(uiState.error.textResId, *uiState.error.arguments.toTypedArray())
        uiState.isCapturing -> stringResource(R.string.tuner_status_capturing)
        uiState.isRendering -> stringResource(R.string.tuner_status_rendering)
        uiState.isComparing -> stringResource(R.string.tuner_status_original)
        else -> stringResource(R.string.tuner_status_ready, uiState.renderMillis)
    }.let { if (uiState.error == null) stringResource(R.string.tuner_status_with_lens, lens, it) else it }
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.chipBackground)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.error == null && (uiState.isCapturing || uiState.isRendering)) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = colors.chipContent,
                trackColor = colors.chipContent.copy(alpha = 0.3f),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = status,
            color = colors.chipContent,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun ZoomButton(isZoomed: Boolean, colors: TunerColors, onClick: () -> Unit) {
    val description = stringResource(R.string.tuner_zoom_description)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp)
            .height(44.dp)
            .semantics {
                contentDescription = description
                selected = isZoomed
            },
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (isZoomed) colors.chipOnBackground else colors.chipBackground,
            contentColor = if (isZoomed) colors.chipOnContent else colors.chipContent,
        ),
    ) {
        Text(text = stringResource(R.string.tuner_zoom), style = MonoStyle.copy(fontSize = 13.sp))
    }
}

@Composable
private fun CompareButton(
    isComparing: Boolean,
    colors: TunerColors,
    onComparingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.tuner_compare_description)
    val currentOnComparingChange by rememberUpdatedState(onComparingChange)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (isComparing) colors.chipOnBackground else colors.chipBackground)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    currentOnComparingChange(true)
                    waitForUpOrCancellation()
                    currentOnComparingChange(false)
                }
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (isComparing) colors.chipOnContent else colors.chipContent
        Icon(
            painter = painterResource(R.drawable.ic_compare),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(if (isComparing) R.string.tuner_compare_original else R.string.tuner_compare_hold),
            color = content,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun ParamsPanel(
    uiState: TunerViewModel.UiState,
    colors: TunerColors,
    onEvent: (UiEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .paperGrain(colors.grain, colors.grainAlpha, colors.grainBlendMode)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tabs(uiState = uiState, colors = colors, onSelect = { onEvent(UiEvent.TabSelected(it)) })
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            uiState.tab.params.forEach { param ->
                ParamRow(param = param, uiState = uiState, colors = colors, onEvent = onEvent)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onEvent(UiEvent.ResetToPreset) },
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(start = 0.dp, end = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.link),
            ) {
                Text(text = stringResource(R.string.tuner_reset), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
            Button(
                onClick = onDone,
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryBackground,
                    contentColor = colors.primaryContent,
                ),
            ) {
                Text(text = stringResource(R.string.tuner_done), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun Tabs(uiState: TunerViewModel.UiState, colors: TunerColors, onSelect: (Tab) -> Unit) {
    val groupDescription = stringResource(R.string.tuner_parameter_group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(colors.tabTrack)
            .padding(4.dp)
            .semantics { contentDescription = groupDescription }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == uiState.tab
            val name = stringResource(tab.labelRes)
            val count = uiState.modifiedCount(tab)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.tabSelectedBackground else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 0) stringResource(R.string.tuner_tab_with_changes, name, count) else name,
                    color = if (selected) colors.tabSelectedContent else colors.tabContent,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ParamRow(
    param: EnhanceParam,
    uiState: TunerViewModel.UiState,
    colors: TunerColors,
    onEvent: (UiEvent) -> Unit,
) {
    val params = uiState.draft.params
    val value = param.get(params)
    val hdrScale = uiState.hdrScale
    val display = if (param == EnhanceParam.HDR && hdrScale != null) {
        "${param.format(value)} → ${param.format(value * hdrScale)}"
    } else {
        param.format(value)
    }
    val label = stringResource(param.labelRes)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = label, color = colors.text, style = TextStyle(fontSize = 15.sp))
            Text(text = param.key, color = colors.faint, style = MonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = display,
                color = if (param.isModified(params)) colors.modified else colors.text,
                style = MonoStyle.copy(fontSize = 14.sp),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onEvent(UiEvent.ParamChanged(param, it.toDouble())) },
            valueRange = param.min.toFloat()..param.max.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics { contentDescription = label },
            colors = SliderDefaults.colors(
                thumbColor = colors.slider,
                activeTrackColor = colors.slider,
                inactiveTrackColor = colors.slider.copy(alpha = 0.24f),
            ),
        )
        if (param == EnhanceParam.HDR) {
            ScaleHdrCheckbox(uiState = uiState, colors = colors) { onEvent(UiEvent.ScaleHdrBySceneLightToggled) }
        }
    }
}

@Composable
private fun ScaleHdrCheckbox(uiState: TunerViewModel.UiState, colors: TunerColors, onToggle: () -> Unit) {
    val checked = uiState.draft.scaleHdrBySceneLight
    val ev100 = uiState.ev100
    val text = if (ev100 == null) {
        stringResource(R.string.tuner_scale_hdr_unknown)
    } else {
        val scale = SceneAdaptiveHdr.scale(ev100)
        stringResource(R.string.tuner_scale_hdr, "%.1f".format(Locale.ROOT, ev100), "%.2f".format(Locale.ROOT, scale))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = colors.slider,
                uncheckedColor = colors.muted,
                checkmarkColor = colors.panel,
            ),
        )
        Text(text = text, color = colors.muted, style = TextStyle(fontSize = 13.sp))
    }
}

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
private const val ZOOM_SCALE = 2.6f

@get:StringRes
private val Tab.labelRes: Int
    get() = when (this) {
        Tab.COLOUR -> R.string.tuner_tab_colour
        Tab.LIGHT -> R.string.tuner_tab_light
        Tab.DETAIL -> R.string.tuner_tab_detail
    }

@get:StringRes
private val EnhanceParam.labelRes: Int
    get() = when (this) {
        EnhanceParam.WHITE_BALANCE -> R.string.tuner_param_white_balance
        EnhanceParam.WARMTH -> R.string.tuner_param_warmth
        EnhanceParam.VIBRANCE -> R.string.tuner_param_vibrance
        EnhanceParam.GOLDEN -> R.string.tuner_param_golden
        EnhanceParam.EXPOSURE -> R.string.tuner_param_exposure
        EnhanceParam.CLAHE_WEIGHT -> R.string.tuner_param_clahe_weight
        EnhanceParam.CURVE -> R.string.tuner_param_curve
        EnhanceParam.HDR -> R.string.tuner_param_hdr
        EnhanceParam.MATTE -> R.string.tuner_param_matte
        EnhanceParam.TEXTURE -> R.string.tuner_param_texture
        EnhanceParam.GRAIN -> R.string.tuner_param_grain
        EnhanceParam.GRAIN_SIZE -> R.string.tuner_param_grain_size
    }

/** The value with as many decimals as the step needs, e.g. 0.020 for warmth, 2.2 for grain. */
private fun EnhanceParam.format(value: Double): String {
    val decimals = when (this) {
        EnhanceParam.WARMTH -> 3
        EnhanceParam.GRAIN -> 1
        else -> 2
    }
    return "%.${decimals}f".format(Locale.ROOT, value)
}

@PreviewPhone
@Composable
internal fun TunerScreenPreview(
    @PreviewParameter(TunerPreviewConfigProvider::class) config: TunerPreviewConfig,
) {
    AurumTheme(darkTheme = config.darkTheme, dynamicColor = false) {
        TunerScreenContent(
            uiState = config.uiState,
            colors = if (config.darkTheme) TunerColors.Dark else TunerColors.Light,
            onEvent = {},
            onClose = {},
            onDone = {},
            photo = { modifier -> PreviewPhotoPlaceholder(modifier) },
        )
    }
}

/** A flat portrait standing in for the preview shot in previews and screenshot tests. */
@Composable
private fun PreviewPhotoPlaceholder(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFFB7A288))
        drawRect(Color(0xFF8C7A63), topLeft = Offset(0.07f * w, 0.09f * h), size = Size(0.34f * w, 0.47f * h))
        drawRect(Color(0xFFEAE5D7), topLeft = Offset(0.07f * w + 3.dp.toPx(), 0.09f * h + 3.dp.toPx()),
            size = Size(0.34f * w - 6.dp.toPx(), 0.47f * h - 6.dp.toPx()))
        drawRect(Color(0xFF8C7A63), topLeft = Offset(0.24f * w, 0.09f * h), size = Size(3.dp.toPx(), 0.47f * h))
        drawRect(Color(0xFF6B5643), topLeft = Offset(0f, 0.78f * h), size = Size(w, 0.22f * h))
        drawRoundRect(Color(0xFF9A5B3C), topLeft = Offset(0.75f * w, 0.71f * h), size = Size(0.16f * w, 0.11f * h),
            cornerRadius = CornerRadius(6.dp.toPx()))
        drawOval(Color(0xFF4F6B45), topLeft = Offset(0.74f * w, 0.50f * h), size = Size(0.18f * w, 0.22f * h))
        drawOval(Color(0xFF3C4A60), topLeft = Offset(0.33f * w, 0.64f * h), size = Size(0.42f * w, 0.72f * h))
        drawOval(Color(0xFFC99A7B), topLeft = Offset(0.43f * w, 0.30f * h), size = Size(0.22f * w, 0.23f * h))
        drawOval(Color(0xFF3A2A20), topLeft = Offset(0.415f * w, 0.27f * h), size = Size(0.25f * w, 0.12f * h))
    }
}
