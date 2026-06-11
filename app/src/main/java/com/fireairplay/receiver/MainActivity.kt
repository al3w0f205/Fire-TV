package com.fireairplay.receiver

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.fireairplay.receiver.service.AirPlayService
import com.fireairplay.receiver.ui.SettingsActivity
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
    private lateinit var rootContainer: View
    private lateinit var ivBackgroundBlur: ImageView
    private lateinit var animatedGradient: AnimatedGradientView

    // UI References — Album art
    private lateinit var cardAlbumArt: CardView
    private lateinit var ivAlbumArt: ImageView

    // UI References — Right panel
    private lateinit var layoutRightPanel: View
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvArtistName: TextView
    private lateinit var tvAlbumName: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimeElapsed: TextView
    private lateinit var tvTimeRemaining: TextView

    // UI References — Status
    private lateinit var tvStatus: TextView

    // UI References — Settings
    private lateinit var btnSettings: View

    // UI References — Quality Badges
    private lateinit var layoutBadges: View
    private lateinit var badgeCodec: TextView

    // State
    private var isPlayingState = false
    private var hasPlayedEntranceAnimation = false

    // UI References — Screensaver / OLED protection
    private lateinit var contentLayout: View
    private lateinit var screensaverOverlay: View
    private lateinit var screensaverContent: View
    private lateinit var ivScreensaverArtwork: ImageView
    private lateinit var tvScreensaverTitle: TextView
    private lateinit var tvScreensaverArtist: TextView

    // Timers & Protection states
    private val pixelShiftHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val screensaverDriftHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var isScreensaverActive = false
    private val INACTIVITY_DELAY = 180000L // 3 minutes

    private val pixelShiftRunnable = object : Runnable {
        override fun run() {
            if (!isScreensaverActive) {
                // Generate random shift between -10 and 10 pixels
                val shiftX = (Math.random() * 20 - 10).toFloat()
                val shiftY = (Math.random() * 20 - 10).toFloat()

                contentLayout.animate()
                    .translationX(shiftX)
                    .translationY(shiftY)
                    .setDuration(3000)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            pixelShiftHandler.postDelayed(this, 60000)
        }
    }

    private val inactivityRunnable = Runnable {
        showScreensaver()
    }

    private val screensaverDriftRunnable = object : Runnable {
        override fun run() {
            if (isScreensaverActive) {
                val parentWidth = screensaverOverlay.width
                val parentHeight = screensaverOverlay.height
                val contentWidth = screensaverContent.width
                val contentHeight = screensaverContent.height

                if (parentWidth > contentWidth && parentHeight > contentHeight) {
                    val maxX = (parentWidth - contentWidth) / 2
                    val maxY = (parentHeight - contentHeight) / 2

                    val targetX = (Math.random() * (maxX * 2) - maxX).toFloat()
                    val targetY = (Math.random() * (maxY * 2) - maxY).toFloat()

                    screensaverContent.animate()
                        .translationX(targetX)
                        .translationY(targetY)
                        .setDuration(12000)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                screensaverDriftHandler.postDelayed(this, 12000)
            }
        }
    }

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
        rootContainer = findViewById(R.id.rootContainer)
        ivBackgroundBlur = findViewById(R.id.ivBackgroundBlur)
        animatedGradient = findViewById(R.id.animatedGradient)

        // Album art
        cardAlbumArt = findViewById(R.id.cardAlbumArt)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)

        // Right Side Panel & contents
        layoutRightPanel = findViewById(R.id.layoutRightPanel)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvArtistName = findViewById(R.id.tvArtistName)
        tvAlbumName = findViewById(R.id.tvAlbumName)
        progressBar = findViewById(R.id.progressBar)
        tvTimeElapsed = findViewById(R.id.tvTimeElapsed)
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining)

        // Status
        tvStatus = findViewById(R.id.tvStatus)

        // Settings
        btnSettings = findViewById(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Quality Badges
        layoutBadges = findViewById(R.id.layoutBadges)
        badgeCodec = findViewById(R.id.badgeCodec)

        // Screensaver / OLED protection
        contentLayout = findViewById(R.id.contentLayout)
        screensaverOverlay = findViewById(R.id.screensaverOverlay)
        screensaverContent = findViewById(R.id.screensaverContent)
        ivScreensaverArtwork = findViewById(R.id.ivScreensaverArtwork)
        tvScreensaverTitle = findViewById(R.id.tvScreensaverTitle)
        tvScreensaverArtist = findViewById(R.id.tvScreensaverArtist)
    }

    /**
     * Sets the initial state for the entrance animation:
     * album art is scaled down and transparent, glass panel is translated down.
     */
    private fun setupEntranceAnimationState() {
        cardAlbumArt.alpha = 0f
        cardAlbumArt.scaleX = 0.8f
        cardAlbumArt.scaleY = 0.8f

        layoutRightPanel.alpha = 0f
        layoutRightPanel.translationY = 80f
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

        // Right side panel: slide up + fade in (delayed slightly)
        layoutRightPanel.animate()
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

            // Update quality badges
            if (metadata.title.isNotEmpty()) {
                layoutBadges.visibility = View.VISIBLE
                badgeCodec.text = "LOSSLESS"
            } else {
                layoutBadges.visibility = View.GONE
            }

            // Update play/pause scaling animation (iOS-style album art breathing)
            if (metadata.isPlaying != isPlayingState) {
                isPlayingState = metadata.isPlaying

                // Reset inactivity timer / cancel screensaver when playing state changes
                resetInactivityTimer()

                val targetScale = if (metadata.isPlaying) 1.0f else 0.85f
                cardAlbumArt.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(400)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }

            // Update screensaver text dynamically if active
            if (isScreensaverActive) {
                if (metadata.title.isNotEmpty()) {
                    tvScreensaverTitle.text = metadata.title
                    tvScreensaverArtist.text = metadata.artist
                } else {
                    tvScreensaverTitle.text = getString(R.string.app_name)
                    tvScreensaverArtist.text = getString(R.string.status_waiting)
                }
            }
        }

        // Observe status changes
        viewModel.status.observe(this) { status ->
            tvStatus.text = status
        }

        // Observe artwork changes for the blurred background + animated gradient
        viewModel.artwork.observe(this) { bitmap ->
            val mode = getSharedPreferences("fire_airplay_prefs", Context.MODE_PRIVATE)
                .getString("background_mode", "liquid_gradient")

            if (bitmap != null) {
                // Apply very heavy blur to the full-screen background (200f for max smoothness)
                if (mode == "liquid_gradient" || mode == "blurred_artwork") {
                    BlurHelper.applyBlur(this, ivBackgroundBlur, bitmap, 200f)
                }

                // Update the animated gradient colors from album palette
                if (mode == "liquid_gradient") {
                    animatedGradient.updateColors(bitmap)
                }

                // Play entrance animation on first artwork
                playEntranceAnimation()

                // Update screensaver artwork if active
                if (isScreensaverActive) {
                    ivScreensaverArtwork.setImageBitmap(bitmap)
                    ivScreensaverArtwork.visibility = View.VISIBLE
                }
            } else {
                // Reset to defaults
                if (mode == "liquid_gradient" || mode == "blurred_artwork") {
                    ivBackgroundBlur.setImageResource(R.drawable.ic_album_placeholder)
                    BlurHelper.clearBlur(ivBackgroundBlur)
                }
                if (mode == "liquid_gradient") {
                    animatedGradient.resetColors()
                }

                if (isScreensaverActive) {
                    ivScreensaverArtwork.setImageResource(R.drawable.ic_album_placeholder)
                }
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

    override fun onResume() {
        super.onResume()
        applyBackgroundMode()
        resetInactivityTimer()
        pixelShiftHandler.postDelayed(pixelShiftRunnable, 60000)
    }

    override fun onPause() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        pixelShiftHandler.removeCallbacks(pixelShiftRunnable)
        screensaverDriftHandler.removeCallbacks(screensaverDriftRunnable)
        hideScreensaver()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Reset inactivity timer on any remote control key press
        resetInactivityTimer()
        return super.dispatchKeyEvent(event)
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (isScreensaverActive) {
            hideScreensaver()
        }
        if (!isPlayingState) {
            inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_DELAY)
        }
    }

    private fun showScreensaver() {
        if (isScreensaverActive) return
        isScreensaverActive = true

        // Populate metadata into the screensaver views
        val metadata = viewModel.metadata.value
        if (metadata != null && metadata.title.isNotEmpty()) {
            tvScreensaverTitle.text = metadata.title
            tvScreensaverArtist.text = metadata.artist
            val bitmap = viewModel.artwork.value
            if (bitmap != null) {
                ivScreensaverArtwork.setImageBitmap(bitmap)
                ivScreensaverArtwork.visibility = View.VISIBLE
            } else {
                ivScreensaverArtwork.setImageResource(R.drawable.ic_album_placeholder)
            }
        } else {
            tvScreensaverTitle.text = getString(R.string.app_name)
            tvScreensaverArtist.text = getString(R.string.status_waiting)
            ivScreensaverArtwork.setImageResource(R.drawable.ic_album_placeholder)
        }

        // Reset positions
        screensaverContent.translationX = 0f
        screensaverContent.translationY = 0f

        // Fade in overlay
        screensaverOverlay.alpha = 0f
        screensaverOverlay.visibility = View.VISIBLE
        screensaverOverlay.animate()
            .alpha(1f)
            .setDuration(1000)
            .withEndAction {
                screensaverDriftHandler.post(screensaverDriftRunnable)
            }
            .start()

        Log.i("MainActivity", "Screensaver activated")
    }

    private fun hideScreensaver() {
        if (!isScreensaverActive) return
        isScreensaverActive = false

        screensaverDriftHandler.removeCallbacks(screensaverDriftRunnable)
        screensaverContent.animate().cancel()

        // Fade out overlay
        screensaverOverlay.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                screensaverOverlay.visibility = View.GONE
            }
            .start()

        Log.i("MainActivity", "Screensaver deactivated")
    }

    /**
     * Applies the configured background theme dynamically, showing or hiding
     * layers and adjusting colors as requested.
     */
    private fun applyBackgroundMode() {
        val prefs = getSharedPreferences("fire_airplay_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("background_mode", "liquid_gradient")
        val currentArtwork = viewModel.artwork.value

        when (mode) {
            "liquid_gradient" -> {
                rootContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.background_primary))
                ivBackgroundBlur.visibility = View.VISIBLE
                animatedGradient.visibility = View.VISIBLE
                
                // If there's active artwork, make sure it's rendered
                if (currentArtwork != null) {
                    BlurHelper.applyBlur(this, ivBackgroundBlur, currentArtwork, 200f)
                    animatedGradient.updateColors(currentArtwork)
                } else {
                    ivBackgroundBlur.setImageResource(R.drawable.ic_album_placeholder)
                    BlurHelper.clearBlur(ivBackgroundBlur)
                    animatedGradient.resetColors()
                }
            }
            "blurred_artwork" -> {
                rootContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.background_primary))
                ivBackgroundBlur.visibility = View.VISIBLE
                animatedGradient.visibility = View.GONE
                
                if (currentArtwork != null) {
                    BlurHelper.applyBlur(this, ivBackgroundBlur, currentArtwork, 200f)
                } else {
                    ivBackgroundBlur.setImageResource(R.drawable.ic_album_placeholder)
                    BlurHelper.clearBlur(ivBackgroundBlur)
                }
            }
            "pure_black" -> {
                rootContainer.setBackgroundColor(android.graphics.Color.BLACK)
                ivBackgroundBlur.visibility = View.GONE
                animatedGradient.visibility = View.GONE
            }
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
