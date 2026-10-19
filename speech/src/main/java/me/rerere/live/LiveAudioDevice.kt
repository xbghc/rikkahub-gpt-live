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
    private val onInterrupted: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val stopped = AtomicBoolean(false)
    private val writtenFrames = AtomicLong(0)
    private val lastSpeechFrame = AtomicLong(0)
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    private var previousDevice: AudioDeviceInfo? = null
    private var changedCommunicationDevice = false

    @SuppressLint("MissingPermission")
    fun start() {
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
            GptLiveProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(inputBuffer > 0) { "This device cannot record 24 kHz audio" }
        val input = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            GptLiveProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(inputBuffer * 2, GptLiveProtocol.FRAME_BYTES * 8),
        ).also { recorder = it }
        check(input.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize the microphone" }
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(input.audioSessionId)
                echoCanceler?.enabled = true
            }
        }

        val outputBuffer = AudioTrack.getMinBufferSize(
            GptLiveProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(outputBuffer > 0) { "This device cannot play 24 kHz audio" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(GptLiveProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(outputBuffer, GptLiveProtocol.FRAME_BYTES * 6))
            .build()
            .also { player = it }
        check(output.state == AudioTrack.STATE_INITIALIZED) { "Unable to initialize audio playback" }
        output.play()
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Unable to start the microphone"
        }
    }

    suspend fun capture(onFrame: (ByteArray) -> Unit) {
        val input = checkNotNull(recorder)
        val frame = ByteArray(GptLiveProtocol.FRAME_BYTES)
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

    suspend fun play(pcm: ByteArray) {
        val output = checkNotNull(player)
        val voiced = hasVoice(pcm)
        var offset = 0
        while (offset < pcm.size && !stopped.get()) {
            currentCoroutineContext().ensureActive()
            val written = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (stopped.get()) return
            if (written < 0) throw IOException("Audio playback failed ($written)")
            offset += written
            val frames = writtenFrames.addAndGet(written.toLong() / 2)
            if (voiced && written > 0) lastSpeechFrame.set(frames)
            if (written == 0) delay(5)
        }
    }

    suspend fun monitorSpeaking(onSpeaking: (Boolean) -> Unit) {
        var wasSpeaking = false
        while (!stopped.get()) {
            val played = player?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0
            val speaking = played < lastSpeechFrame.get()
            if (speaking != wasSpeaking) {
                wasSpeaking = speaking
                onSpeaking(speaking)
            }
            delay(40)
        }
    }

    fun stop() {
        stopped.set(true)
        runCatching { recorder?.stop() }
        runCatching { player?.pause() }
        runCatching { player?.flush() }
    }

    fun release() {
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
