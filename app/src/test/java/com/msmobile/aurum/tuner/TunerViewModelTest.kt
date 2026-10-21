package com.msmobile.aurum.tuner

import androidx.compose.ui.graphics.ImageBitmap
import com.msmobile.aurum.R
import com.msmobile.aurum.capture.CameraSession
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.capture.CapturedPhoto
import com.msmobile.aurum.processing.EnhanceParam
import com.msmobile.aurum.processing.EnhanceParams
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.ProcessingSettingsStore
import com.msmobile.aurum.tuner.TunerViewModel.Tab
import com.msmobile.aurum.tuner.TunerViewModel.UiEvent
import com.msmobile.aurum.util.Logger
import com.msmobile.aurum.util.MockReferenceHolder
import com.msmobile.aurum.util.StringResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class TunerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun opened_savedSettings_startsFromThemAndRendersAShot() = runTest(dispatcher) {
        // Arrange
        val saved = ProcessingSettings(params = EnhanceParams.IPHONE.copy(grain = 1.0), scaleHdrBySceneLight = false)
        val viewModel = createViewModel(settings = saved)

        // Act
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertEquals(saved, state.draft)
        assertNotNull(state.shot)
        assertNotNull(state.rendered)
        assertEquals(RENDER_MILLIS, state.renderMillis)
        assertFalse(state.isCapturing)
        assertFalse(state.isRendering)
    }

    @Test
    fun paramChanged_afterShot_rendersWithNewValue() = runTest(dispatcher) {
        // Arrange
        val rendererRef = MockReferenceHolder<PreviewRenderer>()
        val viewModel = createViewModel(
            settings = ProcessingSettings(scaleHdrBySceneLight = false),
            rendererRef = rendererRef,
        )
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.ParamChanged(EnhanceParam.EXPOSURE, 0.1))
        advanceUntilIdle()

        // Assert
        val expected = EnhanceParams.IPHONE.copy(exposure = 0.1)
        assertEquals(expected, viewModel.uiState.value.draft.params)
        verify(requireNotNull(rendererRef.value))
            .render(any(), eq(ProcessingSettings(params = expected, scaleHdrBySceneLight = false)))
    }

    @Test
    fun opened_brightSceneAndScalingOn_showsScaleAndRendersWithScaling() = runTest(dispatcher) {
        // Arrange
        val rendererRef = MockReferenceHolder<PreviewRenderer>()
        val viewModel = createViewModel(ev100 = 14.0, rendererRef = rendererRef)

        // Act
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Assert
        assertEquals(0.4, requireNotNull(viewModel.uiState.value.hdrScale), 1e-9)
        verify(requireNotNull(rendererRef.value)).render(any(), eq(ProcessingSettings(scaleHdrBySceneLight = true)))
    }

    @Test
    fun scaleHdrToggled_brightScene_rendersWithoutScaling() = runTest(dispatcher) {
        // Arrange
        val rendererRef = MockReferenceHolder<PreviewRenderer>()
        val viewModel = createViewModel(ev100 = 14.0, rendererRef = rendererRef)
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.ScaleHdrBySceneLightToggled)
        advanceUntilIdle()

        // Assert
        assertNull(viewModel.uiState.value.hdrScale)
        verify(requireNotNull(rendererRef.value)).render(any(), eq(ProcessingSettings(scaleHdrBySceneLight = false)))
    }

    @Test
    fun done_afterChanges_savesDraft() = runTest(dispatcher) {
        // Arrange
        val storeRef = MockReferenceHolder<ProcessingSettingsStore>()
        val viewModel = createViewModel(storeRef = storeRef)
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()
        viewModel.onEvent(UiEvent.ParamChanged(EnhanceParam.GRAIN, 3.0))

        // Act
        viewModel.onEvent(UiEvent.Done)

        // Assert
        verify(requireNotNull(storeRef.value)).save(ProcessingSettings(params = EnhanceParams.IPHONE.copy(grain = 3.0)))
    }

    @Test
    fun closed_withoutDone_discardsPreviewAndSavesNothing() = runTest(dispatcher) {
        // Arrange
        val storeRef = MockReferenceHolder<ProcessingSettingsStore>()
        val viewModel = createViewModel(storeRef = storeRef)
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()
        viewModel.onEvent(UiEvent.ParamChanged(EnhanceParam.GRAIN, 3.0))

        // Act
        viewModel.onEvent(UiEvent.Closed)
        advanceUntilIdle()

        // Assert
        assertNull(viewModel.uiState.value.shot)
        assertNull(viewModel.uiState.value.rendered)
        verify(requireNotNull(storeRef.value), never()).save(any())
    }

    @Test
    fun takePreviewShot_again_discardsPreviousShotWhileCapturing() = runTest(dispatcher) {
        // Arrange
        val nextCapture = CompletableDeferred<CapturedPhoto>()
        val captures = ArrayDeque(listOf(CompletableDeferred(photo()), nextCapture))
        val viewModel = createViewModel(capture = { captures.removeFirst().await() })
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()
        val firstShot = requireNotNull(viewModel.uiState.value.shot)

        // Act
        viewModel.onEvent(UiEvent.TakePreviewShot)
        advanceUntilIdle()
        val whileCapturing = viewModel.uiState.value
        nextCapture.complete(photo())
        advanceUntilIdle()

        // Assert
        assertNull(whileCapturing.shot)
        assertNull(whileCapturing.rendered)
        assertNotSame(firstShot, requireNotNull(viewModel.uiState.value.shot))
    }

    @Test
    fun takePreviewShot_captureFails_showsCaptureError() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(capture = { throw IllegalStateException("camera closed") })

        // Act
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Assert
        assertEquals(StringResource(R.string.error_preview_capture), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isCapturing)
    }

    @Test
    fun resetToPreset_afterChanges_restoresPresetAndClearsCounts() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(settings = ProcessingSettings(params = EnhanceParams.IPHONE.copy(warmth = 0.0)))
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()
        val countBefore = viewModel.uiState.value.modifiedCount(Tab.COLOUR)

        // Act
        viewModel.onEvent(UiEvent.ResetToPreset)

        // Assert
        assertEquals(1, countBefore)
        assertEquals(EnhanceParams.IPHONE, viewModel.uiState.value.draft.params)
        assertEquals(0, viewModel.uiState.value.modifiedCount(Tab.COLOUR))
    }

    @Test
    fun switchLens_bothLensesAvailable_selectsFrontAndShootsWithIt() = runTest(dispatcher) {
        // Arrange
        val sessionRef = MockReferenceHolder<CameraSession>()
        val viewModel = createViewModel(sessionRef = sessionRef)
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.SwitchLens)
        advanceUntilIdle()

        // Assert
        val session = requireNotNull(sessionRef.value)
        assertEquals(Lens.FRONT, viewModel.uiState.value.lens)
        assertNotNull(viewModel.uiState.value.shot)
        verify(session).selectLens(Lens.FRONT)
        verify(session, times(2)).takePicture()
    }

    @Test
    fun switchLens_cameraNeverRebinds_showsCaptureErrorWithoutShooting() = runTest(dispatcher) {
        // Arrange
        val sessionRef = MockReferenceHolder<CameraSession>()
        val viewModel = createViewModel(rebindsOnSwitch = false, sessionRef = sessionRef)
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.SwitchLens)
        advanceUntilIdle()

        // Assert
        assertEquals(StringResource(R.string.error_preview_capture), viewModel.uiState.value.error)
        verify(requireNotNull(sessionRef.value), times(1)).takePicture()
    }

    @Test
    fun switchLens_onlyBackCamera_keepsBackCamera() = runTest(dispatcher) {
        // Arrange
        val sessionRef = MockReferenceHolder<CameraSession>()
        val viewModel = createViewModel(availableLenses = setOf(Lens.BACK), sessionRef = sessionRef)
        viewModel.onEvent(UiEvent.Opened)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.SwitchLens)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.uiState.value.canSwitchLens)
        assertEquals(Lens.BACK, viewModel.uiState.value.lens)
        verify(requireNotNull(sessionRef.value), never()).selectLens(any())
    }

    private fun photo() = CapturedPhoto(jpeg = byteArrayOf(1, 2, 3), rotationDegrees = 90)

    private fun createViewModel(
        settings: ProcessingSettings = ProcessingSettings(),
        ev100: Double? = 9.0,
        capture: suspend () -> CapturedPhoto = { photo() },
        availableLenses: Set<Lens> = setOf(Lens.BACK, Lens.FRONT),
        rebindsOnSwitch: Boolean = true,
        sessionRef: MockReferenceHolder<CameraSession>? = null,
        storeRef: MockReferenceHolder<ProcessingSettingsStore>? = null,
        rendererRef: MockReferenceHolder<PreviewRenderer>? = null,
    ): TunerViewModel {
        val lens = MutableStateFlow(Lens.BACK)
        // Stands in for the camera screen, which binds whichever lens is selected.
        val boundLens = MutableStateFlow<Lens?>(Lens.BACK)
        val session = mock<CameraSession> {
            on { takePicture() } doSuspendableAnswer { capture() }
            on { this.lens } doReturn lens
            on { this.boundLens } doReturn boundLens
            on { availableLenses() } doReturn availableLenses
            on { selectLens(any()) } doAnswer {
                lens.value = it.getArgument(0)
                if (rebindsOnSwitch) boundLens.value = lens.value
            }
        }
        sessionRef?.value = session
        val renderer = mock<PreviewRenderer> {
            on { prepare(any()) } doAnswer {
                PreviewShot(original = mock<ImageBitmap>(), ev100 = ev100, photo = photo())
            }
            on { render(any(), any()) } doReturn RenderedPreview(image = mock<ImageBitmap>(), millis = RENDER_MILLIS)
        }
        val store = mock<ProcessingSettingsStore> {
            on { this.settings } doReturn MutableStateFlow(settings)
        }
        storeRef?.value = store
        rendererRef?.value = renderer
        return TunerViewModel(session, renderer, store, mock<Logger>())
    }

    private companion object {
        const val RENDER_MILLIS = 840L
    }
}
