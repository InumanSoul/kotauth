package com.kauth.infrastructure

import java.math.BigInteger
import java.net.InetAddress

/**
 * The proxies whose forwarded headers Kotauth believes, as address ranges.
 *
 * The client is found the way nginx's `real_ip_recursive` and Traefik's `trustedIPs` find it:
 * walk the forwarded chain from the right, skip every entry inside a trusted range, and take the
 * first one outside. Everything left of that entry was written by the client and is ignored.
 */
class TrustedProxies(
    private val ranges: List<Range>,
) {
    fun isTrusted(address: String): Boolean {
        val ip = parseIpLiteral(address) ?: return false
        return ranges.any { it.contains(ip) }
    }

    /** The client address in [chain] (rightmost entry last), or null when the chain is empty. */
    fun clientFrom(chain: List<String>): String? {
        val entries = chain.map { it.trim() }.filter { it.isNotEmpty() }
        return entries.lastOrNull { !isTrusted(it) } ?: entries.firstOrNull()
    }

    class Range(
        private val network: BigInteger,
        private val prefixLength: Int,
        private val bits: Int,
    ) {
        fun contains(ip: InetAddress): Boolean {
            val addressBits = ip.address.size * 8
            if (addressBits != bits) return false
            val shift = bits - prefixLength
            return BigInteger(1, ip.address).shiftRight(shift) == network.shiftRight(shift)
        }
    }

    companion object {
        /**
         * Parses a comma-separated list of CIDRs or single addresses (`173.245.48.0/20, 10.0.0.1, ::1`).
         * Throws [IllegalArgumentException] naming the first entry that isn't one.
         */
        fun parse(raw: String): TrustedProxies =
            TrustedProxies(
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                    parseRange(entry) ?: throw IllegalArgumentException("'$entry' is not an IP address or CIDR range")
                },
            )

        private fun parseRange(entry: String): Range? {
            val address = parseIpLiteral(entry.substringBefore('/')) ?: return null
            val bits = address.address.size * 8
            val prefix = if ('/' in entry) entry.substringAfter('/').toIntOrNull() else bits
            if (prefix == null || prefix !in 0..bits) return null
            return Range(BigInteger(1, address.address), prefix, bits)
        }

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d+)?$""")
        private val IPV6 = Regex("""^[0-9a-fA-F:.]+$""")

        /** An IP literal only: never a hostname, so parsing never triggers a DNS lookup. */
        internal fun parseIpLiteral(value: String): InetAddress? {
            val v = value.trim()
            IPV4.matchEntire(v)?.let { m ->
                val octets = (1..4).map { m.groupValues[it].toInt() }
                if (octets.any { it > 255 }) return null
                return InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
            }
            val bracketed = v.removePrefix("[").substringBefore("]").substringBefore('%')
            if (':' !in bracketed || !IPV6.matches(bracketed)) return null
            // Java returns an IPv4-mapped IPv6 literal as IPv4, so it matches IPv4 ranges.
            return runCatching { InetAddress.getByName(bracketed) }.getOrNull()
        }
    }
}
