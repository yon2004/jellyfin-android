package org.jellyfin.mobile.player.cast.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Chooses the local IPv4 address that the Cast relay should bind to and advertise.
 *
 * The problem this solves: [ConnectivityManager.getActiveNetwork] returns the VPN network
 * whenever Tailscale (or any other VpnService) is connected, so asking it for "the" local
 * address hands back the 100.x CGNAT address. A Chromecast on the local Wi-Fi cannot reach
 * that address, so the relay looks like it started fine but nothing ever connects to it.
 *
 * Instead, this enumerates every network, discards anything carrying TRANSPORT_VPN, and
 * — when the Chromecast's own address is known — picks the candidate whose subnet actually
 * contains the receiver. That is the only address guaranteed to be reachable from the TV.
 */
class LanAddressResolver(private val context: Context) {

    /** One usable local IPv4 address, with the prefix length of the interface it belongs to. */
    data class Candidate(
        val address: Inet4Address,
        val prefixLength: Int,
        val isWifi: Boolean,
        val interfaceName: String?,
    ) {
        fun contains(other: Inet4Address): Boolean = address.isInSameSubnet(other, prefixLength)

        override fun toString(): String =
            "${address.hostAddress}/$prefixLength on ${interfaceName ?: "?"}${if (isWifi) " (wifi)" else ""}"
    }

    /**
     * Returns the address to bind to, or null if no usable LAN interface exists.
     *
     * @param castDeviceAddress the Chromecast's address, from CastDevice.getInetAddress().
     *   May be null — the Cast SDK does not always expose it. When null, the first Wi-Fi
     *   candidate is used, which is correct on a normal single-Wi-Fi phone but cannot be
     *   verified against the receiver.
     */
    fun resolve(castDeviceAddress: InetAddress? = null): Inet4Address? {
        val candidates = candidates()
        if (candidates.isEmpty()) return null

        if (castDeviceAddress is Inet4Address) {
            val onSameSubnet = candidates.firstOrNull { it.contains(castDeviceAddress) }
            if (onSameSubnet != null) return onSameSubnet.address
        }

        return candidates.first().address
    }

    /**
     * All non-VPN IPv4 addresses on Wi-Fi or Ethernet transports, Wi-Fi first.
     * Exposed separately so callers can log what was available when resolution fails.
     */
    @Suppress("DEPRECATION") // getAllNetworks: no non-callback replacement, and we need a snapshot
    fun candidates(): List<Candidate> {
        val connectivityManager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        val found = mutableListOf<Candidate>()

        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue

            // The whole point: never offer the Chromecast a Tailscale/WireGuard address.
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isWifi && !isEthernet) continue

            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue

            for (linkAddress in linkProperties.linkAddresses) {
                val address = linkAddress.address
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) continue

                // Belt and braces: a VPN address should already have been filtered by transport,
                // but Tailscale's 100.64.0.0/10 range is never a LAN a Chromecast sits on.
                if (address.isCarrierGradeNat()) continue

                found += Candidate(
                    address = address,
                    prefixLength = linkAddress.prefixLength,
                    isWifi = isWifi,
                    interfaceName = linkProperties.interfaceName,
                )
            }
        }

        return found.sortedByDescending { it.isWifi }
    }

    companion object {
        private const val IPV4_BITS = 32
        private const val BITS_PER_BYTE = 8
        private const val BYTE_MASK = 0xFF
        private const val CGNAT_FIRST_OCTET = 100
        private const val CGNAT_SECOND_OCTET_MIN = 64
        private const val CGNAT_SECOND_OCTET_MAX = 127

        /** True for 100.64.0.0/10, the shared address space Tailscale allocates from. */
        private fun Inet4Address.isCarrierGradeNat(): Boolean {
            val octets = address
            val first = octets[0].toInt() and BYTE_MASK
            val second = octets[1].toInt() and BYTE_MASK
            return first == CGNAT_FIRST_OCTET && second in CGNAT_SECOND_OCTET_MIN..CGNAT_SECOND_OCTET_MAX
        }

        /** Compares the first [prefixLength] bits of two IPv4 addresses. */
        private fun Inet4Address.isInSameSubnet(other: Inet4Address, prefixLength: Int): Boolean {
            if (prefixLength !in 0..IPV4_BITS) return false

            val self = address
            val theirs = other.address
            var remaining = prefixLength
            var index = 0

            while (remaining > 0) {
                val bitsInThisByte = minOf(BITS_PER_BYTE, remaining)
                val mask = (BYTE_MASK shl (BITS_PER_BYTE - bitsInThisByte)) and BYTE_MASK
                if ((self[index].toInt() and mask) != (theirs[index].toInt() and mask)) return false
                remaining -= bitsInThisByte
                index++
            }

            return true
        }
    }
}
