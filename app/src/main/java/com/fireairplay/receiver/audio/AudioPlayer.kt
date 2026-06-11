package com.fireairplay.receiver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages audio playback using Android's [AudioTrack] API.
 *
 * Receives decoded PCM samples (16-bit, 44100 Hz, stereo) from the ALAC decoder
 * and writes them to the audio output in a dedicated coroutine with a buffered
 * channel to minimize latency while avoiding underruns.
 *
 * Architecture:
 * - A [Channel] acts as a bounded buffer between the decoder thread and the playback thread.
 * - The decoder posts PCM ShortArrays to the channel.
 * - A playback coroutine continuously drains the channel and writes to AudioTrack.
 * - This decouples decode speed from playback timing for smoother audio.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Buffer capacity: ~100 frames of 352 stereo samples ≈ ~0.8 seconds of audio
        private const val BUFFER_CAPACITY = 100
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val pcmChannel = Channel<ShortArray>(BUFFER_CAPACITY)
    private val isPlaying = AtomicBoolean(false)
    private val isPrimed = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    /**
     * Initializes the AudioTrack with optimal settings for low-latency playback.
     * Must be called before [start].
     */
    fun initialize() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Use 2x minimum buffer for stability on Fire TV Sticks
        val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.i(TAG, "AudioTrack initialized: bufferSize=$bufferSize, minBuffer=$minBufferSize")
    }

    /**
     * Starts the audio playback pipeline.
     * Launches a coroutine that continuously reads PCM data from the internal
     * channel and writes it to the AudioTrack.
     */
    fun start() {
        if (isPlaying.getAndSet(true)) {
            Log.w(TAG, "Already playing")
            return
        }

        val track = audioTrack ?: run {
            Log.e(TAG, "AudioTrack not initialized")
            isPlaying.set(false)
            return
        }

        isPrimed.set(false)

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playbackJob = scope?.launch {
            Log.i(TAG, "Playback coroutine started")
            try {
                var lastWriteTime = 0L
                var primedCount = 0
                while (isActive && isPlaying.get()) {
                    val pcmData = pcmChannel.receive()

                    val now = System.currentTimeMillis()
                    if (isPrimed.get() && lastWriteTime != 0L && now - lastWriteTime > 150) {
                        track.pause()
                        track.flush()
                        isPrimed.set(false)
                        primedCount = 0
                        Log.i(TAG, "Audio underrun detected (gap of ${now - lastWriteTime}ms), re-buffering...")
                    }

                    track.write(pcmData, 0, pcmData.size)
                    lastWriteTime = System.currentTimeMillis()

                    if (!isPrimed.get()) {
                        primedCount++
                        if (primedCount >= 6) {
                            track.play()
                            isPrimed.set(true)
                            primedCount = 0
                            Log.i(TAG, "Audio primed and playing")
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Playback coroutine cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Playback error: ${e.message}")
            } finally {
                Log.i(TAG, "Playback coroutine stopped")
            }
        }
    }

    /**
     * Enqueues decoded PCM samples for playback.
     * This is called from the RAOP server's decode thread.
     * If the buffer is full, the oldest frame will be discarded (backpressure).
     *
     * @param pcmSamples interleaved 16-bit PCM samples (L, R, L, R, ...)
     */
    suspend fun enqueuePcm(pcmSamples: ShortArray) {
        if (!isPlaying.get()) return

        // Use trySend for non-blocking behavior; drop if buffer is full
        val result = pcmChannel.trySend(pcmSamples)
        if (result.isFailure) {
            Log.w(TAG, "PCM buffer full, dropping frame")
        }
    }

    /**
     * Blocking version of [enqueuePcm] for use from non-coroutine contexts.
     */
    fun enqueuePcmBlocking(pcmSamples: ShortArray) {
        if (!isPlaying.get()) return
        pcmChannel.trySend(pcmSamples)
    }

    /**
     * Sets the playback volume.
     * @param volume Linear volume scale from 0.0 (mute) to 1.0 (max)
     */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume)
    }

    /**
     * Flushes the audio pipeline. Called when receiving FLUSH from the AirPlay client
     * (e.g., when seeking or changing tracks).
     */
    fun flush() {
        // Drain the channel
        while (true) {
            val result = pcmChannel.tryReceive()
            if (result.isFailure) break
        }

        // Flush the AudioTrack buffer
        audioTrack?.let { track ->
            track.pause()
            track.flush()
        }
        isPrimed.set(false)
        Log.i(TAG, "Audio flushed")
    }

    /**
     * Stops playback and releases all resources.
     */
    fun stop() {
        isPlaying.set(false)
        isPrimed.set(false)
        playbackJob?.cancel()
        playbackJob = null
        scope?.cancel()
        scope = null

        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (e: IllegalStateException) {
                // Already stopped
            }
        }

        // Drain remaining samples
        while (true) {
            val result = pcmChannel.tryReceive()
            if (result.isFailure) break
        }

        Log.i(TAG, "Playback stopped")
    }

    /**
     * Releases the AudioTrack resources completely.
     * Call this when the service/activity is being destroyed.
     */
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        pcmChannel.close()
        Log.i(TAG, "AudioPlayer released")
    }

    /** Returns whether audio is currently playing. */
    fun isCurrentlyPlaying(): Boolean = isPlaying.get()
}
