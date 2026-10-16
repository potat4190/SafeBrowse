package com.safebrowse.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.safebrowse.database.dao.BlockedDomainDao
import com.safebrowse.dns.DnsForwarder
import com.safebrowse.dns.DnsPacketBuilder
import com.safebrowse.dns.DnsParser
import com.safebrowse.settings.AppPreferences
import com.safebrowse.ui.MainActivity
import com.safebrowse.util.DomainMatcher
import com.safebrowse.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The part that actually blocks.
 *
 * A local VpnService gives us a TUN interface that carries the device's DNS
 * queries (and nothing else; see [buildVpnInterface]). We read from it, pick
 * out UDP queries to port 53, and decide:
 *
 *   blocked  -> synthesize NXDOMAIN and write it straight back
 *   allowed  -> forward to the upstream resolver on a protected socket,
 *               then write the real answer back
 *
 * No traffic leaves the device except ordinary DNS to the configured resolver,
 * or to the network's own resolver where the configured one is unreachable
 * (see [DnsForwarder]).
 * Nothing is logged off-device. Incognito windows are filtered like any other
 * traffic, because this sits below the browser entirely.
 *
 * Battery: the loop parks in poll() until a packet arrives or a stop wakes it.
 * There is no polling-with-a-timeout anywhere in this class.
 *
 * Threading: [vpnInterface], [startJob], [packetJob] and [wakeFd] are only
 * touched on the main thread, which is where onStartCommand, onRevoke and
 * onDestroy run. That is what keeps a start and a stop from interleaving.
 */
@AndroidEntryPoint
class SafeVpnService : VpnService() {

    companion object {
        private const val TAG = "SafeVpnService"

        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_DNS = "10.0.0.1"
        private const val SESSION_NAME = "SafeBrowse"
        private const val PACKET_BUFFER_SIZE = 32_768
        private const val NOTIFICATION_ID = 1

        /**
         * Well-known public resolvers, routed into the tunnel so an app that
         * hard-codes one of them is filtered too. Plain DNS to these is
         * answered like any other query; DoH and DoT to them get no reply, so
         * they fail closed instead of slipping past the blocklist. Our own
         * upstream socket is protected, so it still reaches them directly.
         */
        private val PUBLIC_RESOLVERS = listOf(
            "1.1.1.1", "1.0.0.1",                 // Cloudflare
            "8.8.8.8", "8.8.4.4",                 // Google
            "9.9.9.9", "149.112.112.112",         // Quad9
            "208.67.222.222", "208.67.220.220",   // OpenDNS
            "94.140.14.14", "94.140.15.15"        // AdGuard
        )

        const val ACTION_START = "com.safebrowse.vpn.START"
        const val ACTION_STOP = "com.safebrowse.vpn.STOP"

        private val _running = MutableStateFlow(false)

        /**
         * True only while a tunnel is actually established. Kept in memory on
         * purpose: it dies with the process, so a reboot, force-stop or crash
         * can never leave it claiming protection that is not there.
         */
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }

    @Inject lateinit var blockedDomainDao: BlockedDomainDao
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var notificationHelper: NotificationHelper

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Uncaught error in VPN service", e) }
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private var startJob: Job? = null
    private var packetJob: Job? = null
    private var blocklistJob: Job? = null
    private var dnsServerJob: Job? = null

    /** Write end of the pipe the packet loop polls; closing it wakes the loop. */
    private var wakeFd: FileDescriptor? = null

    private val domainMatcher = DomainMatcher()
    @Volatile private var dnsForwarder: DnsForwarder? = null

    /**
     * DNS servers of the real (non-VPN) networks, for [DnsForwarder]'s
     * fallback. Kept by callback because once the tunnel is up,
     * ConnectivityManager.activeNetwork is the tunnel itself.
     */
    private val underlyingDns = ConcurrentHashMap<Network, UnderlyingNetwork>()

