package com.msmobile.aurum.camera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.msmobile.aurum.R
import com.msmobile.aurum.camera.CameraViewModel.UiEvent
import com.msmobile.aurum.camera.CameraViewModel.UiEventState
import com.msmobile.aurum.capture.CameraSession.FlashMode
import com.msmobile.aurum.ui.theme.AurumTheme
import com.msmobile.aurum.ui.theme.PreviewPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun CameraScreen(onOpenTuner: () -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onEvent(UiEvent.CameraPermissionChanged(granted))
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onEvent(UiEvent.CameraPermissionChanged(granted))
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (uiState.hasCameraPermission) {
        LaunchedEffect(lifecycleOwner) { viewModel.bindToCamera(lifecycleOwner) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(UiEvent.RefreshLastPhoto) }

    CameraScreenContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onOpenTuner = onOpenTuner,
        onOpenPermissionSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        },
        onOpenPhoto = { uri ->
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: SecurityException) {
                // The photo was deleted or trashed in the gallery meanwhile.
                viewModel.onEvent(UiEvent.RefreshLastPhoto)
            } catch (e: ActivityNotFoundException) {
                viewModel.onEvent(UiEvent.RefreshLastPhoto)
            }
        },
        viewfinder = { modifier, onTap ->
            val coordinateTransformer = remember { MutableCoordinateTransformer() }
            surfaceRequest?.let { request ->
                CameraXViewfinder(
                    surfaceRequest = request,
                    coordinateTransformer = coordinateTransformer,
                    modifier = modifier.pointerInput(coordinateTransformer) {
                        detectTapGestures { tap ->
                            onTap(tap)
                            val surfacePoint = with(coordinateTransformer) { tap.transform() }
                            viewModel.onEvent(UiEvent.FocusAt(surfacePoint.x, surfacePoint.y))
                        }
                    },
                )
            }
        },
    )
}

/**
 * @param viewfinder draws the camera preview with the given modifier and reports taps (in its own coordinates)
 * to the callback, which shows the focus ring. A slot so previews and screenshot tests can run without a camera.
 */
@Composable
private fun CameraScreenContent(
    uiState: CameraViewModel.UiState,
    onEvent: (UiEvent) -> Unit,
    onOpenTuner: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenPhoto: (Uri) -> Unit,
    viewfinder: @Composable (modifier: Modifier, onTap: (Offset) -> Unit) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = (uiState.eventState as? UiEventState.Error)?.let {
        stringResource(it.message.textResId, *it.message.arguments.toTypedArray())
    }
    LaunchedEffect(uiState.eventState) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            onEvent(UiEvent.EventStateHandled)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (uiState.hasCameraPermission) {
            CameraControls(
                uiState = uiState,
                onEvent = onEvent,
                onOpenTuner = onOpenTuner,
                onOpenPhoto = onOpenPhoto,
                viewfinder = viewfinder,
            )
        } else {
            PermissionRequest(onOpenPermissionSettings = onOpenPermissionSettings)
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 140.dp),
        )
    }
}

@Composable
private fun BoxScope.CameraControls(
    uiState: CameraViewModel.UiState,
    onEvent: (UiEvent) -> Unit,
    onOpenTuner: () -> Unit,
    onOpenPhoto: (Uri) -> Unit,
    viewfinder: @Composable (modifier: Modifier, onTap: (Offset) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shutterFlash = remember { Animatable(0f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
        ) {
            if (uiState.hasFlashUnit) {
                val (icon, label) = uiState.flashMode.iconAndLabel()
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    ControlButton(icon = icon, label = label) { onEvent(UiEvent.CycleFlash) }
                }
            }
            if (uiState.processingCount > 0) {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    ProcessingIndicator(count = uiState.processingCount)
                }
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                ControlButton(icon = R.drawable.ic_tune, label = R.string.action_tune_processing, onClick = onOpenTuner)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
        ) {
            viewfinder(Modifier.fillMaxSize()) { tap -> focusPoint = tap }
            focusPoint?.let { point ->
                FocusRing(point)
                LaunchedEffect(point) {
                    delay(1_000)
                    focusPoint = null
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(shutterFlash.value)
                    .background(Color.Black)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(52.dp)) {
                LastPhotoThumbnail(uri = uiState.lastPhoto, onClick = onOpenPhoto)
            }
            ShutterButton {
                onEvent(UiEvent.TakePhoto)
                scope.launch {
                    shutterFlash.snapTo(0.8f)
                    shutterFlash.animateTo(0f, tween(durationMillis = 250))
                }
            }
            Box(modifier = Modifier.size(48.dp)) {
                if (uiState.canSwitchLens) {
                    ControlButton(icon = R.drawable.ic_switch_camera, label = R.string.action_switch_camera) {
                        onEvent(UiEvent.SwitchLens)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingIndicator(count: Int) {
    val description = stringResource(R.string.processing_photos, count)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = Color.White,
            strokeWidth = 2.dp,
        )
        if (count > 1) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = count.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LastPhotoThumbnail(uri: Uri?, onClick: (Uri) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, uri) {
        if (uri == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { resolver.loadThumbnail(uri, android.util.Size(THUMBNAIL_PX, THUMBNAIL_PX), null).asImageBitmap() }
                .getOrNull()
        }
    }
    val description = stringResource(R.string.action_open_last_photo)
    // Gallery slot: a subtle ring around the last photo, or a dark grey circle before the first photo.
    val slot = Modifier
        .size(52.dp)
        .clip(CircleShape)
        .background(if (uri == null) Color(0xFF2A2A2A) else Color.Transparent)
        .border(width = 1.dp, color = Color.White.copy(alpha = 0.5f), shape = CircleShape)
    Box(
        modifier = if (uri == null) {
            slot
        } else {
            slot
                .semantics { contentDescription = description }
                .clickable(role = Role.Button) { onClick(uri) }
        }
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val THUMBNAIL_PX = 192

@Composable
private fun FocusRing(point: Offset) {
    val sizePx = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((point.x - sizePx / 2).roundToInt(), (point.y - sizePx / 2).roundToInt()) }
            .size(64.dp)
            .border(width = 1.5.dp, color = Color.White, shape = CircleShape)
    )
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val description = stringResource(R.string.action_take_photo)
    Box(
        modifier = Modifier
            .size(76.dp)
            .border(width = 4.dp, color = Color.White, shape = CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(Color.White)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
    )
}

@Composable
private fun ControlButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(label), tint = Color.White)
    }
}

@Composable
private fun PermissionRequest(onOpenPermissionSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_rationale),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenPermissionSettings) {
            Text(text = stringResource(R.string.camera_permission_open_settings))
        }
    }
}

private fun FlashMode.iconAndLabel(): Pair<Int, Int> = when (this) {
    FlashMode.OFF -> R.drawable.ic_flash_off to R.string.flash_off
    FlashMode.AUTO -> R.drawable.ic_flash_auto to R.string.flash_auto
    FlashMode.ON -> R.drawable.ic_flash_on to R.string.flash_on
}

@PreviewPhone
@Composable
internal fun CameraScreenPreview(
    @PreviewParameter(CameraPreviewConfigProvider::class) config: CameraPreviewConfig,
) {
    AurumTheme(darkTheme = true, dynamicColor = false) {
        CameraScreenContent(
            uiState = config.uiState,
            onEvent = {},
            onOpenTuner = {},
            onOpenPermissionSettings = {},
            onOpenPhoto = {},
            viewfinder = { modifier, _ -> Box(modifier = modifier.background(Color(0xFF3A3A3A))) },
        )
    }
}
