package com.safebrowse.util

import java.net.URI

/**
 * Turns whatever the user typed into a bare, lowercase domain.
 *
 *   "HTTPS://WWW.Example.COM/path?q=1"  ->  "example.com"
 *   "sub.Example.COM"                   ->  "sub.example.com"
 *   "not a domain!!"                    ->  null
 */
object DomainNormalizer {

    private val DOMAIN_REGEX =
        Regex("^(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$")

    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        val host = fromUri(trimmed) ?: fromPlainHost(trimmed) ?: return null
        // Strip a leading www. so "www.x.com" and "x.com" are one entry.
        // Suffix matching covers the subdomain either way.
        val bare = host.removePrefix("www.").trimEnd('.')
        return if (DOMAIN_REGEX.matches(bare)) bare else null
    }

    private fun fromUri(input: String): String? {
        val withScheme = if (input.contains("://")) input else "https://$input"
        return runCatching { URI(withScheme).host?.lowercase() }.getOrNull()
    }

    private fun fromPlainHost(input: String): String? =
        input.substringBefore('/').substringBefore('?').trim().lowercase().ifBlank { null }
}
