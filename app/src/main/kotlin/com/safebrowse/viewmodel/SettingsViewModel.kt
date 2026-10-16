package com.safebrowse.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebrowse.dns.DnsForwarder
import com.safebrowse.repository.BlocklistRepository
import com.safebrowse.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val repository: BlocklistRepository
) : ViewModel() {

    val startOnBoot: StateFlow<Boolean> = preferences.startOnBootFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val dnsServer: StateFlow<String> = preferences.dnsServerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DnsForwarder.CLOUDFLARE_DNS)

    val readings: StateFlow<Int> = preferences.readingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage.asSharedFlow()

    fun setStartOnBoot(v: Boolean) { viewModelScope.launch { preferences.setStartOnBoot(v) } }

    fun setDnsServer(ip: String) {
        viewModelScope.launch {
            preferences.setDnsServer(ip)
            _uiMessage.emit("DNS set to $ip.")
        }
    }

    fun importBlocklist(text: String) {
        viewModelScope.launch {
            _uiMessage.emit("Imported ${repository.importDomains(text)} domains.")
        }
    }
}
