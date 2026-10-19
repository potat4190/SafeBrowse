package com.safebrowse.dns

import java.nio.ByteBuffer

/**
 * Remembers upstream answers for as long as their TTL allows (capped at
 * [MAX_TTL_S]), so a repeated lookup is answered from memory instead of
 * paying for a fresh socket, a protect() call and a network round trip.
 *
 * Android's own resolver caches too, but apps with a built-in resolver
 * (Chrome, Cronet-based apps such as YouTube Music) query us directly and
 * repeatedly. Only allowed names ever get here: the blocklist is checked
 * before the cache, so blocking a domain takes effect at once.
 *
 * The key is the whole query minus its transaction ID, so the question type,
 * class and EDNS settings all have to match. On a hit the stored answer gets
 * the new query's ID and its TTLs are counted down by the time it has spent
 * here, so nothing downstream caches it past its real expiry.
 */
class DnsCache(
    private val clock: () -> Long,
    private val maxEntries: Int = MAX_ENTRIES
) {
    companion object {
        private const val MAX_ENTRIES = 1_000
        private const val MAX_TTL_S = 300L
        private const val HEADER = 12
        private const val TYPE_OPT = 41
        private const val RCODE_NOERROR = 0
        private const val RCODE_NXDOMAIN = 3
    }

    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
    }

    private class Entry(
        val answer: ByteArray,
        /** Offsets of every TTL field to count down on a hit. */
        val ttlOffsets: IntArray,
        val ttls: LongArray,
        val storedAt: Long,
        val expiresAt: Long
    )

    private val entries = object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>) = size > maxEntries
    }

    fun get(query: ByteArray): ByteArray? {
        if (query.size < HEADER) return null
        val key = keyOf(query)
        val entry = synchronized(entries) { entries[key] } ?: return null
        val now = clock()
        if (now >= entry.expiresAt) {
            synchronized(entries) { if (entries[key] === entry) entries.remove(key) }
            return null
        }
        val elapsed = (now - entry.storedAt) / 1000
        val out = entry.answer.copyOf()
        out[0] = query[0]; out[1] = query[1]
        val buf = ByteBuffer.wrap(out)
        for (i in entry.ttlOffsets.indices) {
            buf.putInt(entry.ttlOffsets[i], (entry.ttls[i] - elapsed).coerceAtLeast(0).toInt())
        }
        return out
    }

    fun put(query: ByteArray, answer: ByteArray) {
        if (query.size < HEADER) return
        val (offsets, ttls) = ttlFields(answer) ?: return
        val lifetime = minOf(ttls.minOrNull() ?: return, MAX_TTL_S)
        if (lifetime <= 0) return
        val now = clock()
        val entry = Entry(answer.copyOf(), offsets, ttls, now, now + lifetime * 1000)
        synchronized(entries) { entries[keyOf(query)] = entry }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    private fun keyOf(query: ByteArray) =
        Key(query.copyOf().also { it[0] = 0; it[1] = 0 })

    /**
     * Walks the answer and returns the offset and value of every record TTL,
     * or null if the answer should not be cached at all: truncated, an error
     * other than NXDOMAIN, more than one question, malformed, or no records
     * to take a TTL from. The OPT pseudo-record is skipped; its "TTL" field
     * holds EDNS flags.
     */
    private fun ttlFields(answer: ByteArray): Pair<IntArray, LongArray>? {
        if (answer.size < HEADER) return null
        val buf = ByteBuffer.wrap(answer)
        val flags = buf.getShort(2).toInt() and 0xFFFF
        if (flags and 0x8000 == 0) return null                 // not a response
        if (flags and 0x0200 != 0) return null                 // TC: truncated
        val rcode = flags and 0x000F
        if (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN) return null

        val qd = buf.getShort(4).toInt() and 0xFFFF
        val an = buf.getShort(6).toInt() and 0xFFFF
        val ns = buf.getShort(8).toInt() and 0xFFFF
        val ar = buf.getShort(10).toInt() and 0xFFFF
        if (qd != 1 || an + ns == 0) return null

        return try {
            buf.position(HEADER)
            skipName(buf); buf.position(buf.position() + 4)    // QTYPE, QCLASS

            val offsets = ArrayList<Int>()
            val ttls = ArrayList<Long>()
            repeat(an + ns + ar) {
                skipName(buf)
                val type = buf.short.toInt() and 0xFFFF
                buf.short                                       // class
                val ttlAt = buf.position()
                val ttl = buf.int.toLong() and 0xFFFFFFFFL
                val rdLength = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < rdLength) return null
                buf.position(buf.position() + rdLength)
                if (type != TYPE_OPT) { offsets += ttlAt; ttls += ttl }
            }
            offsets.toIntArray() to ttls.toLongArray()
        } catch (e: RuntimeException) {
            null                                                // ran off the end
        }
    }

    private fun skipName(buf: ByteBuffer) {
        while (true) {
            val len = buf.get().toInt() and 0xFF
            when {
                len == 0 -> return
                len and 0xC0 == 0xC0 -> { buf.get(); return }   // compression pointer
                len > 63 -> throw IllegalArgumentException("bad label")
                else -> buf.position(buf.position() + len)
            }
        }
    }
}
