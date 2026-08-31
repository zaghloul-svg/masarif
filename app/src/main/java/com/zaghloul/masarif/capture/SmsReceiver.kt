package com.zaghloul.masarif.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.zaghloul.masarif.data.Repo
import com.zaghloul.masarif.data.Src
import java.util.concurrent.Executors

/**
 * Captures bank SMS as it arrives. In the UAE most transaction alerts still come
 * by SMS rather than app push, so this is the primary source.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val repo = Repo.get(context.applicationContext)
        if (!repo.prefs.captureSms) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            null
        } ?: return
        if (messages.isEmpty()) return

        // A long alert arrives as several PDUs. Join them per sender, in order,
        // or the amount and the merchant end up in different fragments.
        val bySender = LinkedHashMap<String, StringBuilder>()
        var ts = System.currentTimeMillis()
        for (m in messages) {
            val from = m.originatingAddress.orEmpty()
            val part = m.messageBody.orEmpty()
            if (part.isBlank()) continue
            bySender.getOrPut(from) { StringBuilder() }.append(part)
            if (m.timestampMillis > 0) ts = m.timestampMillis
        }
        if (bySender.isEmpty()) return

        val pending = goAsync()
        WORKER.execute {
            try {
                for ((sender, sb) in bySender) {
                    repo.intake(
                        body = sb.toString().trim(),
                        sender = sender,
                        ts = ts,
                        source = Src.SMS,
                        sourcePkg = ""
                    )
                }
            } catch (_: Exception) {
                // Never crash the broadcast; a missed alert is recoverable by backfill.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val WORKER = Executors.newSingleThreadExecutor()
    }
}
