package dev.psychron.node

/**
 * Which broker address to try first.
 *
 * A node provisioned with a LAN address works until the phone leaves the building.
 * Provisioned with several — the LAN address and one reachable from anywhere, such
 * as a VPN or tunnel address — it has to choose, and the wrong first choice is not
 * an error but a ten-second connect timeout on every reconnect. So: a private
 * address is only worth trying first while the phone is on a local network, and
 * the address that last worked is tried first until the network changes.
 *
 * Pure, so the rule is tested without a phone.
 */
object Endpoints {

    /** An address that means something only on the network the phone is attached to. */
    fun isLocal(host: String): Boolean {
        if (host.endsWith(".local", ignoreCase = true)) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val o = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 } ?: return false }
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
        // 100.64.0.0/10 is deliberately absent. It is carrier-grade NAT space, which
        // is where Tailscale and similar overlays put their addresses: reachable from
        // mobile data, so exactly the address to try first away from home.
    }

    /**
     * The order to try [hosts] in. Configured order is the tie-break, so whoever
     * provisions the node still decides which of two equal addresses comes first.
     */
    fun order(hosts: List<String>, onLocalNetwork: Boolean, lastWorked: String?): List<String> {
        val base = if (onLocalNetwork) hosts else hosts.filterNot(::isLocal) + hosts.filter(::isLocal)
        return if (lastWorked != null && lastWorked in base) listOf(lastWorked) + (base - lastWorked) else base
    }

    /** `hosts=a,b` from node.properties, or the single `host=` older provisioning wrote. */
    fun parse(hosts: String?, host: String?): List<String> =
        (hosts ?: host ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
