package me.rerere.live

/** Audio boundary used by the live transport, including an epoch for interrupted playback. */
internal interface LiveAudioIO {
    val playbackGeneration: Long
    fun start()
    suspend fun capture(onFrame: (ByteArray) -> Unit)
    suspend fun play(pcm: ByteArray, generation: Long)
    suspend fun monitorSpeaking(onSpeaking: (Boolean) -> Unit)
    fun interruptPlayback()
    fun stop()
    fun release()
}
