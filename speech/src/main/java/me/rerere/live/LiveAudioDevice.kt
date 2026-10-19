package me.rerere.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Owns audio focus, routing, echo cancellation, and both PCM devices for exactly one session. */
@Suppress("DEPRECATION")
internal class LiveAudioDevice(
    private val context: Context,
    private val inputSampleRate: Int = GptLiveProtocol.SAMPLE_RATE,
    private val outputSampleRate: Int = GptLiveProtocol.SAMPLE_RATE,
    private val onInterrupted: (String) -> Unit,
) : LiveAudioIO {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val stopped = AtomicBoolean(false)
    private val writtenFrames = AtomicLong(0)
    private val lastSpeechFrame = AtomicLong(0)
    private val playbackLock = Any()
    private val generation = AtomicLong(0)
    override val playbackGeneration: Long get() = generation.get()
    private val inputFrameBytes = inputSampleRate / 50 * 2
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    private var previousDevice: AudioDeviceInfo? = null
    private var changedCommunicationDevice = false

    @SuppressLint("MissingPermission")
    override fun start() {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required" }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < AudioManager.AUDIOFOCUS_GAIN && !stopped.get()) {
                    onInterrupted("The live conversation was interrupted by another audio session")
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        focusRequest = focus
        check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio is currently in use by another application"
        }
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousDevice = audioManager.communicationDevice
            // Respect an existing wired/Bluetooth route. Use speaker for a handheld conversation.
            if (previousDevice?.type in listOf(
                    null,
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                )
            ) {
                val devices = audioManager.availableCommunicationDevices
                val external = devices.firstOrNull {
                    it.type in listOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                    )
                }
                val preferred = external ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (preferred != null) {
                    changedCommunicationDevice = audioManager.setCommunicationDevice(preferred)
                }
            }
        } else {
            previousSpeaker = audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn &&
                !audioManager.isBluetoothScoOn
        }

        val inputBuffer = AudioRecord.getMinBufferSize(
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(inputBuffer > 0) { "This device cannot record $inputSampleRate Hz audio" }
        val input = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(inputBuffer * 2, inputFrameBytes * 8),
        ).also { recorder = it }
        check(input.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize the microphone" }
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(input.audioSessionId)
                echoCanceler?.enabled = true
            }
        }

        val outputBuffer = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(outputBuffer > 0) { "This device cannot play $outputSampleRate Hz audio" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(outputBuffer, outputSampleRate / 50 * 2 * 6))
            .build()
            .also { player = it }
        check(output.state == AudioTrack.STATE_INITIALIZED) { "Unable to initialize audio playback" }
        output.play()
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Unable to start the microphone"
        }
    }

    override suspend fun capture(onFrame: (ByteArray) -> Unit) {
        val input = checkNotNull(recorder)
        val frame = ByteArray(inputFrameBytes)
        var offset = 0
        while (!stopped.get()) {
            currentCoroutineContext().ensureActive()
            // Nonblocking I/O makes parent cancellation independent of audio-driver read latency.
            val read = input.read(frame, offset, frame.size - offset, AudioRecord.READ_NON_BLOCKING)
            if (stopped.get()) return
            if (read < 0) throw IOException("Microphone read failed ($read)")
            offset += read
            if (offset == frame.size) {
                onFrame(frame)
                offset = 0
            }
            if (read == 0) delay(5)
        }
    }

    suspend fun play(pcm: ByteArray) = play(pcm, playbackGeneration)

    override suspend fun play(pcm: ByteArray, generation: Long) {
        val output = checkNotNull(player)
        val voiced = hasVoice(pcm)
        var offset = 0
        while (offset < pcm.size && !stopped.get()) {
            currentCoroutineContext().ensureActive()
            val written = synchronized(playbackLock) {
                // A frame can already be dequeued when an interruption arrives. Check its epoch
                // under the same lock as flush/write so that it can never restart old speech.
                if (stopped.get() || generation != this.generation.get()) return
                val count = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                if (count < 0) throw IOException("Audio playback failed ($count)")
                val frames = writtenFrames.addAndGet(count.toLong() / 2)
                if (voiced && count > 0) lastSpeechFrame.set(frames)
                count
            }
            offset += written
            if (written == 0) delay(5)
        }
    }

    override suspend fun monitorSpeaking(onSpeaking: (Boolean) -> Unit) {
        var wasSpeaking = false
        while (!stopped.get()) {
            val speaking = synchronized(playbackLock) {
                val played = player?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0
                played < lastSpeechFrame.get()
            }
            if (speaking != wasSpeaking) {
                wasSpeaking = speaking
                onSpeaking(speaking)
            }
            delay(40)
        }
    }

    override fun interruptPlayback() {
        synchronized(playbackLock) {
            generation.incrementAndGet()
            player?.let { output ->
                if (stopped.get()) return
                output.pause()
                output.flush()
                writtenFrames.set(0)
                lastSpeechFrame.set(0)
                output.play()
            }
        }
    }

    override fun stop() {
        stopped.set(true)
        runCatching { recorder?.stop() }
        synchronized(playbackLock) {
            generation.incrementAndGet()
            runCatching { player?.pause() }
            runCatching { player?.flush() }
        }
    }

    override fun release() {
        stop()
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.release() }
        player = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && changedCommunicationDevice) {
            runCatching {
                val previous = previousDevice
                if (previous == null || !audioManager.setCommunicationDevice(previous)) {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
        previousSpeaker?.let { runCatching { audioManager.isSpeakerphoneOn = it } }
        previousMode?.let { runCatching { audioManager.mode = it } }
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun hasVoice(pcm: ByteArray): Boolean {
        var audibleSamples = 0
        for (index in 0 until pcm.size - 1 step 2) {
            val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
            if (abs(sample) > 300) audibleSamples++
        }
        return audibleSamples > pcm.size / 40
    }
}
