package com.msmobile.aurum.camera

import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import com.msmobile.aurum.R
import com.msmobile.aurum.camera.CameraViewModel.UiEvent
import com.msmobile.aurum.camera.CameraViewModel.UiEventState
import com.msmobile.aurum.capture.CameraSession
import com.msmobile.aurum.capture.CameraSession.FlashMode
import com.msmobile.aurum.capture.CameraSession.Lens
import com.msmobile.aurum.capture.CapturedPhoto
import com.msmobile.aurum.capture.PhotoGallery
import com.msmobile.aurum.capture.PhotoProcessor
import com.msmobile.aurum.processing.ProcessingSettings
import com.msmobile.aurum.processing.ProcessingSettingsStore
import com.msmobile.aurum.util.Logger
import com.msmobile.aurum.util.MockReferenceHolder
import com.msmobile.aurum.util.StringResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun cameraPermissionChanged_granted_updatesState() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel()

        // Act
        viewModel.onEvent(UiEvent.CameraPermissionChanged(granted = true))

        // Assert
        assertTrue(viewModel.uiState.value.hasCameraPermission)
    }

    @Test
    fun cycleFlash_repeatedly_cyclesOffAutoOnAndAppliesEachMode() = runTest(dispatcher) {
        // Arrange
        val sessionRef = MockReferenceHolder<CameraSession>()
        val viewModel = createViewModel(sessionRef = sessionRef)
        val seen = mutableListOf<FlashMode>()

        // Act
        repeat(3) {
            viewModel.onEvent(UiEvent.CycleFlash)
            seen += viewModel.uiState.value.flashMode
        }

        // Assert
        assertEquals(listOf(FlashMode.AUTO, FlashMode.ON, FlashMode.OFF), seen)
        val session = requireNotNull(sessionRef.value)
        inOrder(session) {
            verify(session).setFlashMode(FlashMode.AUTO)
            verify(session).setFlashMode(FlashMode.ON)
            verify(session).setFlashMode(FlashMode.OFF)
        }
    }

    @Test
    fun bindToCamera_bothLensesAvailable_enablesSwitchingAndReportsFlash() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(availableLenses = setOf(Lens.BACK, Lens.FRONT), hasFlashUnit = true)

        // Act
        val binding = launch { viewModel.bindToCamera(mock<LifecycleOwner>()) }
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.uiState.value.canSwitchLens)
        assertTrue(viewModel.uiState.value.hasFlashUnit)
        binding.cancel()
    }

    @Test
    fun switchLens_whileBound_rebindsWithFrontCamera() = runTest(dispatcher) {
        // Arrange
        val sessionRef = MockReferenceHolder<CameraSession>()
        val viewModel = createViewModel(availableLenses = setOf(Lens.BACK, Lens.FRONT), sessionRef = sessionRef)
        val owner = mock<LifecycleOwner>()
        val binding = launch { viewModel.bindToCamera(owner) }
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.SwitchLens)
        advanceUntilIdle()

        // Assert
        assertEquals(Lens.FRONT, viewModel.uiState.value.lens)
        verify(requireNotNull(sessionRef.value)).bind(owner, Lens.FRONT)
        binding.cancel()
    }

    @Test
    fun switchLens_onlyBackCamera_keepsBackCamera() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(availableLenses = setOf(Lens.BACK))
        val binding = launch { viewModel.bindToCamera(mock<LifecycleOwner>()) }
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.SwitchLens)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.uiState.value.canSwitchLens)
        assertEquals(Lens.BACK, viewModel.uiState.value.lens)
        binding.cancel()
    }

    @Test
    fun takePhoto_processingInProgress_countsItAndClearsWhenSaved() = runTest(dispatcher) {
        // Arrange
        val processing = CompletableDeferred<Uri>()
        val processorRef = MockReferenceHolder<PhotoProcessor>()
        val viewModel = createViewModel(processResult = processing, processorRef = processorRef)

        // Act
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()
        val countWhileProcessing = viewModel.uiState.value.processingCount
        processing.complete(mock<Uri>())
        advanceUntilIdle()

        // Assert
        assertEquals(1, countWhileProcessing)
        assertEquals(0, viewModel.uiState.value.processingCount)
        assertEquals(UiEventState.Idle, viewModel.uiState.value.eventState)
        verify(requireNotNull(processorRef.value)).process(any(), any())
    }

    @Test
    fun takePhoto_tunedSettingsSaved_processesWithThem() = runTest(dispatcher) {
        // Arrange
        val tuned = ProcessingSettings(scaleHdrBySceneLight = false)
        val processorRef = MockReferenceHolder<PhotoProcessor>()
        val viewModel = createViewModel(settings = tuned, processorRef = processorRef)

        // Act
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()

        // Assert
        verify(requireNotNull(processorRef.value)).process(any(), eq(tuned))
    }

    @Test
    fun refreshLastPhoto_galleryHasPhoto_showsItAsLastPhoto() = runTest(dispatcher) {
        // Arrange
        val latest = mock<Uri>()
        val viewModel = createViewModel(latestPhoto = latest)

        // Act
        viewModel.onEvent(UiEvent.RefreshLastPhoto)
        advanceUntilIdle()

        // Assert
        assertEquals(latest, viewModel.uiState.value.lastPhoto)
    }

    @Test
    fun refreshLastPhoto_lastPhotoDeletedMeanwhile_showsNextLatestOrNothing() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(processResult = CompletableDeferred(mock<Uri>()), latestPhoto = null)
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.RefreshLastPhoto)
        advanceUntilIdle()

        // Assert
        assertNull(viewModel.uiState.value.lastPhoto)
    }

    @Test
    fun takePhoto_saved_becomesLastPhoto() = runTest(dispatcher) {
        // Arrange
        val saved = mock<Uri>()
        val viewModel = createViewModel(processResult = CompletableDeferred(saved), latestPhoto = mock<Uri>())
        viewModel.onEvent(UiEvent.RefreshLastPhoto)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()

        // Assert
        assertEquals(saved, viewModel.uiState.value.lastPhoto)
    }

    @Test
    fun takePhoto_captureFails_showsCaptureError() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(captureError = IllegalStateException("camera closed"))

        // Act
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()

        // Assert
        assertEquals(UiEventState.Error(StringResource(R.string.error_capture)), viewModel.uiState.value.eventState)
        assertEquals(0, viewModel.uiState.value.processingCount)
    }

    @Test
    fun takePhoto_processingFails_showsProcessingErrorAndClearsCount() = runTest(dispatcher) {
        // Arrange
        val processing = CompletableDeferred<Uri>().apply { completeExceptionally(OutOfMemoryError()) }
        val viewModel = createViewModel(processResult = processing)

        // Act
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()

        // Assert
        assertEquals(UiEventState.Error(StringResource(R.string.error_processing)), viewModel.uiState.value.eventState)
        assertEquals(0, viewModel.uiState.value.processingCount)
    }

    @Test
    fun eventStateHandled_afterError_returnsToIdle() = runTest(dispatcher) {
        // Arrange
        val viewModel = createViewModel(captureError = IllegalStateException())
        viewModel.onEvent(UiEvent.TakePhoto)
        advanceUntilIdle()

        // Act
        viewModel.onEvent(UiEvent.EventStateHandled)

        // Assert
        assertEquals(UiEventState.Idle, viewModel.uiState.value.eventState)
    }

    private fun createViewModel(
        availableLenses: Set<Lens> = setOf(Lens.BACK, Lens.FRONT),
        hasFlashUnit: Boolean = true,
        captureError: Throwable? = null,
        processResult: CompletableDeferred<Uri> = CompletableDeferred(mock<Uri>()),
        latestPhoto: Uri? = null,
        settings: ProcessingSettings = ProcessingSettings(),
        sessionRef: MockReferenceHolder<CameraSession>? = null,
        processorRef: MockReferenceHolder<PhotoProcessor>? = null,
    ): CameraViewModel {
        val photo = CapturedPhoto(jpeg = byteArrayOf(1, 2, 3), rotationDegrees = 90)
        val lens = MutableStateFlow(Lens.BACK)
        val session = mock<CameraSession> {
            on { surfaceRequest } doReturn MutableStateFlow(null)
            on { this.lens } doReturn lens
            on { selectLens(any()) } doAnswer { lens.value = it.getArgument(0) }
            on { availableLenses() } doReturn availableLenses
            on { bind(any(), any()) } doReturn CameraSession.CameraCapabilities(hasFlashUnit = hasFlashUnit)
            if (captureError != null) {
                on { takePicture() } doAnswer { throw captureError }
            } else {
                on { takePicture() } doReturn photo
            }
        }
        val processor = mock<PhotoProcessor> {
            on { process(any(), any()) } doSuspendableAnswer { processResult.await() }
        }
        val gallery = mock<PhotoGallery> {
            on { latestPhoto() } doReturn latestPhoto
        }
        val settingsStore = mock<ProcessingSettingsStore> {
            on { this.settings } doReturn MutableStateFlow(settings)
        }
        sessionRef?.value = session
        processorRef?.value = processor
        return CameraViewModel(session, processor, gallery, settingsStore, mock<Logger>())
    }
}
