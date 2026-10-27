package com.safebrowse

import com.safebrowse.dns.DnsCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DnsCacheTest {

    private var now = 0L
    private val cache = DnsCache({ now })

    private val qname = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)

    private fun query(id: Int, type: Int = 1): ByteArray =
        ByteBuffer.allocate(12 + qname.size + 4)
            .putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0)
            .put(qname).putShort(type.toShort()).putShort(1).array()

    /** Response with one A record (TTL [ttl]) via a compression pointer to the question, plus an OPT record. */
    private fun answer(id: Int, ttl: Int, flags: Int = 0x8180): ByteArray =
        ByteBuffer.allocate(12 + qname.size + 4 + 16 + 11)
            .putShort(id.toShort()).putShort(flags.toShort()).putShort(1).putShort(1).putShort(0).putShort(1)
            .put(qname).putShort(1).putShort(1)
            .putShort(0xC00C.toShort()).putShort(1).putShort(1).putInt(ttl).putShort(4)
            .put(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
            .put(byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0x80.toByte(), 0, 0, 0))   // OPT, DO bit in "TTL"
            .array()

    private val ttlAt = 12 + qname.size + 4 + 6

    @Test fun `a cached answer carries the new id and a counted-down ttl`() {
        cache.put(query(1), answer(1, ttl = 60))
        now += 20_000
        val hit = cache.get(query(0x2222))
        assertNotNull(hit)
        assertEquals(0x2222, ByteBuffer.wrap(hit!!).short.toInt() and 0xFFFF)
        assertEquals(40, ByteBuffer.wrap(hit).getInt(ttlAt))
        // The OPT record's flags are left alone.
        assertEquals(0x00008000, ByteBuffer.wrap(hit).getInt(hit.size - 6))
    }

    @Test fun `entries expire with their ttl, capped at five minutes`() {
        cache.put(query(1), answer(1, ttl = 60))
        now += 60_000
        assertNull(cache.get(query(2)))

        cache.put(query(1), answer(1, ttl = 86_400))
        now += 300_000
        assertNull(cache.get(query(2)))
    }

    @Test fun `a different question type is a different entry`() {
        cache.put(query(1, type = 1), answer(1, ttl = 60))
        assertNull(cache.get(query(2, type = 28)))
    }

    @Test fun `truncated, failed and zero-ttl answers are not cached`() {
        cache.put(query(1), answer(1, ttl = 60, flags = 0x8380))   // TC
        cache.put(query(1), answer(1, ttl = 60, flags = 0x8182))   // SERVFAIL
        cache.put(query(1), answer(1, ttl = 0))
        assertNull(cache.get(query(2)))
    }

    @Test fun `garbage is not cached`() {
        cache.put(query(1), byteArrayOf(1, 2, 3))
        cache.put(query(1), answer(1, ttl = 60).copyOf(40))         // cut off mid-record
        assertNull(cache.get(query(2)))
    }

    @Test fun `clear empties the cache`() {
        cache.put(query(1), answer(1, ttl = 60))
        cache.clear()
        assertNull(cache.get(query(2)))
    }
}
