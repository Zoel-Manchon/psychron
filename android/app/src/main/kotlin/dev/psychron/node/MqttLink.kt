package dev.psychron.node

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Publishes messages over mutual TLS from the on-disk outbox, and brings alerts back.
 *
 * Every message is written to the [Outbox] before anything tries to send it, and
 * removed only once the broker has acknowledged it at QoS 1: the process can die at
 * any point in between and the message is still there on the next start.
 *
 * On a metered network messages leave in batches every 30 seconds instead of one
 * every two. A modem that transmits every two seconds never gets to drop out of its
 * connected state, and on LTE or NR that state costs hundreds of milliwatts; one
 * burst every half minute lets it idle in between. The live panel then lags by up
 * to half a minute, which is the trade the screen tells the user about.
 */
class MqttLink(
    private val context: Context,
    private val config: NodeConfig,
    private val windowMs: Int,
    private val network: NetworkWatch,
    private val outbox: Outbox,
    private val onAlert: (topic: String, payload: String) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var client: MqttClient? = null
    @Volatile private var endpoint: String? = null
    /** The address that last connected, tried first until the network changes. */
    @Volatile private var lastWorked: String? = null

    // Released when the network changes, to cut a reconnect backoff or a batch wait
    // short: waiting out 10 s after walking into Wi-Fi range is waiting for nothing.
    private val wake = Semaphore(0)

    // Publish-to-acknowledgement times since the last window asked for them.
    private val rttLock = Any()
    private var rttSum = 0.0
    private var rttCount = 0

    init {
        network.observe(::onNetworkChange)
    }

    fun start() {
        running = true
        worker = Thread({ loop() }, "psychron-mqtt").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(1000)
        runCatching { client?.disconnect(1000) }
        runCatching { client?.close() }
        client = null
        NodeBus.update { it.copy(link = "stopped") }
    }

    /** Written to disk now; sent when the link and the batching schedule allow. */
    fun offer(topic: String, json: String) {
        val evicted = outbox.add(topic, json, System.currentTimeMillis())
        NodeBus.update { it.copy(queued = outbox.count, dropped = it.dropped + evicted) }
        wake.release()
    }

    /** Mean broker round trip since the last call, for the next window's `net.rtt`. */
    fun drainRtt(): Double? = synchronized(rttLock) {
        (if (rttCount == 0) null else rttSum / rttCount).also { rttSum = 0.0; rttCount = 0 }
    }

    val batching: Boolean get() = network.metered

    private fun onNetworkChange(change: NetworkWatch.Change) {
        lastWorked = null
        val abandon = when (change) {
            NetworkWatch.Change.SWITCHED -> true
            // Only a stream to a LAN address dies with the LAN. One through a VPN
            // or tunnel survives the phone changing networks underneath it, and
            // tearing it down would turn a seamless handover into a visible gap.
            NetworkWatch.Change.LOCAL_LOST -> endpoint?.let(Endpoints::isLocal) == true
            NetworkWatch.Change.AVAILABLE, NetworkWatch.Change.LOCAL_GAINED -> false
        }
        Log.i(TAG, "network $change (${network.label}), abandon=$abandon")
        if (abandon) {
            // From this thread, not the worker's, because the worker may be blocked
            // inside a publish on the very stream that just went quiet. Short
            // timeouts: there is no point waiting to say goodbye on a dead network.
            client?.let { runCatching { it.disconnectForcibly(100, 100) } }
        }
        wake.release()
    }

    private fun loop() {
        var backoffMs = 1000L
        while (running) {
            try {
                val c = client?.takeIf { it.isConnected } ?: connect()
                backoffMs = 1000L

                val waitMs = untilSendable()
                if (waitMs > 0) {
                    wake.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
                    continue
                }
                val batch = outbox.oldest(BATCH_LIMIT)
                for (m in batch) {
                    if (!running) return
                    send(c, m)
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "link down: ${e.message}")
                NodeBus.update { it.copy(link = "reconnecting · ${e.javaClass.simpleName}", endpoint = null) }
                runCatching { client?.close() }
                client = null
                endpoint = null
                try {
                    // A network change ends the wait early and starts the backoff over.
                    if (wake.tryAcquire(backoffMs, TimeUnit.MILLISECONDS)) {
                        wake.drainPermits()
                        backoffMs = 1000L
                        continue
                    }
                } catch (_: InterruptedException) {
                    return
                }
                // Capped at 10 s rather than minutes. A queued message costs nothing to
                // hold, but every second of backoff is a second of latency on all of
                // them once the broker is back, and a refused connect is cheap.
                backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    /**
     * Milliseconds until the outbox should be sent, 0 for now. Nothing waiting: a
     * second, to look again. Live on an unmetered network. Metered: once the oldest
     * message is half a minute old, or the batch is full.
     */
    private fun untilSendable(): Long {
        val oldest = outbox.oldestCreatedMs() ?: return 1000L
        if (!network.metered) return 0L
        if (outbox.count >= BATCH_LIMIT) return 0L
        val age = System.currentTimeMillis() - oldest
        return (BATCH_MS - age).coerceAtLeast(0L)
    }

    private fun connect(): MqttClient {
        // A client the broker dropped without an error is still an open object with
        // threads behind it. Closed before replacing, or every silent disconnect
        // leaks one.
        runCatching { client?.close() }
        client = null
        wake.drainPermits()

        val order = Endpoints.order(config.hosts, network.onLocalNetwork, lastWorked)
        var failure: Exception? = null
        for (host in order) {
            if (!running) throw InterruptedException()
            try {
                return connectTo(host)
            } catch (e: Exception) {
                Log.w(TAG, "$host:${config.port} refused: ${e.message}")
                failure = e
            }
        }
        throw failure ?: IllegalStateException("no broker address provisioned")
    }

    private fun connectTo(host: String): MqttClient {
        NodeBus.update { it.copy(link = "connecting to $host:${config.port}") }
        val c = MqttClient("ssl://$host:${config.port}", "${config.device}-${System.nanoTime()}",
                           MemoryPersistence())
        val options = MqttConnectOptions().apply {
            socketFactory = Provisioning.socketFactory(context)
            // Both, deliberately. Endpoint identification makes the TLS stack check
            // the broker's name during the handshake; the explicit verifier checks
            // it again afterwards. A certificate for any other host is refused either
            // way, which is the whole difference between TLS and encryption to a
            // stranger. Every provisioned address must therefore be in the broker's
            // certificate — infra/make-certs.sh puts them there.
            isHttpsHostnameVerificationEnabled = true
            sslHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 10
            isAutomaticReconnect = false
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
        }
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) = Unit     // the worker notices on its next publish
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            override fun messageArrived(topic: String, message: MqttMessage) {
                runCatching { onAlert(topic, String(message.payload, Charsets.UTF_8)) }
            }
        })
        try {
            c.connect(options)
            // A clean session remembers no subscriptions, so this is repeated on every
            // connect. Retained alerts arrive straight away: the current state, not
            // only the changes made while this connection happens to be open.
            c.subscribe("psychron/alerts/#", 1)
        } catch (e: Exception) {
            runCatching { c.close() }
            throw e
        }
        // A publish waits for its acknowledgement at most this long. Unbounded, a
        // stream that died without a reset would hold the worker until the keepalive
        // gave up, 45 s later.
        c.timeToWait = 15_000L
        client = c
        endpoint = host
        lastWorked = host
        NodeBus.update { it.copy(link = "connected · mTLS", endpoint = host) }
        Provisioning.retireLegacyKey(context)
        return c
    }

    private fun send(c: MqttClient, m: Outbox.Message) {
        val waited = System.currentTimeMillis() - m.createdMs
        // Replayed means it waited longer than the schedule it was sent on explains:
        // an outage, not a batch.
        val schedule = if (network.metered) BATCH_MS else 0L
        val replayed = waited > schedule + 2L * windowMs + 1000L
        val json = if (replayed) Contract.markReplayed(m.json) else m.json

        val started = SystemClock.elapsedRealtime()
        // QoS 1 on a blocking client: this returns only once the broker has
        // acknowledged the message, so nothing is removed that was not delivered.
        c.publish(m.topic, json.toByteArray(Charsets.UTF_8), 1, false)
        val rtt = SystemClock.elapsedRealtime() - started
        outbox.remove(m.id)
        synchronized(rttLock) { rttSum += rtt; rttCount++ }

        val sample = m.topic.endsWith("/sample")
        NodeBus.update {
            it.copy(sent = it.sent + 1, replayed = it.replayed + if (replayed) 1 else 0,
                    queued = outbox.count, lastJson = if (sample) json else it.lastJson,
                    lastAckElapsed = SystemClock.elapsedRealtime())
        }
    }

    companion object {
        private const val TAG = "psychron-link"
        const val BATCH_MS = 30_000L
        private const val BATCH_LIMIT = 200
    }
}
