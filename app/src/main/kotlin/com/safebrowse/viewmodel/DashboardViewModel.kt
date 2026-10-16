package com.safebrowse.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safebrowse.gate.Romans6
import com.safebrowse.repository.BlocklistRepository
import com.safebrowse.settings.AppPreferences
import com.safebrowse.vpn.SafeVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    application: Application,
    private val preferences: AppPreferences,
    private val repository: BlocklistRepository
) : AndroidViewModel(application) {

    /** Straight from the service, so it can only say "on" while a tunnel exists. */
    val isVpnRunning: StateFlow<Boolean> = SafeVpnService.running

    val blockedDomainCount: StateFlow<Int> = repository.observeTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Roughly how long the gate takes, shown so the cost is known up front. */
    val gateMinutes: Int = Romans6.totalReadingMinutes()

    private val _vpnPermissionNeeded = MutableSharedFlow<Intent>()
    val vpnPermissionNeeded: SharedFlow<Intent> = _vpnPermissionNeeded.asSharedFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    // ──────────────────────────────────────────────────────────────────────────
    // Starting is free. Stopping is not.
    // ──────────────────────────────────────────────────────────────────────────

    fun startProtection() {
        val ctx = getApplication<Application>()

        // VpnService.prepare returns an Intent if the user has not yet granted
        // VPN permission, or null if they already have.
        val permIntent = VpnService.prepare(ctx)
        if (permIntent != null) {
            viewModelScope.launch { _vpnPermissionNeeded.emit(permIntent) }
            return
        }

        ctx.startForegroundService(
            Intent(ctx, SafeVpnService::class.java).apply { action = SafeVpnService.ACTION_START }
        )
    }

    fun onVpnPermissionGranted() = startProtection()

    /**
     * Called only by the gate, after all 23 verses and the word check.
     * Nothing else in the app is allowed to stop the service — otherwise the
     * dashboard toggle would be a one-tap way around the whole thing.
     */
    fun confirmStopProtection() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, SafeVpnService::class.java).apply { action = SafeVpnService.ACTION_STOP }
        )
        viewModelScope.launch {
            preferences.incrementReadings()
            _snackbarMessage.emit("Protection stopped.")
        }
    }

    fun seedDefaultBlocklistIfNeeded() {
        viewModelScope.launch { repository.seedDefaultBlocklistIfNeeded() }
    }
}
