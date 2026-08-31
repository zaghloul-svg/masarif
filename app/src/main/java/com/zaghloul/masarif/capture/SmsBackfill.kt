package com.zaghloul.masarif.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.zaghloul.masarif.data.Repo
import com.zaghloul.masarif.data.Src

/**
 * Imports transaction alerts already sitting in the SMS inbox.
 *
 * This is what makes the app useful immediately: instead of waiting a month for
 * data to accumulate, the first run reconstructs however much history the phone
 * still holds — usually many months of it.
 */
object SmsBackfill {

    data class Progress(val scanned: Int, val imported: Int, val queued: Int, val total: Int)

    data class Result(val scanned: Int, val imported: Int, val queued: Int)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param months how far back to read
     * @param onProgress called on the calling thread every 25 messages
     */
    fun run(
        context: Context,
        months: Int = 12,
        onProgress: (Progress) -> Unit = {}
    ): Result {
        if (!hasPermission(context)) return Result(0, 0, 0)

        val repo = Repo.get(context.applicationContext)
        val since = System.currentTimeMillis() - months.toLong() * 31L * 24 * 60 * 60 * 1000

        var scanned = 0
        var imported = 0
        var queued = 0

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} ASC"
            )
        } catch (_: Exception) {
            null
        } ?: return Result(0, 0, 0)

        cursor.use { c ->
            val total = c.count
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)

            while (c.moveToNext()) {
                scanned++
                val body = if (iBody >= 0) c.getString(iBody).orEmpty() else ""
                val sender = if (iAddr >= 0) c.getString(iAddr).orEmpty() else ""
                val date = if (iDate >= 0) c.getLong(iDate) else System.currentTimeMillis()
                if (body.isBlank()) continue

                when (repo.intake(body, sender, date, Src.SMS)) {
                    is Repo.Intake.Stored -> imported++
                    is Repo.Intake.Queued -> queued++
                    else -> Unit
                }

                if (scanned % 25 == 0) onProgress(Progress(scanned, imported, queued, total))
            }
            onProgress(Progress(scanned, imported, queued, total))
        }

        repo.prefs.backfillDone = true
        repo.bump()
        return Result(scanned, imported, queued)
    }
}
