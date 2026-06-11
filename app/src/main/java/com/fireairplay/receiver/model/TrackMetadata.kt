package com.fireairplay.receiver.model

import android.graphics.Bitmap

/**
 * Represents the metadata of the currently playing track received from AirPlay.
 * This data class is immutable — new instances are created for each metadata update
 * to safely post to LiveData from background threads.
 */
data class TrackMetadata(
    /** Track title (DAAP: dmap.itemname / minm) */
    val title: String = "",
    /** Artist name (DAAP: daap.songartist / asar) */
    val artist: String = "",
    /** Album name (DAAP: daap.songalbum / asal) */
    val album: String = "",
    /** Album artwork bitmap decoded from the image/jpeg or image/png payload */
    val artwork: Bitmap? = null,
    /** Track duration in seconds (from progress SET_PARAMETER) */
    val durationSeconds: Double = 0.0,
    /** Current playback position in seconds (from progress SET_PARAMETER) */
    val positionSeconds: Double = 0.0,
    /** Whether audio is currently being streamed */
    val isPlaying: Boolean = false,
    /** Audio sample rate in Hz (e.g., 44100) */
    val sampleRate: Int = 44100,
    /** Audio sample size in bits (e.g., 16) */
    val sampleSize: Int = 16,
    /** Number of audio channels (e.g., 2 for Stereo) */
    val numChannels: Int = 2
) {
    /**
     * Returns a human-readable time string (e.g., "3:45") from seconds.
     */
    companion object {
        fun formatTime(totalSeconds: Double): String {
            val minutes = (totalSeconds / 60).toInt()
            val seconds = (totalSeconds % 60).toInt()
            return "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    /** Formatted elapsed time */
    val elapsedFormatted: String get() = formatTime(positionSeconds)

    /** Formatted remaining time (negative) */
    val remainingFormatted: String
        get() {
            val remaining = durationSeconds - positionSeconds
            return if (remaining > 0) "-${formatTime(remaining)}" else "-0:00"
        }

    /** Progress as a fraction 0.0 to 1.0 */
    val progressFraction: Float
        get() = if (durationSeconds > 0) {
            (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}
