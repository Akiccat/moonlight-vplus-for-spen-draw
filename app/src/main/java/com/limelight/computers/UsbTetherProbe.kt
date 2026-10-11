package com.limelight.computers

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import com.limelight.LimeLog
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds the PC's address on the USB tethering link.
 *
 * Sunshine is reachable over two independent paths, and which one a stream uses is
 * decided entirely by the address the client dials:
 *
 *  - WiFi: the PC sits on the same router subnet (e.g. 192.168.1.4)
 *  - USB:  the tablet is the tethering *server* and hands the PC an address on a
 *           separate /24 (e.g. rndis0 = 10.79.42.12/24, PC = 10.79.42.55)
 *
 * Those two never overlap, so an address alone tells us which cable is in use. That
 * is what lets this return a second, independent card instead of another address on
 * the existing one.
 *
 * Why not ConnectivityManager: the tether network is TRANSPORT_VPN, and
 * ConnectivityManager.getLinkProperties() on such a network frequently reports no
 * LinkAddress at all -- the interface enumeration below does. Verified on this
 * device: the USB path is only visible through NetworkInterface.
 *
 * Discovery is a two-step probe rather than a scan: we learn our own tether
 * subnet from the local interface, then find the PC inside it. A /24 scan would
 * cost up to 254 connects and show up as traffic on the user's LAN; a single
 * connect to the gateway-adjacent host we already have a route to does not.
 */
object UsbTetherProbe {

    private const val PROBE_TIMEOUT_MS = 1500

    /** Sunshine's plain HTTP port. Bound on all interfaces when it is running. */
    private const val SUNSHINE_HTTP_PORT = 47989

    /** Result of a successful probe: how to reach the PC over the wire. */
    data class UsbPeer(
        /** The PC's address on the tether subnet, e.g. "10.79.42.55". */
        val address: String,
        /** The tablet's own tether address, e.g. "10.79.42.12". */
        val localAddress: String,
        /** Netmask prefix length of the tether subnet (usually 24). */
        val prefixLength: Int,
        /** True when the link was present but the PC did not answer. */
        val linkUpButNoPeer: Boolean = false
    )

    /**
     * Local addresses of every interface that looks like Android USB tethering.
     *
     * Matches on the interface name because that is the only reliable signal:
     * a RNDIS or NCM link can report any transport, and filtering on
     * TRANSPORT_VPN would also catch plain WiFi hotspots.
     */
    private fun tetherLocalAddresses(): List<Inet4Address> {
        val result = ArrayList<Inet4Address>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue

                val name = nif.name.lowercase()
                val looksTether = name.startsWith("rndis") || name.startsWith("ncm") ||
                        name.startsWith("usb") || name.startsWith("ap") ||
                        name.contains("rndis") || name.contains("tether")
                if (!looksTether) continue

                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add(addr)
                        LimeLog.info("UsbTetherProbe: tether interface $nif has ${addr.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("UsbTetherProbe: interface enumeration failed: ${e.message}")
        }
        return result
    }

    /** hostAddress of every tether interface, for comparisons that need a String. */
    private fun tetherLocalText(): Set<String> =
        tetherLocalAddresses().mapNotNull { it.hostAddress }.toSet()

    /**
     * Link properties of the tether network, used only for the prefix length.
     * Returns null when the platform has no record of it, which is common -- the
     * prefix is then inferred from the probe.
     */
    private fun tetherPrefixLength(context: Context, fallback: Int): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return fallback
        return try {
            val network: Network = cm.activeNetwork ?: return fallback
            val props = cm.getLinkProperties(network) ?: return fallback
            for (link: LinkAddress in props.linkAddresses) {
                val addr = link.address
                if (addr is Inet4Address && addr.hostAddress in tetherLocalText()) {
                    return link.prefixLength
                }
            }
            fallback
        } catch (e: Exception) {
            fallback
        } catch (_: NoSuchMethodError) {
            fallback
        }
    }

    /**
     * True when the tablet currently has a USB tethering link up. Cheap: it only
     * inspects local interfaces and does no network I/O, so it is safe to call on
     * every resume.
     */
    fun hasTetherLink(): Boolean = tetherLocalAddresses().isNotEmpty()

    /**
     * Locates the PC on the USB link.
     *
     * Returns null when there is no USB link at all -- the caller should then show
     * no USB card at all rather than an offline one, because a phantom card that
     * can never connect is worse than an absent card.
     *
     * When the link is up but the PC does not answer, returns a result with
     * linkUpButNoPeer = true so the caller can render the card as offline instead
     * of silently dropping it (the PC may simply be asleep).
     */
    fun probe(context: Context): UsbPeer? {
        val locals = tetherLocalAddresses()
        if (locals.isEmpty()) return null

        val prefix = tetherPrefixLength(context, 24)

        for (local in locals) {
            // hostAddress, not .address: on this SDK .address hands back a
            // ByteArray (it is the raw 4 bytes), which silently turns every string
            // operation below into a compile error or a nonsense comparison.
            val localText = local.hostAddress ?: continue
            if (!isUsableV4(localText)) continue

            // Try the handful of addresses a Windows-host DHCP server hands out.
            // .55 is what this host actually gets; .1 / .2 cover other hosts and
            // generic tether bridges. Deliberately NOT a full sweep: each miss is
            // a real SYN on the user's LAN.
            val candidates = listOf(55, 1, 2)
                .map { withLastOctet(localText, it) }
                .distinct()

            for (candidate in candidates) {
                if (candidate == localText) continue
                if (canOpenTcp(candidate, SUNSHINE_HTTP_PORT)) {
                    LimeLog.info("UsbTetherProbe: PC reachable at $candidate over USB (local $localText/$prefix)")
                    return UsbPeer(candidate, localText, prefix)
                }
            }

            // Link is up but nothing answered. Report it so the card can show as
            // offline; the PC is probably just not running Sunshine yet.
            LimeLog.info("UsbTetherProbe: tether link up ($localText/$prefix) but no PC answered")
            return UsbPeer("", localText, prefix, linkUpButNoPeer = true)
        }
        return null
    }

    private fun isUsableV4(text: String): Boolean =
        text.count { it == '.' } == 3 && text.split('.').all { it.toIntOrNull() in 0..255 }

    /** Replaces the last dotted octet, leaving the /24 prefix intact. */
    private fun withLastOctet(v4: String, last: Int): String {
        val parts = v4.split('.')
        if (parts.size != 4) return v4
        return "${parts[0]}.${parts[1]}.${parts[2]}.$last"
    }

    /**
     * True when a TCP connect to the host's Sunshine HTTP port succeeds.
     *
     * A connect is enough and is the right probe: Sunshine binds 47989 on all
     * interfaces, and completing the handshake proves both that the PC is alive and
     * that Sunshine is up. No data is sent, so this is invisible to the host.
     */
    private fun canOpenTcp(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}