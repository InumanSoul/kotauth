package com.kauth.adapter.webhook

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Decides which addresses a webhook may reach. Every address the host resolves to must pass, so a
 * name that resolves to both a public and an internal address is refused.
 *
 * Link-local (cloud metadata sits at 169.254.169.254 and fd00:ec2::254), unspecified, multicast and
 * broadcast addresses are always refused. Loopback, private, unique-local and carrier-grade NAT
 * ranges are refused unless [allowPrivateNetworks] is set, for installs that deliver to services
 * on their own network.
 */
class WebhookAddressPolicy(
    private val allowPrivateNetworks: Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Null when [url] may be called, otherwise the reason it may not. */
    fun refusalFor(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "The URL is not valid."
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return "The URL must use http or https."
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        if (host.isNullOrBlank()) return "The URL has no host."
        val addresses =
            try {
                resolve(host)
            } catch (_: UnknownHostException) {
                return "The host $host could not be resolved."
            }
        if (addresses.isEmpty()) return "The host $host could not be resolved."
        return addresses.firstNotNullOfOrNull { refusalFor(it) }?.let { "The host $host $it" }
    }

    private fun refusalFor(address: InetAddress): String? =
        when {
            isAlwaysBlocked(address) -> "resolves to a link-local or reserved address, which webhooks may not reach."
            !allowPrivateNetworks && isPrivate(address) ->
                "resolves to a private or loopback address. Set KAUTH_WEBHOOK_ALLOW_PRIVATE_NETWORKS=true " +
                    "to deliver webhooks to your own network."
            else -> null
        }

    private fun isAlwaysBlocked(address: InetAddress): Boolean =
        address.isLinkLocalAddress ||
            address.isAnyLocalAddress ||
            address.isMulticastAddress ||
            address.hostAddress == "255.255.255.255" ||
            (address is Inet4Address && address.address[0].toInt() == 0) ||
            address == AWS_IPV6_METADATA

    private fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                first == 100 && second in 64..127 // RFC 6598 carrier-grade NAT
            }
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC // RFC 4193 unique local, fc00::/7
            else -> false
        }
    }

    private companion object {
        val AWS_IPV6_METADATA: InetAddress = InetAddress.getByName("fd00:ec2::254")
    }
}
