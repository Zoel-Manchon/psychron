package dev.psychron.node

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Publishes windows over mutual TLS, and holds them while the network is gone.
 *
 * Windows are queued as summaries, not as JSON, and serialised at the moment of
 * sending. That is what lets a window held through an outage be marked as replayed
 * truthfully: whether it was replayed is only known when it finally goes out.
 */
class MqttLink(private val context: Context, private val config: NodeConfig, private val windowMs: Int) {

    private data class Pending(val envelope: Contract.Envelope, val summary: Contract.Summary, val createdAt: Long)

    // One hour at one window every two seconds. A phone cannot buffer forever,
    // and pretending it can would end in an out-of-memory kill that loses the whole
    // backlog instead of its oldest part. Drops are counted and shown, never silent.
    private val queue = LinkedBlockingDeque<Pending>(1800)

    @Volatile private var running = false
    private var worker: Thread? = null
    private var client: MqttClient? = null

    private val topic = "psychron/v2/${config.device}/sample"

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

    fun offer(envelope: Contract.Envelope, summary: Contract.Summary) {
        val p = Pending(envelope, summary, SystemClock.elapsedRealtime())
        if (!queue.offerLast(p)) {
            queue.pollFirst()
            queue.offerLast(p)
            NodeBus.update { it.copy(dropped = it.dropped + 1) }
        }
        NodeBus.update { it.copy(queued = queue.size) }
    }

    private fun loop() {
        var backoffMs = 1000L
        while (running) {
            try {
                val c = client?.takeIf { it.isConnected } ?: connect()
                backoffMs = 1000L
                val next = queue.pollFirst(1, TimeUnit.SECONDS) ?: continue
                try {
                    send(c, next)
                } catch (e: Exception) {
                    // Back at the head, not the tail: the order a backlog is replayed
                    // in is the order it was measured in.
                    queue.offerFirst(next)
                    throw e
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "link down: ${e.message}")
                NodeBus.update { it.copy(link = "reconnecting · ${e.javaClass.simpleName}") }
                runCatching { client?.close() }
                client = null
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun connect(): MqttClient {
        // A client the broker dropped without an error is still an open object with
        // threads behind it. Closed before replacing, or every silent disconnect
        // leaks one.
        runCatching { client?.close() }
        client = null
        NodeBus.update { it.copy(link = "connecting to ${config.host}:${config.port}") }
        val c = MqttClient("ssl://${config.host}:${config.port}", "${config.device}-${System.nanoTime()}",
                           MemoryPersistence())
        val options = MqttConnectOptions().apply {
            socketFactory = Provisioning.socketFactory(context)
            // Both, deliberately. Endpoint identification makes the TLS stack check
            // the broker's name during the handshake; the explicit verifier checks
            // it again afterwards. A certificate for any other host is refused either
            // way, which is the whole difference between TLS and encryption to a
            // stranger.
            isHttpsHostnameVerificationEnabled = true
            sslHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 10
            isAutomaticReconnect = false
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
        }
        c.connect(options)
        client = c
        NodeBus.update { it.copy(link = "connected · mTLS") }
        return c
    }

    private fun send(c: MqttClient, p: Pending) {
        val waited = SystemClock.elapsedRealtime() - p.createdAt
        val replayed = waited > 2L * windowMs + 1000L
        val quality = if (replayed) p.envelope.quality or Contract.Q_REPLAYED else p.envelope.quality
        val json = Contract.encode(p.envelope.copy(quality = quality), p.summary) ?: return
        // QoS 1 on a blocking client: this returns only once the broker has
        // acknowledged the message, so nothing is counted as sent that was not.
        c.publish(topic, json.toByteArray(Charsets.UTF_8), 1, false)
        NodeBus.update { it.copy(sent = it.sent + 1, replayed = it.replayed + if (replayed) 1 else 0,
                                 queued = queue.size, lastJson = json) }
    }

    companion object { private const val TAG = "psychron-link" }
}
