package com.safebrowse.util

/**
 * Thread-safe blocklist with suffix matching, so blocking "example.com" also
 * blocks "www.example.com" and "sub.video.example.com".
 *
 * Read on the VPN packet thread, written from the database observer, hence the
 * lock. Lookup is O(number of labels), never more than a handful.
 */
class DomainMatcher {

    private val lock = Any()
    private val blocklist = HashSet<String>()

    fun load(domains: Collection<String>) = synchronized(lock) {
        blocklist.clear(); blocklist.addAll(domains)
    }

    fun clear() = synchronized(lock) { blocklist.clear() }

    val size: Int get() = synchronized(lock) { blocklist.size }

    fun isBlocked(queriedDomain: String): Boolean {
        val domain = queriedDomain.lowercase().trimEnd('.')
        if (domain.isEmpty()) return false

        synchronized(lock) {
            var current = domain
            while (true) {
                if (blocklist.contains(current)) return true
                val dot = current.indexOf('.')
                // Stop before we are left with a bare TLD: never block "com".
                if (dot < 0 || current.indexOf('.', dot + 1) < 0) return false
                current = current.substring(dot + 1)
            }
        }
    }
}
