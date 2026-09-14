package dev.psychron.node

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The service's state, observable from the screen.
 *
 * A single immutable snapshot replaced atomically, delivered on the main thread.
 * No coroutines, no LiveData: the app has one producer and one screen, and a
 * dependency to move one value between them would be the largest thing in the APK.
 */
object NodeBus {
    data class State(
        val running: Boolean = false,
        val link: String = "stopped",
        val latest: Contract.Summary? = null,
        val sensors: Map<String, Boolean> = emptyMap(),
        val microphone: Boolean = false,
        val sent: Long = 0,
        val replayed: Long = 0,
        val dropped: Long = 0,
        val queued: Int = 0,
        val lastJson: String? = null,
        /** Where window timestamps come from, and how far the phone's own clock is off. */
        val clock: String = "system clock · NTP pending",
        /** "Wi-Fi", "mobile data · VPN", ...: what the default network is. */
        val network: String = "no network",
        /** The broker address the link is connected to, when it is. */
        val endpoint: String? = null,
        /** This app's traffic since start, split by whether the network was metered. */
        val meteredBytes: Long = 0,
        val unmeteredBytes: Long = 0,
        /** elapsedRealtime at start, to turn byte counts into a rate. */
        val startedElapsed: Long = 0,
    )

    @Volatile var state = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    fun update(change: (State) -> State) {
        val next = synchronized(lock) { change(state).also { state = it } }
        main.post { listeners.forEach { it(next) } }
    }

    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun forget(listener: (State) -> Unit) {
        listeners -= listener
    }
}
