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

        // Buffer capacity: 50 frames (~400ms). Absorbs Wi-Fi jitter but discards the 2s AirPlay burst for real low delay.
        private const val BUFFER_CAPACITY = 50
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val pcmChannel = Channel<ShortArray>(BUFFER_CAPACITY)
    private val isPlaying = AtomicBoolean(false)
    private val isPrimed = AtomicBoolean(false)
    private val needsFadeIn = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    /**
     * Initializes the AudioTrack with optimal settings for low-latency playback.
     * Must be called before [start].
     */
    fun initialize() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Use 4x minimum buffer or at least 256KB for stability and buffering on TV platforms
        val bufferSize = (minBufferSize * 4).coerceAtLeast(256 * 1024)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
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
                var primedCount = 0
                while (isActive && isPlaying.get()) {
                    val pcmData = pcmChannel.receive()

                    track.write(pcmData, 0, pcmData.size)

                    if (!isPrimed.get()) {
                        primedCount++
                        if (primedCount >= 2) {
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

        var samplesToQueue = pcmSamples
        if (needsFadeIn.getAndSet(false)) {
            samplesToQueue = applyFadeIn(pcmSamples)
        }

        // Use trySend to NEVER block the UDP receiver. If full, we drop a frame to maintain low delay.
        val result = pcmChannel.trySend(samplesToQueue)
        if (result.isFailure) {
            needsFadeIn.set(true) // Ensure next frame fades in to avoid pop
        }
    }

    /**
     * Applies a quick fade-in to the frame to prevent audio pops after a gap.
     */
    private fun applyFadeIn(samples: ShortArray): ShortArray {
        val out = samples.clone()
        val fadeSamples = out.size / 2 // fade over half the frame
        for (i in 0 until fadeSamples) {
            val multiplier = i.toFloat() / fadeSamples
            // Interleaved stereo: Left
            out[i * 2] = (out[i * 2] * multiplier).toInt().toShort()
            // Right
            out[i * 2 + 1] = (out[i * 2 + 1] * multiplier).toInt().toShort()
        }
        return out
    }

    /**
     * Notifies the player that a gap occurred in the network stream so it can fade in the next frame.
     */
    fun notifyGap() {
        needsFadeIn.set(true)
    }

    /**
     * Blocking version of [enqueuePcm] for use from non-coroutine contexts.
     */
    fun enqueuePcmBlocking(pcmSamples: ShortArray) {
        if (!isPlaying.get()) return

        var samplesToQueue = pcmSamples
        if (needsFadeIn.getAndSet(false)) {
            samplesToQueue = applyFadeIn(pcmSamples)
        }

        val result = pcmChannel.trySend(samplesToQueue)
        if (result.isFailure) {
            needsFadeIn.set(true)
        }
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

        // Hard-flush: Pause and flush hardware buffer to eliminate old audio residue
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                isPrimed.set(false)
                needsFadeIn.set(true)
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
            }
        }
        
        Log.i(TAG, "Audio flushed (hard)")
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
