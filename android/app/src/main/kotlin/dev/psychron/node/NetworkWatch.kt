package dev.psychron.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Which network the phone is on, and when that changes.
 *
 * A TCP stream belongs to the network that carried it. Walk out of Wi-Fi range and
 * the connection to the broker does not fail, it goes quiet, and MQTT only notices
 * at the next missed keepalive. Told about the change directly, the link can open a
 * new stream on the new network in about a second instead.
 */
class NetworkWatch(context: Context) {

    enum class Change {
        /** The default network was replaced, or lost: every open stream is on the old one. */
        SWITCHED,
        /** A network appeared where there was none. Nothing to abandon, but no reason to wait. */
        AVAILABLE,
        /** No Wi-Fi or Ethernet any more, so no LAN address is reachable. */
        LOCAL_LOST,
        LOCAL_GAINED,
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val listeners = mutableListOf<(Change) -> Unit>()
    // Its own thread: reacting to a change can block for a moment while a stale
    // connection is torn down, and the sensor thread should not feel that.
    private val thread = HandlerThread("psychron-network")

    @Volatile var label: String = "no network"
        private set
    @Volatile var metered: Boolean = false
        private set

    private var defaultNetwork: Network? = null
    private val local = mutableSetOf<Network>()
    val onLocalNetwork: Boolean get() = synchronized(local) { local.isNotEmpty() }

    // Bytes this app sent and received, split by whether the network was metered at
    // the moment of sampling. Once a window, so a burst across a change can land on
    // the wrong side of it by at most one window's traffic.
    private val uid = Process.myUid()
    private var lastBytes = -1L
    @Volatile var meteredBytes = 0L
        private set
    @Volatile var unmeteredBytes = 0L
        private set

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            when (previous) {
                network -> Unit
                null -> emit(Change.AVAILABLE)
                else -> emit(Change.SWITCHED)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != defaultNetwork) return
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            label = describe(caps)
        }

        override fun onLost(network: Network) {
            if (network != defaultNetwork) return
            defaultNetwork = null
            label = "no network"
            emit(Change.SWITCHED)
        }
    }

    private val localCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val gained = synchronized(local) { local.isEmpty().also { local += network } }
            if (gained) emit(Change.LOCAL_GAINED)
        }

        override fun onLost(network: Network) {
            val lost = synchronized(local) { local.remove(network) && local.isEmpty() }
            if (lost) emit(Change.LOCAL_LOST)
        }
    }

    /** Before [start]. Called on the watch's own thread. */
    fun observe(listener: (Change) -> Unit) {
        listeners += listener
    }

    fun start() {
        thread.start()
        val handler = Handler(thread.looper)
        cm.registerDefaultNetworkCallback(defaultCallback, handler)
        // Wi-Fi or Ethernet, with or without internet: a LAN that cannot reach the
        // outside still reaches a broker on that LAN, which is the only question.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, localCallback, handler)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
        runCatching { cm.unregisterNetworkCallback(localCallback) }
        thread.quitSafely()
    }

    /** Once per window, from the sampling thread. */
    fun sampleTraffic() {
        val tx = TrafficStats.getUidTxBytes(uid)
        val rx = TrafficStats.getUidRxBytes(uid)
        if (tx < 0 || rx < 0) return            // TrafficStats.UNSUPPORTED on this device
        val total = tx + rx
        if (lastBytes >= 0) {
            val delta = (total - lastBytes).coerceAtLeast(0)
            if (metered) meteredBytes += delta else unmeteredBytes += delta
        }
        lastBytes = total
    }

    private fun emit(change: Change) = listeners.forEach { it(change) }

    private fun describe(caps: NetworkCapabilities): String {
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            // A VPN does not always declare what it runs over. Whether a local
            // network is up is the best answer left.
            onLocalNetwork -> "Wi-Fi"
            else -> "mobile data"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "$base · VPN" else base
    }
}
