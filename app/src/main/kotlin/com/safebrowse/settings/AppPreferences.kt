package com.safebrowse.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.safebrowse.dns.DnsForwarder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("app_settings")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val DNS_SERVER = stringPreferencesKey("dns_server")
        val DEFAULT_LIST_LOADED = booleanPreferencesKey("default_list_loaded")
        val READINGS = intPreferencesKey("romans6_readings")
    }

    val startOnBootFlow: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.START_ON_BOOT] ?: true }

    val dnsServerFlow: Flow<String> =
        context.dataStore.data.map { it[Keys.DNS_SERVER] ?: DnsForwarder.CLOUDFLARE_DNS }

    /** How many times Romans 6 has been read all the way through. */
    val readingsFlow: Flow<Int> =
        context.dataStore.data.map { it[Keys.READINGS] ?: 0 }

    suspend fun getDnsServer(): String = dnsServerFlow.first()
    suspend fun isDefaultListLoaded(): Boolean =
        context.dataStore.data.map { it[Keys.DEFAULT_LIST_LOADED] ?: false }.first()

    suspend fun setStartOnBoot(v: Boolean) { context.dataStore.edit { it[Keys.START_ON_BOOT] = v } }
    suspend fun setDnsServer(ip: String) { context.dataStore.edit { it[Keys.DNS_SERVER] = ip } }
    suspend fun setDefaultListLoaded(v: Boolean) { context.dataStore.edit { it[Keys.DEFAULT_LIST_LOADED] = v } }

    suspend fun incrementReadings() {
        context.dataStore.edit { it[Keys.READINGS] = (it[Keys.READINGS] ?: 0) + 1 }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides @Singleton
    fun provideAppPreferences(@ApplicationContext c: Context) = AppPreferences(c)
}
