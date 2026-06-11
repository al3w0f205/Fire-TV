package com.fireairplay.receiver

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.ViewModelProvider
import com.fireairplay.receiver.service.AirPlayService
import com.fireairplay.receiver.ui.AnimatedGradientView
import com.fireairplay.receiver.ui.BlurHelper
import com.fireairplay.receiver.ui.GlassPanelLayout
import com.fireairplay.receiver.ui.NowPlayingViewModel

/**
 * Main activity — the "Now Playing" screen displayed on the TV.
 *
 * Responsibilities:
 * 1. Starts the [AirPlayService] foreground service
 * 2. Observes [NowPlayingViewModel] for metadata updates from the RAOP server
 * 3. Updates the UI: blurred background, animated gradient, album art, glass panel
 * 4. Keeps the screen on during the entire session (no screensaver)
 *
 * This activity is launched as a LEANBACK_LAUNCHER for Fire TV / Android TV,
 * and also as a standard LAUNCHER for testing on phones/tablets.
 */
class MainActivity : AppCompatActivity() {

    // ViewModel
    private lateinit var viewModel: NowPlayingViewModel

    // UI References — Background layers
    private lateinit var ivBackgroundBlur: ImageView
    private lateinit var animatedGradient: AnimatedGradientView

    // UI References — Album art
    private lateinit var cardAlbumArt: CardView
    private lateinit var ivAlbumArt: ImageView

    // UI References — Glass panel
    private lateinit var glassPanel: GlassPanelLayout
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvArtistName: TextView
    private lateinit var tvAlbumName: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimeElapsed: TextView
    private lateinit var tvTimeRemaining: TextView

    // UI References — Status
    private lateinit var tvStatus: TextView

    // State
    private var isPlayingState = false
    private var hasPlayedEntranceAnimation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full immersive mode — hide system UI
        setupImmersiveMode()

        setContentView(R.layout.activity_main)

        // Keep screen on while app is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize UI references
        bindViews()

        // Set initial state for entrance animation
        setupEntranceAnimationState()

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]

        // Observe LiveData
        observeViewModel()

        // Start the AirPlay service
        startAirPlayService()
    }

    /**
     * Binds all view references from the layout.
     */
    private fun bindViews() {
        // Background layers
        ivBackgroundBlur = findViewById(R.id.ivBackgroundBlur)
        animatedGradient = findViewById(R.id.animatedGradient)

        // Album art
        cardAlbumArt = findViewById(R.id.cardAlbumArt)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)

        // Glass panel & contents
        glassPanel = findViewById(R.id.glassPanel)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvArtistName = findViewById(R.id.tvArtistName)
        tvAlbumName = findViewById(R.id.tvAlbumName)
        progressBar = findViewById(R.id.progressBar)
        tvTimeElapsed = findViewById(R.id.tvTimeElapsed)
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining)

        // Status
        tvStatus = findViewById(R.id.tvStatus)
    }

    /**
     * Sets the initial state for the entrance animation:
     * album art is scaled down and transparent, glass panel is translated down.
     */
    private fun setupEntranceAnimationState() {
        cardAlbumArt.alpha = 0f
        cardAlbumArt.scaleX = 0.8f
        cardAlbumArt.scaleY = 0.8f

        glassPanel.alpha = 0f
        glassPanel.translationY = 80f
    }

    /**
     * Plays the entrance animation: album art fades/scales in,
     * glass panel slides up with a spring effect.
     */
    private fun playEntranceAnimation() {
        if (hasPlayedEntranceAnimation) return
        hasPlayedEntranceAnimation = true

        // Album art: fade + scale in
        cardAlbumArt.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()

        // Glass panel: slide up + fade in (delayed slightly)
        glassPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /**
     * Observes ViewModel LiveData and updates the UI accordingly.
     */
    private fun observeViewModel() {
        // Observe metadata changes (title, artist, album, progress, etc.)
        viewModel.metadata.observe(this) { metadata ->
            // Update track info
            tvTrackTitle.text = metadata.title.ifEmpty {
                getString(R.string.track_title_placeholder)
            }
            tvArtistName.text = metadata.artist.ifEmpty {
                getString(R.string.track_artist_placeholder)
            }

            // Update album name (show only if we have one)
            if (metadata.album.isNotEmpty()) {
                tvAlbumName.text = metadata.album
                tvAlbumName.visibility = View.VISIBLE
            } else {
                tvAlbumName.visibility = View.GONE
            }

            // Update album art
            if (metadata.artwork != null) {
                ivAlbumArt.setImageBitmap(metadata.artwork)
                ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                ivAlbumArt.setImageResource(R.drawable.ic_album_placeholder)
                ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            // Update progress bar
            progressBar.progress = (metadata.progressFraction * 1000).toInt()

            // Hide status text if we have actual metadata
            if (metadata.title.isNotEmpty()) {
                tvStatus.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { tvStatus.visibility = View.INVISIBLE }
                    .start()
            }

            // Update time labels
            tvTimeElapsed.text = metadata.elapsedFormatted
            tvTimeRemaining.text = metadata.remainingFormatted

            // Update play/pause scaling animation (iOS-style album art breathing)
            if (metadata.isPlaying != isPlayingState) {
                isPlayingState = metadata.isPlaying

                val targetScale = if (metadata.isPlaying) 1.0f else 0.85f
                cardAlbumArt.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(400)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
        }

        // Observe status changes
        viewModel.status.observe(this) { status ->
            tvStatus.text = status
        }

        // Observe artwork changes for the blurred background + animated gradient
        viewModel.artwork.observe(this) { bitmap ->
            if (bitmap != null) {
                // Apply very heavy blur to the full-screen background (200f for max smoothness)
                BlurHelper.applyBlur(this, ivBackgroundBlur, bitmap, 200f)

                // Update the animated gradient colors from album palette
                animatedGradient.updateColors(bitmap)

                // Update the glass panel's frosted backdrop
                glassPanel.updateBackdrop(bitmap)

                // Play entrance animation on first artwork
                playEntranceAnimation()
            } else {
                // Reset to defaults
                ivBackgroundBlur.setImageResource(R.drawable.ic_album_placeholder)
                BlurHelper.clearBlur(ivBackgroundBlur)
                animatedGradient.resetColors()
                glassPanel.updateBackdrop(null)
            }
        }
    }

    /**
     * Starts the AirPlay foreground service and connects its callbacks
     * to the ViewModel.
     */
    private fun startAirPlayService() {
        // Set up callbacks before starting the service
        AirPlayService.onMetadataCallback = { metadata ->
            viewModel.updateMetadata(metadata)
        }
        AirPlayService.onStatusCallback = { status ->
            viewModel.updateStatus(status)
        }

        // Start the foreground service
        val serviceIntent = Intent(this, AirPlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * Sets up full immersive mode to hide status bar, navigation bar,
     * and keep the entire screen for our content.
     */
    private fun setupImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersiveMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop the service on destroy — it should keep running
        // Only clear the callbacks to avoid leaking the activity
        AirPlayService.onMetadataCallback = null
        AirPlayService.onStatusCallback = null
    }
}
