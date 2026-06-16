package com.fireairplay.receiver.server

import android.util.Log

/**
 * JNI Wrapper for interacting with C++ AirPlay libraries (e.g., UxPlay decryption core).
 * This class abstracts the heavy cryptographic operations required for Screen Mirroring.
 */
class AirPlayNativeWrapper {

    companion object {
        private const val TAG = "AirPlayNativeWrapper"
        
        // Used to load the 'fireairplay_native' library on application startup.
        init {
            try {
                System.loadLibrary("fireairplay_native")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    /**
     * A native method that is implemented by the 'fireairplay_native' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    /**
     * Initializes the native cryptographic keys (Ed25519/Curve25519) for AirPlay Mirroring.
     */
    external fun initNative()

    // Future methods to add:
    // external fun decryptVideoPacket(input: ByteArray, output: ByteArray)
    // external fun processPairSetup(requestPayload: ByteArray): ByteArray
}