    private class UnderlyingNetwork(val dns: List<java.net.InetAddress>, val wifi: Boolean)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private val wifi = ConcurrentHashMap<Network, Boolean>()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            wifi[network] = isWifi
            underlyingDns[network]?.let { underlyingDns[network] = UnderlyingNetwork(it.dns, isWifi) }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            underlyingDns[network] = UnderlyingNetwork(lp.dnsServers, wifi[network] ?: false)
        }

        override fun onLost(network: Network) {
            wifi.remove(network)
            underlyingDns.remove(network)
        }
    }

    /** Wi-Fi first, as that is what the system routes over when both are up. */
    private fun networkResolvers(): List<DnsForwarder.Upstream> =
        underlyingDns.entries
            .sortedByDescending { it.value.wifi }
            .flatMap { (network, info) -> info.dns.map { DnsForwarder.Upstream(it, network) } }

    override fun onCreate() {
        super.onCreate()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerNetworkCallback(request, networkCallback)
        }.onFailure { Log.e(TAG, "Could not watch networks; no DNS fallback", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (intent?.action == ACTION_STOP) {
            stopVpn(); START_NOT_STICKY
        } else {
            startVpn(); START_STICKY   // survive an OS kill
        }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        stopVpn()
        serviceScope.cancel()
    }

    /** Fired when the user revokes VPN consent, or another VPN app takes over. */
    override fun onRevoke() {
        super.onRevoke()
        Log.w(TAG, "VPN consent revoked")
        stopVpn()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    private fun startVpn() {
        // Before the early return: every startForegroundService() call must be
        // answered with startForeground(), even when the tunnel is already up.
        startForeground(NOTIFICATION_ID, notificationHelper.buildVpnNotification())

        // Both checked on the main thread, so a second start arriving while
        // the first is still loading the blocklist is turned away here.
        if (vpnInterface != null || startJob?.isActive == true) return

        startJob = serviceScope.launch {
            try {
                dnsForwarder = newForwarder(appPreferences.getDnsServer())

                // Prime the cache before opening the tunnel, so the very first
                // query is filtered rather than waved through.
                domainMatcher.load(blockedDomainDao.getDomains())

                // Back on main, where stopVpn runs: either a stop already
                // happened and we bail here, or the tunnel is recorded before
                // any stop can look for it. establish() itself can't be
                // cancelled, so this is the only safe place to call it.
                val started = withContext(Dispatchers.Main) {
                    ensureActive()
                    val (wakeRead, wakeWrite) = Os.pipe()
                    val tun = runCatching { buildVpnInterface() }
                        .onFailure { Log.e(TAG, "establish() threw", it) }
                        .getOrNull()
                    if (tun == null) {
                        Os.close(wakeRead)
                        Os.close(wakeWrite)
                        return@withContext false
                    }
                    vpnInterface = tun
                    wakeFd = wakeWrite
                    observeBlocklist()
                    observeDnsServer()
                    packetJob = serviceScope.launch { runPacketLoop(tun, wakeRead) }
                    _running.value = true
                    true
                }

                if (started) {
                    Log.i(TAG, "Filtering ${domainMatcher.size} domains")
                } else {
                    Log.e(TAG, "establish() failed — VPN consent missing?")
                    withContext(NonCancellable + Dispatchers.Main) { stopVpn() }
                }
            } catch (e: CancellationException) {
                throw e   // a stop got here first; it has already cleaned up
            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                withContext(NonCancellable + Dispatchers.Main) { stopVpn() }
            }
        }
    }

    /**
     * Keep the in-memory blocklist in step with the database.
     *
     * Without this, unblocking a domain would not take effect until the
     * service restarted, and a newly added domain would stay reachable.
     */
    private fun observeBlocklist() {
        blocklistJob?.cancel()
        blocklistJob = serviceScope.launch {
            blockedDomainDao.observeDomains().collectLatest { domains ->
                domainMatcher.load(domains)
                Log.d(TAG, "Blocklist refreshed: ${domains.size} domains")
            }
        }
    }

    /**
     * Pick up a new upstream resolver without a restart. A restart would mean
     * stopping, and stopping costs the whole gate.
     */
    private fun observeDnsServer() {
        dnsServerJob?.cancel()
        dnsServerJob = serviceScope.launch {
            appPreferences.dnsServerFlow.distinctUntilChanged().collect { ip ->
                dnsForwarder = newForwarder(ip)
                Log.d(TAG, "Upstream DNS set to $ip")
            }
        }
    }

    private fun newForwarder(upstreamIp: String) = DnsForwarder(
        upstreamIp = upstreamIp,
        protect = { socket: DatagramSocket -> protect(socket) },
        networkDns = ::networkResolvers
    )

    /** Main thread only. Safe to call at any point, including mid-start. */
    private fun stopVpn() {
        startJob?.cancel()
        startJob = null
        blocklistJob?.cancel()
        dnsServerJob?.cancel()

        // Closing the write end wakes the packet loop out of poll(). Closing
        // the TUN alone does not reliably unblock a thread parked on it.
        wakeFd?.let { runCatching { Os.close(it) } }
        wakeFd = null
        packetJob?.cancel()
        packetJob = null

        runCatching { vpnInterface?.close() }
        vpnInterface = null
        _running.value = false

        domainMatcher.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * addDnsServer points the system resolver at an address inside the
     * tunnel, and the only routes are to that address and to the well-known
     * public resolvers, so DNS queries reach us while all other traffic keeps
     * using the normal network. Routing 0.0.0.0/0 here would pull every IPv4
     * packet into a loop that only answers DNS, cutting the device off.
     */
    private fun buildVpnInterface(): ParcelFileDescriptor? {
        val configure = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Builder()
            .setSession(SESSION_NAME)
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            .setBlocking(true)
            .setConfigureIntent(configure)
        PUBLIC_RESOLVERS.forEach { builder.addRoute(it, 32) }
        return builder.establish()
    }

    // ──────────────────────────────────────────────────────────────────────
    // The loop
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun runPacketLoop(tun: ParcelFileDescriptor, wake: FileDescriptor) {
        try {
            processPackets(tun, wake)
        } finally {
            runCatching { Os.close(wake) }
            // If the loop died on its own, take the tunnel down with it rather
            // than leave every DNS lookup on the device unanswered. After a
            // normal stop vpnInterface is already gone, so this does nothing.
            withContext(NonCancellable + Dispatchers.Main) {
                if (vpnInterface === tun) {
                    Log.e(TAG, "Packet loop exited unexpectedly; stopping")
                    stopVpn()
                }
            }
        }
    }

    private suspend fun processPackets(tun: ParcelFileDescriptor, wake: FileDescriptor) = coroutineScope {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val packet = ByteArray(PACKET_BUFFER_SIZE)
        val buffer = ByteBuffer.wrap(packet)

        val tunPoll = StructPollfd().apply {
            fd = tun.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = StructPollfd().apply {
            fd = wake
            events = OsConstants.POLLIN.toShort()
        }
        val polls = arrayOf(tunPoll, wakePoll)
        val tunDead = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

        while (isActive) {
            val length = try {
                Os.poll(polls, -1)
                if (wakePoll.revents.toInt() != 0) break          // stopVpn closed the pipe
                val revents = tunPoll.revents.toInt()
                if (revents and tunDead != 0) break
                if (revents and OsConstants.POLLIN == 0) continue
                input.read(packet)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                Log.e(TAG, "poll failed: ${e.message}")
                break
            } catch (e: IOException) {
                Log.e(TAG, "TUN read failed: ${e.message}")
                break
            }
            if (length <= 0) continue

            try {
                buffer.clear()
                buffer.limit(length)

                val payload = PacketUtils.extractDnsPayload(buffer, length) ?: continue
                val query = DnsParser.parse(payload.dnsBytes, payload.dnsBytes.size)
                if (query == null) {
                    // Answer it rather than drop it: a dropped query is an app
                    // that hangs. Responses (QR set) and runts are not queries.
                    val raw = payload.dnsBytes
                    if (raw.size >= 12 && raw[2].toInt() and 0x80 == 0) {
                        synchronized(output) {
                            output.write(PacketUtils.buildResponsePacket(DnsPacketBuilder.buildFormErr(raw), payload))
                        }
                    }
                    continue
                }

                if (domainMatcher.isBlocked(query.domain)) {
                    // Deliberately not logged: logcat is readable over adb and
                    // by bug reports, and the domain is browsing history.
                    val nx = DnsPacketBuilder.buildNxDomain(query)
                    synchronized(output) {
                        output.write(PacketUtils.buildResponsePacket(nx, payload))
                    }
                } else {
                    val forwarder = dnsForwarder
                    // Forwarding blocks on the network, so it gets its own
                    // coroutine — one slow lookup must not stall every other
                    // query on the device. Its errors stay inside it: one that
                    // escaped would cancel this whole loop.
                    launch {
                        try {
                            // No resolver answered: say so with SERVFAIL. Writing
                            // nothing leaves the app waiting out every retry.
                            val answer = forwarder?.forward(payload.dnsBytes)
                                ?: DnsPacketBuilder.buildServFail(query)
                            synchronized(output) {
                                output.write(PacketUtils.buildResponsePacket(answer, payload))
                            }
                        } catch (e: Exception) {
                            // Usually the tunnel closing under an in-flight lookup.
                            Log.d(TAG, "Dropped forwarded answer: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "packet error: ${e.message}")
            }
        }
    }
}
