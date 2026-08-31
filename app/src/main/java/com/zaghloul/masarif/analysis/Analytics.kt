package com.zaghloul.masarif.analysis

import com.zaghloul.masarif.data.Cat
import com.zaghloul.masarif.data.Dir
import com.zaghloul.masarif.data.Subscription
import com.zaghloul.masarif.data.Txn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Aggregation over stored transactions. Pure functions, no Android dependencies,
 * so the numbers can be reasoned about independently of the UI.
 */
object Analytics {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * A budget period. Defaults to the calendar month, but anchoring it to payday
     * is what most people actually budget against.
     */
    data class Cycle(val label: String, val shortLabel: String, val startTs: Long, val endTs: Long) {
        val lengthDays: Int get() = max(1, ((endTs - startTs) / DAY_MS).toInt())

        fun contains(ts: Long) = ts >= startTs && ts < endTs

        /** Days elapsed so far, clamped to the cycle length. */
        fun elapsedDays(now: Long = System.currentTimeMillis()): Int {
            if (now <= startTs) return 0
            if (now >= endTs) return lengthDays
            return max(1, ((now - startTs) / DAY_MS).toInt() + 1)
        }

        fun remainingDays(now: Long = System.currentTimeMillis()): Int =
            max(0, lengthDays - elapsedDays(now))

        val isCurrent: Boolean get() = System.currentTimeMillis().let { it in startTs until endTs }
    }

    private fun cal(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }

