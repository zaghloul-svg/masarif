package com.zaghloul.masarif.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Fmt {

    private val dayMonth = SimpleDateFormat("d MMM", Locale.US)
    private val dayMonthYear = SimpleDateFormat("d MMM yyyy", Locale.US)
    private val dayMonthTime = SimpleDateFormat("d MMM, HH:mm", Locale.US)
    private val timeOnly = SimpleDateFormat("HH:mm", Locale.US)

    /** "AED 1,234" or "AED 1,234.56" when [decimals] is set. */
    fun money(value: Double, currency: String = "AED", decimals: Boolean = false): String {
        val sign = if (value < 0) "-" else ""
        val v = abs(value)
        val body = if (decimals) String.format(Locale.US, "%,.2f", v)
        else String.format(Locale.US, "%,d", v.roundToLong())
        return "$sign$currency $body"
    }

    /** Drops the currency; for dense table cells. */
    fun amount(value: Double, decimals: Boolean = true): String =
        if (decimals) String.format(Locale.US, "%,.2f", value)
        else String.format(Locale.US, "%,d", value.roundToLong())

    /** "12.4k" for axis labels. */
    fun compact(value: Double): String {
        val v = abs(value)
        return when {
            v >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000)
            v >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000)
            else -> String.format(Locale.US, "%.0f", value)
        }
    }

    fun pct(fraction: Double, decimals: Int = 0): String =
        String.format(Locale.US, "%.${decimals}f%%", fraction * 100)

    fun date(ts: Long): String = dayMonth.format(Date(ts))

    fun dateFull(ts: Long): String = dayMonthYear.format(Date(ts))

    fun dateTime(ts: Long): String = dayMonthTime.format(Date(ts))

    /** "Today, 14:05" / "Yesterday" / "3 Aug". */
    fun friendly(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today, ${timeOnly.format(Date(ts))}"
            sameYear && dayDiff == 1 -> "Yesterday, ${timeOnly.format(Date(ts))}"
            sameYear -> dayMonthTime.format(Date(ts))
            else -> dayMonthYear.format(Date(ts))
        }
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String =
        if (n == 1) "$n $one" else "$n $many"
}
