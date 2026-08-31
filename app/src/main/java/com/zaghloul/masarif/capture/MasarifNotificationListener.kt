package com.zaghloul.masarif.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zaghloul.masarif.data.Repo
import com.zaghloul.masarif.data.Src
import java.util.concurrent.Executors

/**
 * Reads notifications posted by other apps and hands anything bank-shaped to the
 * parser. Needs "Notification access", which the user grants in system settings —
 * there is no runtime permission dialog for this one.
 */
class MasarifNotificationListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val repo = Repo.get(applicationContext)
        if (!repo.prefs.captureNotifications) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (pkg in repo.prefs.mutedPackages) return

        val n = sbn.notification ?: return

        // Group headers carry no detail, and ongoing notifications (music, downloads)
        // are never transaction alerts.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // Prefer the expanded text: banks truncate the collapsed line.
        val detail = bigText.ifBlank { text }
        val body = listOf(title, detail, sub)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .trim()

        if (body.isBlank()) return

        val ts = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        worker.execute {
            try {
                repo.intake(
                    body = body,
                    sender = pkg,
                    ts = ts,
                    source = Src.NOTIFICATION,
                    sourcePkg = pkg
                )
            } catch (_: Exception) {
                // A malformed notification must never take the listener down.
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** True when the user has granted notification access to this app. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(context, MasarifNotificationListener::class.java)
            return flat.split(':').any {
                val cn = ComponentName.unflattenFromString(it)
                cn != null && cn.packageName == me.packageName
            }
        }
    }
}
