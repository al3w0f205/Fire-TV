package com.fireairplay.receiver.server

import android.util.Log
import com.github.serezhka.jap2server.AirPlayServer
import com.github.serezhka.jap2server.AirplayDataConsumer
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Wrapper for the external java-airplay library to handle Screen Mirroring (H.264)
 * and FairPlay decryption independently of our existing audio RaopServer.
 */
class AirPlayMirroringServer(
    private val serverName: String,
    private val onVideoFrameReceived: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "AirPlayMirroringServer"
        const val AIRPLAY_PORT = 7100 // Standard Mirroring Port
        const val AIRTUNES_PORT = 5001 // Standard Audio pairing port
    }

    private var airPlayServer: AirPlayServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (airPlayServer != null) return

        try {
            val consumer = object : AirplayDataConsumer {
                override fun onVideo(videoBytes: ByteArray) {
                    // Pass the decrypted H.264 NAL units to our ExoPlayer or MediaCodec surface
                    onVideoFrameReceived(videoBytes)
                }

                override fun onAudio(audioBytes: ByteArray) {
                    // We can ignore this or pass it to our AudioTrack if the library intercepts it
                }
            }

            airPlayServer = AirPlayServer(serverName, AIRPLAY_PORT, AIRTUNES_PORT, consumer)
            
            // Start the server asynchronously since it blocks
            scope.launch {
                try {
                    airPlayServer?.start()
                    Log.i(TAG, "Screen Mirroring Server started successfully on port $AIRPLAY_PORT")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start mirroring server: ${e.message}")
                }
            }
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "java-airplay library not found or incompatible. Mirroring disabled.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring mirroring server", e)
        }
    }

    fun stop() {
        try {
            airPlayServer?.stop()
            Log.i(TAG, "Screen Mirroring Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mirroring server: ${e.message}")
        } finally {
            airPlayServer = null
        }
    }
}
