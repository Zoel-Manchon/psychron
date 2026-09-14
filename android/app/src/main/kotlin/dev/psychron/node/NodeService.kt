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
    private var wakeLock: PowerManager.WakeLock? = null

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

        link = MqttLink(this, config, WINDOW_MS).apply { start() }
        window.start()
        sound.start()

        NodeBus.update {
            it.copy(running = true, sensors = window.present.toMap(), microphone = sound.available)
        }

        nextDeadline = SystemClock.uptimeMillis() + WINDOW_MS
        handler.postAtTime(tick, nextDeadline)
        nextDeadline += WINDOW_MS
        return START_STICKY
    }

    private fun flush() {
        val summary = window.take(sound.drain())
        seq += 1
        val now = System.currentTimeMillis()
        val envelope = Contract.Envelope(
            device = config.device,
            firmware = FIRMWARE,
            boot = boot,
            seq = seq,
            // The phone's clock is network-synced, so it is sent. Ingestion still
            // checks it against arrival and falls back to the boot anchor when it
            // disagrees; the node does not get to decide its own clock is right.
            tsSeconds = now / 1000,
            uptimeMs = SystemClock.elapsedRealtime() - bootElapsed,
            windowMs = WINDOW_MS,
            quality = 0,
        )
        link?.offer(envelope, summary)
        NodeBus.update { it.copy(latest = summary) }
    }

    override fun onDestroy() {
        // Guarded: if start failed half way, some of these were never created, and
        // a crash in onDestroy would hide the reason start failed.
        if (::handler.isInitialized) handler.removeCallbacks(tick)
        if (::window.isInitialized) window.stop()
        if (::sound.isInitialized) sound.stop()
        link?.stop()
        link = null
        if (::thread.isInitialized) thread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        NodeBus.update { it.copy(running = false, link = "stopped") }
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
        const val FIRMWARE = "android-0.1.0"
        private const val CHANNEL = "node"
        private const val NOTIFICATION_ID = 1
    }
}
