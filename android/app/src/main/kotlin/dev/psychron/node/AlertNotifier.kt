package dev.psychron.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Alerts from the server, shown the way the phone shows anything urgent.
 *
 * One notification per kind and device, replaced as the state changes and removed
 * when the alert clears: a storm warning that stays on screen after the pressure has
 * recovered is a warning nobody believes the next time.
 */
class AlertNotifier(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pressure falling quickly, a node gone silent, a hot battery"
            },
        )
    }

    /**
     * A message from psychron/alerts/. Anything that is not a well-formed alert — the
     * transport checks publish probes on the same tree — is ignored rather than shown.
     */
    fun handle(topic: String, payload: String) {
        if (payload.isEmpty()) return                  // a retained message being cleared
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val kind = o.optString("kind").takeIf { it.isNotEmpty() } ?: return
        val device = o.optString("device").takeIf { it.isNotEmpty() } ?: return
        val state = o.optString("state")
        val message = o.optString("message")
        val id = "$kind/$device".hashCode()

        when (state) {
            "raised" -> {
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                                                     PendingIntent.FLAG_IMMUTABLE)
                nm.notify(id, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_node)
                    .setContentTitle(message.ifEmpty { kind })
                    .setContentText("$device · psychron")
                    .setContentIntent(open)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setAutoCancel(false)
                    .build())
                NodeBus.update { it.copy(alerts = it.alerts + ("$kind/$device" to message)) }
            }
            "cleared" -> {
                nm.cancel(id)
                NodeBus.update { it.copy(alerts = it.alerts - "$kind/$device") }
            }
        }
    }

    private companion object {
        const val CHANNEL = "alerts"
    }
}
