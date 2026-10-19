package com.safebrowse

import com.safebrowse.dns.DnsForwarder
import com.safebrowse.dns.DnsPacketBuilder
import com.safebrowse.dns.DnsParser
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Regression for the "allowed sites load forever" bug: on a network that
 * drops DNS to outside resolvers, every forwarded query timed out and nothing
 * was written back. Loopback servers stand in for the two resolvers.
 */
class DnsForwarderTest {

    private val loopback = InetAddress.getLoopbackAddress()
    private val sockets = mutableListOf<DatagramSocket>()

    @After fun tearDown() = sockets.forEach { it.close() }

    /** A resolver that receives queries and never answers, like a firewalled 1.1.1.1. */
    private fun silentResolver(received: AtomicInteger = AtomicInteger()): DatagramSocket =
        DatagramSocket(0, loopback).also { s ->
            sockets += s
            thread(isDaemon = true) {
                val buf = ByteArray(4096)
                runCatching { while (true) { s.receive(DatagramPacket(buf, buf.size)); received.incrementAndGet() } }
            }
        }

    /** A resolver that answers every query by echoing it with QR set. */
    private fun answeringResolver(received: AtomicInteger = AtomicInteger()): DatagramSocket =
        DatagramSocket(0, loopback).also { s ->
            sockets += s
            thread(isDaemon = true) {
                val buf = ByteArray(4096)
                runCatching {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        received.incrementAndGet()
                        val reply = buf.copyOf(p.length).also { it[2] = (it[2].toInt() or 0x80).toByte() }
                        s.send(DatagramPacket(reply, reply.size, p.socketAddress))
                    }
                }
            }
        }

    /** AAAA (type 28) query for www.youtube.com with an EDNS0 OPT record. */
    private fun aaaaQuery(id: Int = 0x2b2b): ByteArray {
        val qname = byteArrayOf(3) + "www".toByteArray() + byteArrayOf(7) + "youtube".toByteArray() +
            byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        val opt = byteArrayOf(0, 0, 41, 0x04, 0xD0.toByte(), 0, 0, 0, 0, 0, 0)
        return ByteBuffer.allocate(12 + qname.size + 4 + opt.size)
            .putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(1)
            .put(qname).putShort(28).putShort(1).put(opt).array()
    }

    private fun forwarder(configured: DatagramSocket, network: List<DatagramSocket>, now: () -> Long = { 0L }) =
        DnsForwarder(
            upstreamIp = loopback.hostAddress!!,
            protect = { true },
            networkDns = { network.map { DnsForwarder.Upstream(loopback, port = it.localPort) } },
            port = configured.localPort,
            timeoutMs = 300,
            clock = now
        )

    @Test fun `answer comes from the configured resolver when it works`() {
        val configured = answeringResolver()
        val networkHits = AtomicInteger()
        val network = answeringResolver(networkHits)

        val answer = forwarder(configured, listOf(network)).forward(aaaaQuery())

        assertNotNull(answer)
        assertEquals(0, networkHits.get())
    }

    @Test fun `falls back to the network resolver when the configured one is blocked`() {
        val answer = forwarder(silentResolver(), listOf(answeringResolver())).forward(aaaaQuery(0x4242))

        assertNotNull("AAAA query must still be answered", answer)
        assertEquals(0x4242, ByteBuffer.wrap(answer!!).short.toInt() and 0xFFFF)
    }

    @Test fun `after falling back, later queries skip the dead resolver`() {
        val configuredHits = AtomicInteger()
        var now = 0L
        val fwd = forwarder(silentResolver(configuredHits), listOf(answeringResolver())) { now }

        fwd.forward(aaaaQuery(1))
        Thread.sleep(50)
        assertEquals(1, configuredHits.get())

        now += 60_000                       // inside the hold: network first
        assertNotNull(fwd.forward(aaaaQuery(2)))
        Thread.sleep(50)
        assertEquals(1, configuredHits.get())

        now += 10 * 60_000                  // hold expired: configured retried
        assertNotNull(fwd.forward(aaaaQuery(3)))
        Thread.sleep(50)
        assertEquals(2, configuredHits.get())
    }

    @Test fun `returns null only when every resolver is dead`() {
        assertNull(forwarder(silentResolver(), listOf(silentResolver())).forward(aaaaQuery()))
    }

    @Test fun `SERVFAIL for an unanswered AAAA query keeps the ID and question`() {
        val raw = aaaaQuery(0x7a7a)
        val query = DnsParser.parse(raw, raw.size)!!
        assertEquals(28, query.qType)

        val fail = DnsPacketBuilder.buildServFail(query)
        val buf = ByteBuffer.wrap(fail)
        assertEquals(0x7a7a, buf.short.toInt() and 0xFFFF)
        assertEquals(0x8182, buf.short.toInt() and 0xFFFF)   // response, RCODE 2
        assertEquals(1, buf.short.toInt())                    // question echoed
        assertEquals(0, buf.short.toInt()); assertEquals(0, buf.short.toInt()); assertEquals(0, buf.short.toInt())
        assertArrayEquals(raw.copyOfRange(12, query.questionEnd), fail.copyOfRange(12, fail.size))
    }

    @Test fun `FORMERR for an unparseable query keeps the ID`() {
        val raw = byteArrayOf(0x55, 0x66, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0, 0)   // QDCOUNT 0
        assertNull(DnsParser.parse(raw, raw.size))
        val err = DnsPacketBuilder.buildFormErr(raw)
        assertEquals(12, err.size)
        assertEquals(0x5566, ByteBuffer.wrap(err).short.toInt() and 0xFFFF)
        assertEquals(0x8181, ByteBuffer.wrap(err, 2, 2).short.toInt() and 0xFFFF)
    }

    @Test fun `once the hold expires the dead resolver is probed off the request path`() {
        val configuredHits = AtomicInteger()
        var now = 0L
        val fwd = forwarder(silentResolver(configuredHits), listOf(answeringResolver())) { now }
        fwd.forward(aaaaQuery(1))                // falls back

        now += 10 * 60_000
        val started = System.nanoTime()
        assertNotNull(fwd.forward(aaaaQuery(2)))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("lookup waited on the probe: $tookMs ms", tookMs < 200)   // timeout is 300

        Thread.sleep(50)
        assertEquals(2, configuredHits.get())    // the probe did go out
    }

    @Test fun `a probe that gets an answer switches back to the configured resolver`() {
        val networkHits = AtomicInteger()
        val configuredUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val configured = DatagramSocket(0, loopback).also { s ->
            sockets += s
            thread(isDaemon = true) {
                val buf = ByteArray(4096)
                runCatching {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        if (!configuredUp.get()) continue
                        val reply = buf.copyOf(p.length).also { it[2] = (it[2].toInt() or 0x80).toByte() }
                        s.send(DatagramPacket(reply, reply.size, p.socketAddress))
                    }
                }
            }
        }
        var now = 0L
        val fwd = forwarder(configured, listOf(answeringResolver(networkHits))) { now }
        fwd.forward(aaaaQuery(1))
        assertEquals(1, networkHits.get())

        configuredUp.set(true)
        now += 10 * 60_000
        fwd.forward(aaaaQuery(2))                // network answers; probe succeeds in background
        Thread.sleep(150)
        fwd.forward(aaaaQuery(3))
        assertEquals(2, networkHits.get())       // query 3 went to the configured resolver
    }

    @Test fun `a repeated lookup is answered from the cache`() {
        val hits = AtomicInteger()
        val ttlResolver = DatagramSocket(0, loopback).also { s ->
            sockets += s
            thread(isDaemon = true) {
                val buf = ByteArray(4096)
                runCatching {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        hits.incrementAndGet()
                        // Echo the question, add one AAAA record with a 60 s TTL.
                        val q = buf.copyOf(p.length)
                        val questionEnd = q.size - 11            // strip the OPT record
                        val reply = ByteBuffer.allocate(questionEnd + 28)
                            .put(q, 0, questionEnd)
                            .putShort(0xC00C.toShort()).putShort(28).putShort(1).putInt(60).putShort(16)
                            .put(ByteArray(16)).array()
                        ByteBuffer.wrap(reply).putShort(2, 0x8180.toShort()).putShort(6, 1).putShort(10, 0)
                        s.send(DatagramPacket(reply, reply.size, p.socketAddress))
                    }
                }
            }
        }
        val fwd = forwarder(ttlResolver, emptyList())
        assertNotNull(fwd.forward(aaaaQuery(1)))
        val second = fwd.forward(aaaaQuery(2))
        assertEquals(1, hits.get())
        assertEquals(2, ByteBuffer.wrap(second!!).short.toInt())
    }
}
