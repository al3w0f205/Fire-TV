package com.fireairplay.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Intercepts the BOOT_COMPLETED broadcast to automatically start the AirPlay service
 * when the Fire TV boots up. This ensures the receiver is always available on the network.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device booted. Starting AirPlayService automatically.")
            val serviceIntent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
