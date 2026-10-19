package me.rerere.rikkahub.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val AUDIO_TAG = "GptLiveAudio"

/**
 * Duplex 24 kHz, mono PCM16 little-endian audio for one live session.
 *
 * Call [start] only after the microphone/mediaPlayback foreground service acknowledges readiness.
 * Incoming audio may be queued before that point. Native reads and writes are nonblocking and are
 * serialized with teardown, so closing the microphone never races a read on a released recorder.
 */
class GptLiveAudio(
    context: Context,
    private val scope: CoroutineScope,
    private val onAudio: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 24_000
        private const val FRAME_MS = 20L
        private const val FRAME_SAMPLES = SAMPLE_RATE / 50
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val DEVICE_STALL_MS = 2_000L
        private const val MAX_CAPTURE_LAG_MS = 1_000L
        private const val RELEASE_JOIN_MS = 750L
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleMutex = Mutex()
    private val captureMutex = Mutex()
    private val playbackMutex = Mutex()
    private val released = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val releaseDone = CompletableDeferred<Unit>()
    private val playbackBuffer = GptLivePcmBuffer(SAMPLE_RATE * 2 * 4)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                fail("Voice conversation ended because another app took audio focus.")
            }
        }, handler)
        .build()

    @Volatile
    private var muted = false

    @Volatile
    private var recorderSessionId: Int? = null

    private var started = false
    private var focusHeld = false
    private var communicationModeSet = false
    private var previousSpeakerphone = false
    private var callbacksRegistered = false
    private var selectedDeviceId: Int? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var effects = emptyList<AudioEffect>()
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var microphoneDataAt = 0L

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateRoute(removedDevices.map { it.id }.toSet())
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !muted && !released.get()) {
                val ownRecording = configs.firstOrNull { it.clientAudioSessionId == recorderSessionId }
                if (ownRecording?.isClientSilenced == true) {
                    fail("Android has blocked microphone access. Check microphone privacy or another recording app.")
                }
            }
        }
    }

    suspend fun start() {
        try {
            withContext(Dispatchers.IO) {
                lifecycleMutex.withLock {
                    check(!released.get()) { "Voice audio has already been released" }
                    if (started) return@withLock
                    scope.coroutineContext.ensureActive()
                    check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        "Audio focus is unavailable. End other audio or phone calls and try again."
                    }
                    focusHeld = true
                    check(!failed.get() && !released.get()) { "Voice audio was interrupted while starting" }
                    @Suppress("DEPRECATION")
                    previousSpeakerphone = audioManager.isSpeakerphoneOn
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    communicationModeSet = true
                    selectCommunicationRoute()
                    playbackMutex.withLock {
                        track = createTrack()
                    }
                    captureMutex.withLock {
                        if (!muted) openMicrophone()
                    }
                    callbacksRegistered = true
                    audioManager.registerAudioDeviceCallback(deviceCallback, handler)
                    audioManager.registerAudioRecordingCallback(recordingCallback, handler)
                    started = true
                    // UNDISPATCHED installs both finally blocks before start() can return or its
                    // parent scope can be cancelled, including when output arrived during connect.
                    captureJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            captureLoop()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fail(e.message ?: "The microphone stopped working.")
                        } finally {
                            release()
                        }
                    }
                    playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            playbackLoop()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fail(e.message ?: "Audio playback stopped working.")
                        } finally {
                            release()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    scope.coroutineContext.ensureActive()
                    check(!released.get()) { "Voice audio was interrupted while starting" }
                }
            }
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Could not start voice audio.")
            release()
            throw e
        }
    }

    /** The four-second bound includes queued bytes; only one extra 20 ms frame can be in flight. */
    fun enqueue(pcm: ByteArray): Boolean {
        if (released.get() || failed.get()) return false
        if (pcm.isEmpty()) return true
        if (!playbackBuffer.offer(pcm)) {
            fail("Voice playback could not keep up, or the server returned invalid PCM audio.")
            return false
        }
        return !released.get()
    }

    /** Muting closes the local recorder; the capture clock continues sending silence. */
    suspend fun setMuted(muted: Boolean) {
        try {
            withContext(Dispatchers.IO) {
                lifecycleMutex.withLock {
                    if (released.get()) return@withLock
                    captureMutex.withLock capture@{
                        if (this@GptLiveAudio.muted == muted) return@capture
                        if (muted) {
                            this@GptLiveAudio.muted = true
                            closeMicrophone()
                        } else {
                            if (started) openMicrophone()
                            this@GptLiveAudio.muted = false
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Could not change microphone state.")
            throw e
        }
    }

    suspend fun release() {
        val caller = currentCoroutineContext()[Job]
        if (!released.compareAndSet(false, true)) {
            withContext(NonCancellable) {
                withTimeoutOrNull(RELEASE_JOIN_MS) { releaseDone.await() }
            }
            return
        }
        withContext(NonCancellable + Dispatchers.IO) {
            val workers = listOfNotNull(captureJob, playbackJob).filter { it !== caller }
            workers.forEach { it.cancel() }
            try {
                lifecycleMutex.withLock {
                    captureMutex.withLock { closeMicrophone() }
                    playbackMutex.withLock {
                        track?.let { output ->
                            runCatching { output.pause() }
                            runCatching { output.flush() }
                            runCatching { output.release() }
                        }
                        track = null
                        playbackBuffer.close()
                        setPlaying(false)
                    }
                    if (callbacksRegistered) {
                        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
                        callbacksRegistered = false
                    }
                    if (communicationModeSet) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            runCatching { audioManager.clearCommunicationDevice() }
                        } else {
                            @Suppress("DEPRECATION")
                            runCatching { audioManager.isSpeakerphoneOn = previousSpeakerphone }
                        }
                        // Relinquish this app's mode request; do not restore another app's global mode.
                        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
                        communicationModeSet = false
                    }
                    if (focusHeld) {
                        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
                        focusHeld = false
                    }
                    started = false
                }
            } finally {
                releaseDone.complete(Unit)
            }
            // Native I/O is NON_BLOCKING. A cancelled worker must never hold up session teardown.
            withTimeoutOrNull(RELEASE_JOIN_MS) { workers.joinAll() }
        }
    }

    private suspend fun captureLoop() {
        val samples = ShortArray(FRAME_SAMPLES)
        var nextFrameAt = SystemClock.elapsedRealtime() + FRAME_MS
        while (currentCoroutineContext().isActive && !released.get()) {
            delay((nextFrameAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            check(SystemClock.elapsedRealtime() - nextFrameAt < MAX_CAPTURE_LAG_MS) {
                "Microphone delivery stalled. Start a new voice conversation."
            }
            val pcm = ByteArray(FRAME_BYTES)
            captureMutex.withLock {
                if (released.get()) return
                if (!muted) {
                    val input = checkNotNull(recorder) { "The microphone is unavailable" }
                    check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "The microphone stopped recording"
                    }
                    val count = input.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Microphone read failed ($count)" }
                    if (count > 0) microphoneDataAt = SystemClock.elapsedRealtime()
                    check(SystemClock.elapsedRealtime() - microphoneDataAt < DEVICE_STALL_MS) {
                        "The microphone stopped delivering audio"
                    }
                    repeat(count) { index ->
                        pcm[index * 2] = samples[index].toByte()
                        pcm[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
                    }
                }
                // Keep delivery inside the same lock as mute: no captured frame can be delivered
                // after setMuted(true) has completed. A short read is padded with silence.
                if (!released.get()) onAudio(pcm)
            }
            nextFrameAt += FRAME_MS
        }
    }

    private suspend fun playbackLoop() {
        val head = GptLivePlaybackHead()
        var submittedFrames = 0L
        var playedFrames = 0L
        var progressAt = SystemClock.elapsedRealtime()
        var pending: ByteBuffer? = null
        while (currentCoroutineContext().isActive && !released.get()) {
            var wrote = 0
            playbackMutex.withLock {
                if (released.get()) return
                val output = checkNotNull(track) { "Audio playback is unavailable" }
                if (pending == null) pending = playbackBuffer.take(FRAME_BYTES)?.let(ByteBuffer::wrap)
                pending?.let { buffer ->
                    if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
                    wrote = output.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                    check(wrote >= 0 && wrote % 2 == 0) { "Audio playback failed ($wrote)" }
                    submittedFrames += wrote / 2
                    if (!buffer.hasRemaining()) pending = null
                }
                val position = head.update(output.playbackHeadPosition)
                val now = SystemClock.elapsedRealtime()
                if (position != playedFrames || submittedFrames <= position) progressAt = now
                playedFrames = position
                val buffered = submittedFrames > playedFrames
                check(!buffered || now - progressAt < DEVICE_STALL_MS) { "The audio device stopped playing" }
                setPlaying(buffered)
            }
            // One writer drains pending PCM in order, and observes when AudioTrack really drains.
            if (wrote == 0) delay(5)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone() {
        check(
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required for voice conversations." }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "This device does not support 24 kHz voice capture." }
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum * 2, FRAME_BYTES * 5))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(true)
        val input = builder.build()
        recorder = input
        check(input.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize the microphone." }
        recorderSessionId = input.audioSessionId
        effects = listOfNotNull(
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(input.audioSessionId) else null
            }.getOrNull(),
            runCatching {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(input.audioSessionId) else null
            }.getOrNull(),
        )
        effects.forEach { effect -> runCatching { effect.enabled = true } }
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start the microphone." }
        microphoneDataAt = SystemClock.elapsedRealtime()
    }

    private fun closeMicrophone() {
        recorderSessionId = null
        recorder?.let { input -> runCatching { input.stop() } }
        effects.forEach { effect -> runCatching { effect.release() } }
        effects = emptyList()
        recorder?.let { input -> runCatching { input.release() } }
        recorder = null
    }

    private fun createTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "This device does not support 24 kHz voice playback." }
        val output = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, FRAME_BYTES * 5))
            .build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            error("Could not initialize voice playback.")
        }
        return output
    }

    private fun updateRoute(removedDeviceIds: Set<Int> = emptySet()) {
        scope.launch(Dispatchers.IO) {
            try {
                lifecycleMutex.withLock {
                    if (!started || released.get()) return@withLock
                    check(selectedDeviceId !in removedDeviceIds) {
                        "The audio device was disconnected. Start a new voice conversation."
                    }
                    selectCommunicationRoute()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Could not select the voice audio device.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun selectCommunicationRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val current = audioManager.communicationDevice
            val external = devices.filter { it.type in EXTERNAL_DEVICE_TYPES }
            val preferred = external.firstOrNull { it.id == current?.id }
                ?: external.firstOrNull()
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: current
            checkNotNull(preferred) { "No voice audio device is available." }
            if (preferred.id != selectedDeviceId) {
                check(audioManager.setCommunicationDevice(preferred)) { "Could not select the voice audio device." }
                selectedDeviceId = preferred.id
            }
        } else {
            val external = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type in LEGACY_EXTERNAL_DEVICE_TYPES }
            audioManager.isSpeakerphoneOn = external == null && !audioManager.isBluetoothScoOn
            selectedDeviceId = external?.id
        }
    }

    private fun setPlaying(value: Boolean) {
        if (playing.getAndSet(value) != value) {
            runCatching { onPlayingChanged(value) }.onFailure {
                Log.w(AUDIO_TAG, "Could not deliver playback state", it)
            }
        }
    }

    private fun fail(message: String) {
        if (released.get() || !failed.compareAndSet(false, true)) return
        // Cleanup also runs if the owner has already cancelled its scope or the callback throws.
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { release() }
        runCatching { onError(message) }.onFailure {
            Log.w(AUDIO_TAG, "Could not deliver audio failure", it)
        }
    }
}

private val LEGACY_EXTERNAL_DEVICE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

private val EXTERNAL_DEVICE_TYPES = LEGACY_EXTERNAL_DEVICE_TYPES + setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
)
