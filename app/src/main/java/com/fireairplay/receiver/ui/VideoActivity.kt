package com.fireairplay.receiver.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fireairplay.receiver.R

/**
 * Activity dedicated to playing AirPlay Video casts (URLs) using ExoPlayer.
 * This will overlay the main music UI when a video starts playing.
 */
class VideoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_START_POSITION = "extra_start_position"
        private const val TAG = "VideoActivity"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var videoUrl: String? = null
    private var startPosition: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Immersive full screen mode
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )

        setContentView(R.layout.activity_video)
        playerView = findViewById(R.id.playerView)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        startPosition = intent.getFloatExtra(EXTRA_START_POSITION, 0f)

        Log.i(TAG, "VideoActivity launched with URL: $videoUrl")
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // If a new video is cast while we are already playing one
        intent?.let {
            videoUrl = it.getStringExtra(EXTRA_VIDEO_URL)
            startPosition = it.getFloatExtra(EXTRA_START_POSITION, 0f)
            Log.i(TAG, "New intent received with URL: $videoUrl")
            releasePlayer()
            initializePlayer()
        }
    }

    private fun initializePlayer() {
        if (videoUrl == null) {
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(videoUrl!!)
            exoPlayer.setMediaItem(mediaItem)
            
            if (startPosition > 0) {
                // startPosition is usually in fractional seconds in AirPlay (0.0 to 1.0)
                // We need to parse it or use absolute time depending on protocol
                // For now, assume it's seconds
                exoPlayer.seekTo((startPosition * 1000).toLong())
            }

            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
