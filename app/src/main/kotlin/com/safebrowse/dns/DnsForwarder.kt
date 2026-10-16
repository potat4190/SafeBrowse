package com.safebrowse.dns

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
 * those stay first for [FALLBACK_HOLD_MS] before the configured one is tried
 * again. Filtering is unaffected: the blocklist is applied before forwarding.
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

    /** Until when the network's own resolvers go first. */
    @Volatile private var fallbackUntil = 0L

    /** Blocking call — must run on an IO dispatcher. Returns null if every resolver failed. */
    fun forward(rawQuery: ByteArray): ByteArray? {
        val network = networkDns().filter {
            it.address != configured.address || it.port != configured.port
        }
        val onFallback = clock() < fallbackUntil
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
