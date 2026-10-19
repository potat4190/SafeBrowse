package com.safebrowse.dns

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sends allowed queries on to a real resolver.
 *
 * The socket must be handed to VpnService.protect() before use. Without that,
 * the query is routed back into our own tunnel and loops forever. That is what
 * the [protect] lambda is for, and why a failure to protect aborts the send.
 *
 * The configured resolver is tried first. Many managed networks (campus,
 * hotel, corporate Wi-Fi) drop DNS to anything but their own resolver, and
 * there every query to 1.1.1.1 just times out. So when it fails, the query
 * goes to the underlying network's own resolvers ([networkDns]) instead, and
 * those stay first. Every [FALLBACK_HOLD_MS] the configured one is probed
 * again on a background thread, never in the path of a real lookup: a probe
 * that times out must not stall the queries waiting behind it.
 * Filtering is unaffected: the blocklist is applied before forwarding.
 *
 * Answers are kept in a [DnsCache] for their TTL, so a repeat lookup costs no
 * network round trip.
 */
class DnsForwarder(
    private val upstreamIp: String = CLOUDFLARE_DNS,
    private val protect: (DatagramSocket) -> Boolean,
    private val networkDns: () -> List<Upstream> = { emptyList() },
    port: Int = DNS_PORT,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /** A resolver, and the network to reach it on (null = default). */
    data class Upstream(
        val address: InetAddress,
        val network: Network? = null,
        val port: Int = DNS_PORT
    )

    companion object {
        private const val TAG = "DnsForwarder"
        const val CLOUDFLARE_DNS = "1.1.1.1"
        const val GOOGLE_DNS = "8.8.8.8"
        const val QUAD9_DNS = "9.9.9.9"
        private const val DNS_PORT = 53
        // Per attempt. Android's resolver gives up on us after ~5 s, so a
        // dead configured resolver must leave time to try the network's own.
        private const val TIMEOUT_MS = 1_500
        private const val FALLBACK_HOLD_MS = 5 * 60_000L
        // Not 512: Android's resolver advertises EDNS0, so answers can be
        // larger, and a DatagramPacket silently truncates what does not fit.
        private const val MAX_DNS_PACKET = 4096
    }

    private val configured = Upstream(InetAddress.getByName(upstreamIp), port = port)

    /**
     * 0 while the configured resolver is in use. Otherwise the network's own
     * resolvers go first, and this is when the configured one is next probed.
     */
    @Volatile private var fallbackUntil = 0L
    private val probing = AtomicBoolean(false)

    private val cache = DnsCache(clock)

    /** Blocking call — must run on an IO dispatcher. Returns null if every resolver failed. */
    fun forward(rawQuery: ByteArray): ByteArray? {
        cache.get(rawQuery)?.let { return it }
        return resolve(rawQuery)?.also { cache.put(rawQuery, it) }
    }

    /** Drop cached answers, e.g. on a network change, where they may no longer hold. */
    fun clearCache() = cache.clear()

    private fun resolve(rawQuery: ByteArray): ByteArray? {
        val network = networkDns().filter {
            it.address != configured.address || it.port != configured.port
        }
        val onFallback = fallbackUntil != 0L && network.isNotEmpty()
        if (onFallback && clock() >= fallbackUntil) probeConfigured(rawQuery)
        val order = if (onFallback) network + configured else listOf(configured) + network

        for (upstream in order) {
            val answer = query(upstream, rawQuery) ?: continue
            if (upstream === configured) {
                fallbackUntil = 0L
            } else if (!onFallback) {
                Log.w(TAG, "Configured resolver unreachable; using the network's resolver")
                fallbackUntil = clock() + FALLBACK_HOLD_MS
            }
            return answer
        }
        return null
    }

    /** Off the request path, at most one at a time; switches back if it answers. */
    private fun probeConfigured(rawQuery: ByteArray) {
        if (!probing.compareAndSet(false, true)) return
        val probe = rawQuery.copyOf()
        thread(isDaemon = true, name = "dns-probe") {
            try {
                if (query(configured, probe) != null) {
                    Log.i(TAG, "Configured resolver reachable again")
                    fallbackUntil = 0L
                } else {
                    fallbackUntil = clock() + FALLBACK_HOLD_MS
                }
            } finally {
                probing.set(false)
            }
        }
    }

    private fun query(upstream: Upstream, rawQuery: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                Log.e(TAG, "Could not protect the forwarding socket; dropping query")
                return null
            }
            // A network's resolver is often only reachable on that network
            // (e.g. a cellular carrier's private IPv6 resolver while on Wi-Fi).
            upstream.network?.bindSocket(socket)
            socket.soTimeout = timeoutMs

            // Connecting makes the kernel drop datagrams from anyone but the
            // resolver, so an off-path sender can't race in a forged answer.
            socket.connect(upstream.address, upstream.port)
            socket.send(DatagramPacket(rawQuery, rawQuery.size))

            val buffer = ByteArray(MAX_DNS_PACKET)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            // The answer must carry the query's transaction ID (bytes 0-1).
            if (response.length < 2 || rawQuery.size < 2 ||
                buffer[0] != rawQuery[0] || buffer[1] != rawQuery[1]) {
                Log.w(TAG, "Upstream answer did not match the query; dropping")
                return null
            }
            buffer.copyOf(response.length)
        } catch (e: IOException) {
            Log.w(TAG, "Upstream DNS failed: ${e.message}")
            null
        } catch (e: RuntimeException) {
            // Android can surface a failed connect() as an unchecked exception.
            Log.w(TAG, "Upstream DNS failed: ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }
}
