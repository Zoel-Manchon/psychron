package dev.psychron.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.security.SecureRandom

/**
 * The node. Runs as a foreground service so the record does not stop when the
 * screen does, and so the microphone indicator stays visible for as long as the
 * microphone is in use — which is the honest way to hold one.
 */
class NodeService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var window: SensorWindow
    private lateinit var sound: SoundMeter
    private var link: MqttLink? = null
    private var network: NetworkWatch? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val clock = TrustedClock { SystemClock.elapsedRealtime() }
    @Volatile private var clockThreadRunning = false
    private var clockThread: Thread? = null

    private lateinit var config: NodeConfig
    private var boot = 0L
    private var seq = 0L
    private var bootElapsed = 0L

    private val tick = object : Runnable {
        override fun run() {
            flush()
            // Scheduled against the previous deadline rather than "now + window", so
            // a slow flush does not stretch every later window by its own duration.
            handler.postAtTime(this, nextDeadline.also { nextDeadline += WINDOW_MS })
        }
    }
    private var nextDeadline = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (NodeBus.state.running) return START_STICKY

        config = Provisioning.config(this)
        sound = SoundMeter(this)
        goForeground(sound.available)

        // A fresh boot id per session, drawn once. With `up` measured from this same
        // instant, ingestion can place a window even if it arrives an hour late —
        // exactly as it does for the ESP32 after a power cut.
        boot = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        seq = 0
        bootElapsed = SystemClock.elapsedRealtime()

        thread = HandlerThread("psychron-sensors").apply { start() }
        handler = Handler(thread.looper)
        window = SensorWindow(this, handler)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "psychron:node")
            .apply { acquire() }

        startClock()
        // The link observes the watch, so it is built first and the watch started
        // after: a change reported before anyone listens is a change missed.
        val watch = NetworkWatch(this).also { network = it }
        link = MqttLink(this, config, WINDOW_MS, watch)
        watch.start()
        link?.start()
        window.start()
        sound.start()

        NodeBus.update {
            it.copy(running = true, sensors = window.present.toMap(), microphone = sound.available,
                    meteredBytes = 0, unmeteredBytes = 0, startedElapsed = SystemClock.elapsedRealtime())
        }

        nextDeadline = SystemClock.uptimeMillis() + WINDOW_MS
        handler.postAtTime(tick, nextDeadline)
        nextDeadline += WINDOW_MS
        return START_STICKY
    }

    private fun flush() {
        val summary = window.take(sound.drain())
        seq += 1
        // NTP time carried by the monotonic clock once any server has answered; the
        // system clock only until then. The ESP32 and the server both run NTP, and a
        // node stamping with the phone's network-set clock was half a second out.
        val now = clock.nowMillis() ?: System.currentTimeMillis()
        val envelope = Contract.Envelope(
            device = config.device,
            firmware = FIRMWARE,
            boot = boot,
            seq = seq,
            // The phone's clock is network-synced, so it is sent. Ingestion still
            // checks it against arrival and falls back to the boot anchor when it
            // disagrees; the node does not get to decide its own clock is right.
            tsSeconds = now / 1000,
            tsMillis = (now % 1000).toInt(),
            uptimeMs = SystemClock.elapsedRealtime() - bootElapsed,
            windowMs = WINDOW_MS,
            quality = 0,
        )
        link?.offer(envelope, summary)
        val net = network
        net?.sampleTraffic()
        NodeBus.update {
            it.copy(latest = summary, network = net?.label ?: it.network,
                    meteredBytes = net?.meteredBytes ?: 0, unmeteredBytes = net?.unmeteredBytes ?: 0)
        }
    }

    private fun startClock() {
        clockThreadRunning = true
        clockThread = Thread({
            while (clockThreadRunning) {
                if (clock.synchronise(NTP_SERVERS)) {
                    val ntp = clock.nowMillis()!!
                    val ahead = System.currentTimeMillis() - ntp
                    val phone = when {
                        ahead > 0 -> "phone clock ${ahead} ms ahead"
                        ahead < 0 -> "phone clock ${-ahead} ms behind"
                        else -> "phone clock exact"
                    }
                    NodeBus.update {
                        it.copy(clock = "NTP ${clock.server} · ±${clock.uncertaintyMillis()} ms · $phone")
                    }
                } else if (clock.anchor == null) {
                    NodeBus.update { it.copy(clock = "system clock · no NTP server answered") }
                }
                // Every 15 minutes, like the ESP32: the phone's crystal drifts by tens
                // of milliseconds an hour, so the anchor is renewed well before that.
                // A failed attempt retries sooner.
                val wait = if (clock.anchor == null) 30_000L else 15 * 60_000L
                try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
            }
        }, "psychron-ntp").apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        // Guarded: if start failed half way, some of these were never created, and
        // a crash in onDestroy would hide the reason start failed.
        if (::handler.isInitialized) handler.removeCallbacks(tick)
        if (::window.isInitialized) window.stop()
        if (::sound.isInitialized) sound.stop()
        clockThreadRunning = false
        clockThread?.interrupt()
        link?.stop()
        link = null
        network?.stop()
        network = null
        if (::thread.isInitialized) thread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        NodeBus.update { it.copy(running = false, link = "stopped", endpoint = null) }
        super.onDestroy()
    }

    private fun goForeground(microphone: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sensor node", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle("Psychron node running")
            .setContentText(if (microphone) "Sensors and microphone level, over mTLS" else "Sensors, over mTLS")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // The microphone type only when the permission was actually granted: asking
        // for it without the permission is a SecurityException on Android 14, and a
        // node that crashes on start records nothing at all.
        val type = if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val WINDOW_MS = 2000
        const val FIRMWARE = "android-0.4.0"
        // The same pool family the ESP32 and the Windows host use, so all three
        // nodes of the system are corrected against one standard.
        private val NTP_SERVERS = listOf("es.pool.ntp.org", "pool.ntp.org", "time.cloudflare.com")
        private const val CHANNEL = "node"
        private const val NOTIFICATION_ID = 1
    }
}
