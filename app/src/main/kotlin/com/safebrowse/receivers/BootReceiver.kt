package com.safebrowse.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.safebrowse.settings.AppPreferences
import com.safebrowse.vpn.SafeVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Brings protection back after a reboot when the user has asked for it.
 *
 * goAsync() holds the broadcast open while the DataStore read completes;
 * without it the process could be killed mid-read.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON")) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (preferences.startOnBootFlow.first()) {
                    Log.i("BootReceiver", "Restarting protection after boot")
                    context.startForegroundService(
                        Intent(context, SafeVpnService::class.java)
                            .apply { action = SafeVpnService.ACTION_START }
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
