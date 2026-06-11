package com.fireairplay.receiver.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.fireairplay.receiver.MainActivity
import com.fireairplay.receiver.R
import com.fireairplay.receiver.audio.AlacDecoder
import com.fireairplay.receiver.audio.AudioPlayer
import com.fireairplay.receiver.model.TrackMetadata
import com.fireairplay.receiver.network.AirPlayServiceRegistrar
import com.fireairplay.receiver.server.RaopServer
import kotlinx.coroutines.*

/**
 * Foreground service that manages the AirPlay receiver lifecycle.
 *
 * Running as a foreground service ensures that:
 * 1. Android/Fire OS doesn't kill the process while streaming audio
 * 2. A persistent notification informs the user that AirPlay is active
 * 3. WakeLock keeps the CPU alive for continuous audio decoding
 *
 * The service owns the core components:
 * - [RaopServer] — RTSP protocol handler
 * - [AlacDecoder] — audio decoder
 * - [AudioPlayer] — audio output
 * - [AirPlayServiceRegistrar] — mDNS announcement
 */
class AirPlayService : Service() {

    companion object {
        private const val TAG = "AirPlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "airplay_channel"

        // Static reference for the ViewModel to connect to
        // (In a production app, use dependency injection like Hilt)
        var raopServer: RaopServer? = null
            private set

        var onMetadataCallback: ((TrackMetadata) -> Unit)? = null
        var onStatusCallback: ((String) -> Unit)? = null
    }

    private lateinit var audioPlayer: AudioPlayer
    private lateinit var alacDecoder: AlacDecoder
    private lateinit var serviceRegistrar: AirPlayServiceRegistrar
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMdnsRegistered = false

    private fun registerMdns() {
        val port = raopServer?.rtspPort ?: RaopServer.DEFAULT_RTSP_PORT
        serviceRegistrar.unregister()
        serviceRegistrar.register(port)
        isMdnsRegistered = true
        Log.i(TAG, "mDNS service registered on port $port")
    }

    private fun unregisterMdns() {
        if (isMdnsRegistered) {
            serviceRegistrar.unregister()
            isMdnsRegistered = false
            Log.i(TAG, "mDNS service unregistered")
        }
    }

    private fun setupNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var job: Job? = null

            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available: $network")
                job?.cancel()
                job = serviceScope.launch {
                    delay(1000)
                    registerMdns()
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost: $network")
                job?.cancel()
                job = serviceScope.launch {
                    unregisterMdns()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.d(TAG, "Network link properties changed: $network")
                job?.cancel()
                job = serviceScope.launch {
                    delay(1000)
                    registerMdns()
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "Network callback registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        // Initialize components
        alacDecoder = AlacDecoder().apply { initializeDefault() }
        audioPlayer = AudioPlayer()
        serviceRegistrar = AirPlayServiceRegistrar(this)

        // Create the RAOP server
        raopServer = RaopServer(audioPlayer, alacDecoder).apply {
            onMetadataUpdate = { metadata ->
                onMetadataCallback?.invoke(metadata)
            }
            onStatusUpdate = { status ->
                onStatusCallback?.invoke(status)
            }
        }

        // Setup connectivity monitoring
        setupNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")

        // Start as foreground service with notification
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Acquire wake lock to keep CPU active during streaming
        acquireWakeLock()

        // Start the RAOP server
        raopServer?.start()

        // Trigger initial mDNS registration immediately if network is active
        registerMdns()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")

        // Unregister network callback
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        networkCallback = null
        connectivityManager = null

        // Cancel scope
        serviceScope.cancel()

        // Unregister mDNS
        unregisterMdns()

        // Stop server
        raopServer?.stop()
        raopServer = null

        // Release audio
        audioPlayer.release()

        // Release wake lock
        releaseWakeLock()

        onMetadataCallback = null
        onStatusCallback = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Creates the notification channel (required for API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent foreground notification.
     */
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Acquires a partial wake lock to keep the CPU active during audio streaming.
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FireAirPlay::AudioWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
        Log.i(TAG, "Wake lock acquired")
    }

    /**
     * Releases the wake lock.
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}