    private fun startOfDay(c: Calendar) = c.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** The cycle containing [ts], for a period that restarts on [startDay]. */
    fun cycleFor(ts: Long, startDay: Int): Cycle {
        val day = startDay.coerceIn(1, 28)
        val c = startOfDay(cal(ts))
        if (c.get(Calendar.DAY_OF_MONTH) < day) c.add(Calendar.MONTH, -1)
        c.set(Calendar.DAY_OF_MONTH, day)
        val start = c.timeInMillis
        val endCal = (c.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val end = endCal.timeInMillis
        return Cycle(labelFor(start, end, day), shortLabelFor(start, day), start, end)
    }

    /** [count] cycles ending with the one containing now, oldest first. */
    fun recentCycles(count: Int, startDay: Int, now: Long = System.currentTimeMillis()): List<Cycle> {
        val out = ArrayList<Cycle>(count)
        var cursor = cycleFor(now, startDay)
        repeat(count) {
            out += cursor
            cursor = cycleFor(cursor.startTs - DAY_MS, startDay)
        }
        return out.reversed()
    }

    private val MONTH_YEAR = SimpleDateFormat("MMMM yyyy", Locale.US)
    private val DAY_MONTH = SimpleDateFormat("d MMM", Locale.US)
    private val MONTH_SHORT = SimpleDateFormat("MMM", Locale.US)

    private fun labelFor(start: Long, end: Long, startDay: Int): String =
        if (startDay == 1) MONTH_YEAR.format(start)
        else "${DAY_MONTH.format(start)} – ${DAY_MONTH.format(end - DAY_MS)}"

    private fun shortLabelFor(start: Long, startDay: Int): String =
        if (startDay == 1) MONTH_SHORT.format(start) else DAY_MONTH.format(start)

    // ============================================================== aggregates

    data class Totals(
        val spend: Double,
        val income: Double,
        val byCategory: Map<String, Double>,
        val count: Int,
        val transfers: Double
    ) {
        val net: Double get() = income - spend
        val savingsRate: Double get() = if (income > 0) net / income else 0.0
    }

    fun totals(txns: List<Txn>): Totals {
        var spend = 0.0
        var income = 0.0
        var transfers = 0.0
        var count = 0
        val byCat = HashMap<String, Double>()
        for (t in txns) {
            when {
                t.countsAsSpend -> {
                    spend += t.amount
                    byCat[t.category] = (byCat[t.category] ?: 0.0) + t.amount
                    count++
                }
                t.countsAsIncome -> {
                    income += t.amount
                    count++
                }
                t.isTransfer && !t.excluded -> transfers += t.amount
            }
        }
        // A LinkedHashMap built from a sorted list, NOT toSortedMap with a value
        // comparator: a TreeMap treats "compares equal" as "same key", so two
        // categories with identical totals would silently collapse into one.
        val ordered = byCat.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .associateTo(LinkedHashMap()) { it.key to it.value }
        return Totals(spend, income, ordered, count, transfers)
    }

    fun spendIn(txns: List<Txn>, cycle: Cycle): Double =
        txns.filter { cycle.contains(it.ts) && it.countsAsSpend }.sumOf { it.amount }

    fun topMerchants(txns: List<Txn>, limit: Int = 8): List<Pair<String, Double>> =
        txns.filter { it.countsAsSpend }
            .groupBy { it.merchant.ifBlank { "Unknown" } }
            .map { (m, list) -> m to list.sumOf { it.amount } }
            .sortedByDescending { it.second }
            .take(limit)

    /** Cumulative spend per day of the cycle, for the burn-rate chart. */
    fun cumulativeDaily(txns: List<Txn>, cycle: Cycle): List<Double> {
        val days = cycle.lengthDays
        val perDay = DoubleArray(days)
        for (t in txns) {
            if (!cycle.contains(t.ts) || !t.countsAsSpend) continue
            val idx = ((t.ts - cycle.startTs) / DAY_MS).toInt().coerceIn(0, days - 1)
            perDay[idx] += t.amount
        }
        var run = 0.0
        return perDay.map { run += it; run }
    }

    /**
     * Straight-line projection of where this cycle lands.
     * Returns null before there is enough of the cycle to extrapolate from.
     */
    fun projectedSpend(txns: List<Txn>, cycle: Cycle, now: Long = System.currentTimeMillis()): Double? {
        val elapsed = cycle.elapsedDays(now)
        if (elapsed < 3) return null
        val spend = spendIn(txns, cycle)
        if (spend <= 0.0) return null
        return spend / elapsed * cycle.lengthDays
    }

    // ============================================================ subscriptions

    /**
     * A merchant billing a similar amount on a regular cadence.
     * Requires at least three charges so a coincidence does not qualify.
     */
    fun detectSubscriptions(all: List<Txn>): List<Subscription> {
        val debits = all.filter { it.countsAsSpend }
        return debits
            .groupBy { it.merchantNorm }
            .mapNotNull { (norm, list) ->
                if (norm.isBlank() || list.size < 3) return@mapNotNull null
                val sorted = list.sortedBy { it.ts }

                val intervals = sorted.zipWithNext { a, b -> (b.ts - a.ts).toDouble() / DAY_MS }
                    .filter { it > 0.5 }
                if (intervals.size < 2) return@mapNotNull null
                val medianInterval = median(intervals)

                val cadence = when {
                    medianInterval in 25.0..36.0 -> medianInterval   // monthly
                    medianInterval in 6.0..8.5 -> medianInterval     // weekly
                    medianInterval in 85.0..95.0 -> medianInterval   // quarterly
                    medianInterval in 350.0..380.0 -> medianInterval // annual
                    else -> return@mapNotNull null
                }

                // Intervals must be consistent, not merely average out.
                if (intervals.any { abs(it - medianInterval) > medianInterval * 0.35 }) {
                    return@mapNotNull null
                }

                val amounts = sorted.map { it.amount }
                val medianAmount = median(amounts)
                if (medianAmount <= 0.0) return@mapNotNull null
                // Amounts must be stable; a shop you visit monthly is not a subscription.
                if (amounts.any { abs(it - medianAmount) > max(2.0, medianAmount * 0.18) }) {
                    return@mapNotNull null
                }

                Subscription(
                    merchant = sorted.last().merchant,
                    typicalAmount = medianAmount,
                    occurrences = sorted.size,
                    lastSeen = sorted.last().ts,
                    avgIntervalDays = cadence,
                    category = sorted.last().category
                )
            }
            .sortedByDescending { it.typicalAmount * (30.0 / it.avgIntervalDays) }
    }

    /** Normalises any cadence to what it costs per month. */
    fun monthlyCost(s: Subscription): Double = s.typicalAmount * (30.0 / s.avgIntervalDays)

    // =================================================================== salary

    /**
     * Largest credit that recurs roughly monthly. Used when no salary is set by hand.
     */
    fun detectSalary(all: List<Txn>): Double? {
        val credits = all.filter { it.countsAsIncome }
        if (credits.isEmpty()) return null

        val labelled = credits.filter {
            it.category == Cat.INCOME && it.merchant.contains("salary", ignoreCase = true)
        }
        val pool = labelled.ifEmpty { credits }

        // Bucket by magnitude so month-to-month variation still groups together.
        val buckets = pool.groupBy { (it.amount / 500.0).toInt() }
        val best = buckets.values
            .filter { it.size >= 2 }
            .maxByOrNull { group -> group.sumOf { it.amount } }
            ?: return pool.maxByOrNull { it.amount }?.amount

        return median(best.map { it.amount })
    }

    /** Day of month that salary usually lands on, for the budget cycle default. */
    fun detectSalaryDay(all: List<Txn>): Int? {
        val salaryTxns = all.filter {
            it.countsAsIncome && it.merchant.contains("salary", ignoreCase = true)
        }
        if (salaryTxns.size < 2) return null
        val days = salaryTxns.map { cal(it.ts).get(Calendar.DAY_OF_MONTH).toDouble() }
        return median(days).toInt().coerceIn(1, 28)
    }

    // ==================================================================== utils

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    fun isWeekend(ts: Long): Boolean {
        // UAE weekend is Saturday and Sunday, and Friday afternoon for many.
        val d = cal(ts).get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }
}
