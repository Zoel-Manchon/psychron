package dev.psychron.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager

/**
 * The serving cell as the modem reports it: technology, RSRP, RSRQ, SINR, band.
 *
 * Signal strength is read every window; it is cached by the platform and costs
 * nothing. The band needs the full cell list, which needs the location permission
 * and wakes more of the modem, so it is refreshed every thirty seconds — a phone
 * does not change band between two windows often enough to be worth more.
 *
 * NR is reported whenever the modem has an NR measurement, which in non-standalone
 * 5G is the secondary carrier beside an LTE anchor: what a user calls "being on 5G",
 * and what the coverage map is for.
 */
class RadioMonitor(private val context: Context) {

    data class Cell(val rat: String, val rsrpDbm: Double, val rsrqDb: Double, val sinrDb: Double?, val band: Int?)

    private val tm = context.getSystemService(TelephonyManager::class.java)
    private var bands: Map<String, Int> = emptyMap()
    private var bandsReadAt = Long.MIN_VALUE / 2

    fun current(): Cell? {
        val strength = runCatching { tm.signalStrength }.getOrNull() ?: return null
        val nr = strength.getCellSignalStrengths(CellSignalStrengthNr::class.java)
            .firstOrNull { valid(it.ssRsrp) && valid(it.ssRsrq) }
        val lte = strength.getCellSignalStrengths(CellSignalStrengthLte::class.java)
            .firstOrNull { valid(it.rsrp) && valid(it.rsrq) }
        refreshBands()
        return when {
            nr != null -> Cell("nr", nr.ssRsrp.toDouble(), nr.ssRsrq.toDouble(),
                               nr.ssSinr.takeIf(::valid)?.toDouble(), bands["nr"])
            lte != null -> Cell("lte", lte.rsrp.toDouble(), lte.rsrq.toDouble(),
                                lte.rssnr.takeIf(::valid)?.toDouble(), bands["lte"])
            else -> null
        }
    }

    private fun refreshBands() {
        val now = SystemClock.elapsedRealtime()
        if (now - bandsReadAt < 30_000) return
        bandsReadAt = now
        if (Build.VERSION.SDK_INT < 30) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val cells = runCatching {
            @Suppress("MissingPermission")
            tm.allCellInfo
        }.getOrNull() ?: return
        val found = mutableMapOf<String, Int>()
        for (c in cells.filter(CellInfo::isRegistered)) {
            when (c) {
                is CellInfoLte -> (c.cellIdentity as CellIdentityLte).bands.firstOrNull()?.let { found["lte"] = it }
                is CellInfoNr -> (c.cellIdentity as CellIdentityNr).bands.firstOrNull()?.let { found["nr"] = it }
            }
        }
        bands = found
    }

    private fun valid(v: Int) = v != CellInfo.UNAVAILABLE && v != Int.MIN_VALUE
}
