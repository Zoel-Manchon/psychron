package dev.psychron.node

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.security.SecureRandom

/**
 * The node. Runs as a foreground service so the record does not stop when the
 * screen does, and so the microphone and location indicators stay visible for as
 * long as either is in use — which is the honest way to hold them.
 */
class NodeService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var window: SensorWindow
    private lateinit var sound: SoundMeter
    private lateinit var location: LocationTracker
    private lateinit var radio: RadioMonitor
    private var outbox: Outbox? = null
    private var link: MqttLink? = null
    private var network: NetworkWatch? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val clock = TrustedClock { SystemClock.elapsedRealtime() }
    @Volatile private var clockThreadRunning = false
    private var clockThread: Thread? = null

    private lateinit var config: NodeConfig
    private var boot = 0L
    private var seq = 0L
    private var eventSeq = 0L
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
        goForeground()

        // A fresh boot id per session, drawn once. With `up` measured from this same
        // instant, ingestion can place a window even if it arrives an hour late —
        // exactly as it does for the ESP32 after a power cut.
        boot = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        seq = 0
        eventSeq = 0
        bootElapsed = SystemClock.elapsedRealtime()

        thread = HandlerThread("psychron-sensors").apply { start() }
        handler = Handler(thread.looper)
        window = SensorWindow(this, handler, ::onVibration)
        location = LocationTracker(this, thread.looper)
        radio = RadioMonitor(this)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "psychron:node")
            .apply { acquire() }

        startClock()
        val alerts = AlertNotifier(this)
        val box = Outbox(this).also { outbox = it }
        // The link observes the watch, so it is built first and the watch started
        // after: a change reported before anyone listens is a change missed.
        val watch = NetworkWatch(this).also { network = it }
        link = MqttLink(this, config, WINDOW_MS, watch, box, alerts::handle)
        watch.start()
        link?.start()
        window.start()
        sound.start()
        location.start()

        NodeBus.update {
            it.copy(running = true, sensors = window.present.toMap() + ("location" to location.available),
                    microphone = sound.available, queued = box.count,
                    meteredBytes = 0, unmeteredBytes = 0, startedElapsed = SystemClock.elapsedRealtime(),
                    identity = identityLabel(), events = 0, lastEvent = null, vibration = null,
                    fix = null, locationEnabled = location.enabled, oldestQueuedMs = box.oldestCreatedMs(),
                    lastAckElapsed = 0)
        }

        nextDeadline = SystemClock.uptimeMillis() + WINDOW_MS
        handler.postAtTime(tick, nextDeadline)
        nextDeadline += WINDOW_MS
        return START_STICKY
    }

    /** NTP time carried by the monotonic clock once any server has answered; the system clock until then. */
    private fun wallNow(): Long = clock.nowMillis() ?: System.currentTimeMillis()

    private fun flush() {
        val levels = sound.drain()
        val fix = location.current()
        val cell = radio.current()
        val net = network
        val summary = window.take().copy(
            soundRmsDbfs = levels?.rmsDbfs,
            soundPeakDbfs = levels?.peakDbfs,
            laeqDbfs = levels?.noise?.laeq,
            lamaxDbfs = levels?.noise?.lamax,
            l10Dbfs = levels?.noise?.l10,
            l90Dbfs = levels?.noise?.l90,
            lat = fix?.lat, lon = fix?.lon, locAccM = fix?.accM,
            altMslM = fix?.altMslM, altAccM = fix?.altAccM, speedMs = fix?.speedMs,
            cellRat = cell?.rat, rsrpDbm = cell?.rsrpDbm, rsrqDb = cell?.rsrqDb,
            sinrDb = cell?.sinrDb, cellBand = cell?.band,
            netVia = net?.via, netVpn = net?.vpn, rttMs = link?.drainRtt(),
        )
        seq += 1
        val now = wallNow()
        val envelope = Contract.Envelope(
            device = config.device,
            firmware = FIRMWARE,
            boot = boot,
            seq = seq,
            // The phone's clock is NTP-disciplined, so it is sent. Ingestion still
            // checks it against arrival and falls back to the boot anchor when it
            // disagrees; the node does not get to decide its own clock is right.
            tsSeconds = now / 1000,
            tsMillis = (now % 1000).toInt(),
            uptimeMs = SystemClock.elapsedRealtime() - bootElapsed,
            windowMs = WINDOW_MS,
            quality = 0,
        )
        Contract.encode(envelope, summary)?.let { link?.offer("psychron/v2/${config.device}/sample", it) }

        net?.sampleTraffic()
        val fixForScreen = location.last()
        val locationOn = location.enabled
        val detector = window.vibrationStatus()
        // Guarded: this runs on the sensor thread, and a stop closes the outbox from
        // the main one. A query that lands in between must not crash the node on its
        // way out.
        val oldest = runCatching { outbox?.oldestCreatedMs() }.getOrNull()
        NodeBus.update {
            it.copy(latest = summary, network = net?.label ?: it.network,
                    meteredBytes = net?.meteredBytes ?: 0, unmeteredBytes = net?.unmeteredBytes ?: 0,
                    batching = link?.batching == true, fix = fixForScreen, locationEnabled = locationOn,
                    vibration = detector, oldestQueuedMs = oldest)
        }
    }

    /** On the sensor thread, from the detector, when an event has closed. */
    private fun onVibration(e: VibrationDetector.Event) {
        // The detector speaks the sensor clock; the contract speaks wall time and
        // uptime. Both are the same instant counted back from now.
        val agoMs = (SystemClock.elapsedRealtimeNanos() - e.startNanos) / 1_000_000
        val startWall = wallNow() - agoMs
        val startUptime = e.startNanos / 1_000_000 - bootElapsed
        if (startUptime < 0) return                 // began before this session did
        eventSeq += 1
        val vibration = Contract.Vibration(e.durationMs, e.pgaMs2, e.staLta, e.freqHz)
        val envelope = Contract.Envelope(
            device = config.device, firmware = FIRMWARE, boot = boot, seq = eventSeq,
            tsSeconds = startWall / 1000, tsMillis = (startWall % 1000).toInt(),
            uptimeMs = startUptime, windowMs = WINDOW_MS, quality = 0,
        )
        Contract.encodeEvent(envelope, vibration)?.let { link?.offer("psychron/v2/${config.device}/event", it) }
        NodeBus.update {
            it.copy(events = it.events + 1, lastEvent = vibration, lastEventElapsed = SystemClock.elapsedRealtime())
        }
    }

    private fun identityLabel(): String = when (Provisioning.identity(this)) {
        Provisioning.Identity.HARDWARE -> "hardware key · ${runCatching { DeviceKey.where().name }.getOrDefault("?")}"
        Provisioning.Identity.LEGACY_FILE -> "file key · enrol with infra/provision-phone.sh"
        Provisioning.Identity.PENDING -> "hardware key waiting for its certificate"
        Provisioning.Identity.NONE -> "no key"
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
        if (::location.isInitialized) location.stop()
        clockThreadRunning = false
        clockThread?.interrupt()
        link?.stop()
        link = null
        network?.stop()
        network = null
        outbox?.close()
        outbox = null
        if (::thread.isInitialized) thread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        NodeBus.update { it.copy(running = false, link = "stopped", endpoint = null) }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sensor node", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val microphone = granted(Manifest.permission.RECORD_AUDIO)
        val position = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_node)
            .setContentTitle("Psychron node running")
            .setContentText(listOfNotNull("sensors", "sound level".takeIf { microphone },
                                          "location".takeIf { position }).joinToString(", ") + ", over mTLS")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // Each type only when its permission was actually granted: declaring the
        // microphone or location type without it is a SecurityException from Android
        // 14 on, and a node that crashes on start records nothing at all.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (position) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val WINDOW_MS = 2000
        const val FIRMWARE = "android-0.5.1"
        // The same pool family the ESP32 and the host use, so all three clocks of
        // the system are corrected against one standard.
        private val NTP_SERVERS = listOf("es.pool.ntp.org", "pool.ntp.org", "time.cloudflare.com")
        private const val CHANNEL = "node"
        private const val NOTIFICATION_ID = 1
    }
}
