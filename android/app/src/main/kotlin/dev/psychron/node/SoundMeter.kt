package dev.psychron.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sound level, and nothing that could be played back.
 *
 * PCM is read into one reusable buffer and reduced as it arrives: a running sum of
 * squares and a peak for the unweighted level, and an A-weighting filter feeding
 * 125 ms blocks for the noise figures. The buffer is overwritten by the next read.
 * Nothing is stored and no sample survives the window it was measured in. The
 * contract has no field that could carry audio; this class has no code path that
 * could produce one.
 */
class SoundMeter(private val context: Context) {

    data class Levels(val rmsDbfs: Double, val peakDbfs: Double, val noise: NoiseLevels.Window?)

    private val lock = Any()
    private var sumSquares = 0.0
    private var count = 0L
    private var peak = 0
    private var noise: NoiseLevels? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    val available: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !available) return
        running = true
        thread = Thread({ loop() }, "psychron-sound").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    /** Levels for everything read since the last call, then reset. */
    fun drain(): Levels? = synchronized(lock) {
        if (count == 0L) return null
        val rms = sqrt(sumSquares / count)
        val result = Levels(dbfs(rms), dbfs(peak.toDouble()), noise?.drain())
        sumSquares = 0.0; count = 0; peak = 0
        result
    }

    private fun loop() {
        // 48 kHz where the phone offers it, which every current one does. The
        // A-weighting curve reaches 12 kHz and a 16 kHz stream would fold everything
        // above 8 kHz back into the band being measured.
        val (rate, recorder) = RATES.firstNotNullOfOrNull { r -> open(r)?.let { r to it } } ?: run {
            running = false
            return
        }
        val weighting = AWeighting(rate.toDouble())
        synchronized(lock) { noise = NoiseLevels(rate) }

        val buffer = ShortArray(2048)
        try {
            recorder.startRecording()
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                var sq = 0.0
                var pk = 0
                synchronized(lock) {
                    val levels = noise!!
                    for (i in 0 until n) {
                        val s = buffer[i].toInt()
                        sq += (s * s).toDouble()
                        val a = abs(s)
                        if (a > pk) pk = a
                        levels.add(weighting.step(s.toDouble()))
                    }
                    sumSquares += sq
                    count += n
                    if (pk > peak) peak = pk
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    private fun open(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return try {
            @Suppress("MissingPermission")
            AudioRecord(source(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The least-processed input the phone offers. MIC applies automatic gain
     * control on most devices, which would flatten exactly the changes in level
     * this is trying to measure; UNPROCESSED is the honest source where it exists,
     * and VOICE_RECOGNITION — which by specification has AGC off — where it does not.
     */
    private fun source(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    companion object {
        private val RATES = listOf(48000, 44100, 16000)

        /**
         * Relative to a full-scale 16-bit sample. Digital silence has no logarithm,
         * so it is reported at the contract's floor rather than as minus infinity,
         * which JSON cannot carry and every aggregate would be poisoned by.
         */
        fun dbfs(amplitude: Double): Double =
            if (amplitude <= 0.0) -160.0 else max(-160.0, 20.0 * log10(amplitude / 32768.0))
    }
}
