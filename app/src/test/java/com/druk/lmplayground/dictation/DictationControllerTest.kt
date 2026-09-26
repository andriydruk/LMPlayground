package com.druk.lmplayground.dictation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State-machine behaviour of the microphone button. Recording and transcription
 * need real hardware and a real model (covered on device by
 * `AsrTranscriptionTest`); what this pins down is that the controller starts
 * inert, reports availability honestly, and ignores stray stop/cancel taps.
 *
 * Assertions read return values rather than LiveData where possible: this
 * project's Robolectric setup serves no Android resources, and postValue
 * delivery depends on the main looper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DictationControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var controller: DictationController

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        val storageRepository = StorageRepository(app, StoragePreferences(app))
        controller = DictationController(
            app,
            // No llamaCpp: the manager can still answer "is the model on disk".
            DictationManager(null, storageRepository),
            storageRepository,
            TestScope(dispatcher),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts idle with unknown model availability`() {
        assertEquals(DictationState.Idle, controller.state.value)
        assertNull("availability is unknown until checked", controller.isModelReady.value)
    }

    @Test
    fun `reports the model as missing when no models folder is configured`() = runTest(dispatcher) {
        assertFalse(controller.isModelAvailable())
    }

    @Test
    fun `stopping when not listening is a no-op`() {
        controller.stopListening()
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun `releasing without a press is a no-op`() {
        // The permission dialog can steal the gesture, so a release can arrive
        // with no press behind it.
        controller.onMicReleased()
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun `transcript events are one-shot`() {
        assertNull(controller.transcript.value)
        controller.consumeTranscript()
        assertNull(controller.transcript.value)
    }
}
