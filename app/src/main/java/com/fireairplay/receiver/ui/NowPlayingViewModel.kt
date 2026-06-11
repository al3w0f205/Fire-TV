package com.fireairplay.receiver.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fireairplay.receiver.model.TrackMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel for the Now Playing screen.
 *
 * Acts as the bridge between the RAOP server (running on background threads)
 * and the UI (running on the main thread). Uses [LiveData] to safely post
 * metadata updates from the server's network threads.
 *
 * Lifecycle-aware: survives configuration changes and is scoped to the Activity.
 */
class NowPlayingViewModel(application: Application) : AndroidViewModel(application) {

    // =====================================================================
    // LiveData — observed by MainActivity
    // =====================================================================

    /** Current track metadata (title, artist, album, artwork, progress) */
    private val _metadata = MutableLiveData(TrackMetadata())
    val metadata: LiveData<TrackMetadata> = _metadata

    /** Connection/playback status string */
    private val _status = MutableLiveData("Esperando conexión AirPlay…")
    val status: LiveData<String> = _status

    /** Album artwork bitmap — separate LiveData for the AnimatedGradientView */
    private val _artwork = MutableLiveData<Bitmap?>()
    val artwork: LiveData<Bitmap?> = _artwork

    // =====================================================================
    // Methods called from RAOP server (background threads)
    // =====================================================================

    private var progressJob: Job? = null

    init {
        startProgressTicker()
    }

    private fun startProgressTicker() {
        progressJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val currentMeta = _metadata.value
                if (currentMeta != null && currentMeta.isPlaying && currentMeta.durationSeconds > 0) {
                    val newPosition = currentMeta.positionSeconds + 1.0
                    // Don't exceed duration
                    val clampedPosition = if (newPosition > currentMeta.durationSeconds) currentMeta.durationSeconds else newPosition
                    
                    val updatedMeta = currentMeta.copy(positionSeconds = clampedPosition)
                    _metadata.postValue(updatedMeta)
                }
            }
        }
    }

    /**
     * Called by [RaopServer] when track metadata is updated.
     * Posts the update to the main thread via LiveData.
     *
     * @param trackMetadata the updated metadata
     */
    fun updateMetadata(trackMetadata: TrackMetadata) {
        _metadata.postValue(trackMetadata)

        // Post artwork separately if it changed
        if (trackMetadata.artwork != _artwork.value) {
            _artwork.postValue(trackMetadata.artwork)
        }
    }

    /**
     * Called by [RaopServer] when the connection/playback status changes.
     *
     * @param statusText the new status string
     */
    fun updateStatus(statusText: String) {
        _status.postValue(statusText)
    }

    /**
     * Resets all metadata to defaults (called on TEARDOWN or disconnect).
     */
    fun reset() {
        _metadata.postValue(TrackMetadata())
        _status.postValue("Esperando conexión AirPlay…")
        _artwork.postValue(null)
    }
}
