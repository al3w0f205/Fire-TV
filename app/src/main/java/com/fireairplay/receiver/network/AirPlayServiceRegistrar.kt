package com.fireairplay.receiver.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.NetworkInterface

/**
 * Handles mDNS/Bonjour service registration for AirPlay discovery.
 *
 * AirPlay 1 (RAOP) devices announce themselves with:
 * - Service type: `_raop._tcp`
 * - Instance name: `<MAC_ADDRESS>@<DEVICE_NAME>`
 * - TXT records containing device capabilities (codec support, encryption, etc.)
 *
 * When registered, Apple devices will see this Fire TV in their AirPlay output menu.
 */
class AirPlayServiceRegistrar(private val context: Context) {

    companion object {
        private const val TAG = "AirPlayRegistrar"
        private const val SERVICE_TYPE = "_raop._tcp"
        private const val DEVICE_NAME = "FireTV AirPlay"
    }

    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRegistered = false

    /**
     * Registration listener to track the state of our mDNS service.
     */
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "✅ Service registered: ${serviceInfo.serviceName}")
            isRegistered = true
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "❌ Registration failed: errorCode=$errorCode")
            isRegistered = false
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
            isRegistered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "❌ Unregistration failed: errorCode=$errorCode")
        }
    }

    /**
     * Registers the AirPlay RAOP service on the network.
     *
     * @param port the TCP port where the RTSP server is listening
     */
    fun register(port: Int) {
        // Acquire multicast lock to ensure mDNS packets are received
        acquireMulticastLock()

        val macAddress = getMacAddress()
        val prefs = context.getSharedPreferences("fire_airplay_prefs", Context.MODE_PRIVATE)
        val customName = prefs.getString("device_name", DEVICE_NAME) ?: DEVICE_NAME
        val instanceName = "${macAddress}@${customName}"

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = SERVICE_TYPE
            setPort(port)

            // AirPlay TXT records — these tell Apple devices what we support
            // Reference: https://nto.github.io/AirPlay.html#servicediscovery-airtunesservice

            // Use exact AirPort Express parameters so iOS 16 discovers it as a legacy audio receiver
            setAttribute("txtvers", "1")
            setAttribute("ch", "2")
            setAttribute("cn", "0,1")
            setAttribute("et", "0,1")
            setAttribute("md", "0,1,2")
            setAttribute("pw", "false")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("tp", "UDP")
            setAttribute("vs", "130.14")
            setAttribute("am", "AirPort4,107")
            setAttribute("fv", "76400.10")
            setAttribute("sf", "0x4")
            setAttribute("vn", "65537")
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        Log.i(TAG, "Registering RAOP service: $instanceName on port $port")
    }

    /**
     * Unregisters the mDNS service and releases resources.
     */
    fun unregister() {
        if (isRegistered) {
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering service: ${e.message}")
            }
        }
        releaseMulticastLock()
        isRegistered = false
        Log.i(TAG, "Service unregistered and resources released")
    }

    /**
     * Acquires a Wi-Fi multicast lock so that mDNS/Bonjour packets
     * are delivered to our app instead of being filtered out by Android.
     */
    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("FireAirPlay_mDNS").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.i(TAG, "Multicast lock acquired")
    }

    /**
     * Releases the multicast lock.
     */
    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Multicast lock released")
            }
        }
        multicastLock = null
    }

    /**
     * Gets the device's MAC address for the AirPlay instance name.
     * Falls back to a generated address if the real one is unavailable
     * (Android 6+ restricts MAC address access).
     */
    private fun getMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // Look for wlan0 (Wi-Fi interface)
                if (networkInterface.name.equals("wlan0", ignoreCase = true)) {
                    val mac = networkInterface.hardwareAddress
                    if (mac != null) {
                        return mac.joinToString("") { "%02X".format(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get MAC address: ${e.message}")
        }

        // Fallback: generate a deterministic pseudo-MAC from the device model
        // AirPlay strictly requires the MAC to be 12 valid HEX characters.
        val serialHash = try {
            @Suppress("DEPRECATION")
            val serial = android.os.Build.SERIAL
            if (serial != null && serial != android.os.Build.UNKNOWN) {
                serial.hashCode()
            } else {
                android.os.Build.MODEL.hashCode()
            }
        } catch (e: Exception) {
            android.os.Build.MODEL.hashCode()
        }

        val fallback = "F1BEEF${serialHash.toString(16).takeLast(6).uppercase()}"
        Log.w(TAG, "Using fallback MAC: $fallback")
        return fallback.padEnd(12, '0').take(12)
    }
}
