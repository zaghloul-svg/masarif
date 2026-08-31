package com.zaghloul.masarif.analysis

import com.zaghloul.masarif.data.Cat
import com.zaghloul.masarif.data.Conf
import com.zaghloul.masarif.data.Insight
import com.zaghloul.masarif.data.Insight.Severity
import com.zaghloul.masarif.data.Subscription
import com.zaghloul.masarif.data.Txn
import com.zaghloul.masarif.util.Fmt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic savings analysis. Every number below is computed from stored
 * transactions — nothing is estimated by a model, and nothing leaves the phone.
 *
 * Insights only fire when there is enough data to support them, so an app with two
 * weeks of history stays quiet rather than inventing advice.
 */
object InsightEngine {

    data class Config(
        val salary: Double,
        val savingsTarget: Double,
        val cycleStartDay: Int,
        val currency: String = "AED"
    )

    fun generate(
        all: List<Txn>,
        cycle: Analytics.Cycle,
        history: List<Analytics.Cycle>,
        budgets: Map<String, Double>,
        config: Config,
        now: Long = System.currentTimeMillis()
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val cur = all.filter { cycle.contains(it.ts) }
        val totals = Analytics.totals(cur)
        val cy = config.currency

        // Previous complete cycles, most recent first.
        val past = history.filter { it.endTs <= cycle.startTs }.sortedByDescending { it.startTs }
        val income = if (config.salary > 0) config.salary
        else Analytics.detectSalary(all) ?: totals.income

        pace(out, all, cur, cycle, budgets, income, cy, now)
        budgetBreaches(out, cur, cycle, budgets, cy, now)
        categorySpikes(out, all, cur, past, cy)
        subscriptions(out, all, income, cy, now)
        diningPattern(out, cur, cycle, cy)
        smallSpendLeak(out, cur, cy)
        bankFees(out, cur, cy)
        savingsGap(out, totals.spend, income, config, cy, cycle, now)
        merchantConcentration(out, cur, totals.spend, cy)
        wins(out, cur, past, all, cy)
        dataQuality(out, cur)

        return out.sortedWith(compareBy({ it.severity.ordinal }, { -(it.potentialSaving ?: 0.0) }))
    }

    // ------------------------------------------------------------------- pace

    private fun pace(
        out: MutableList<Insight>,
        all: List<Txn>,
        cur: List<Txn>,
        cycle: Analytics.Cycle,
        budgets: Map<String, Double>,
        income: Double,
        cy: String,
        now: Long
    ) {
        if (!cycle.isCurrent) return
        val projected = Analytics.projectedSpend(all, cycle, now) ?: return
        val spent = cur.filter { it.countsAsSpend }.sumOf { it.amount }
        val elapsed = cycle.elapsedDays(now)
        val remaining = cycle.remainingDays(now)
        val perDay = spent / max(1, elapsed)

        val ceiling = when {
            income > 0 -> income
            budgets.isNotEmpty() -> budgets.values.sum()
            else -> return
        }
        val label = if (income > 0) "your income" else "your total budget"

        val over = projected - ceiling
        val safeDaily = if (remaining > 0) (ceiling - spent) / remaining else 0.0

        when {
            over > ceiling * 0.02 -> out += Insight(
                id = "pace_over",
                severity = if (over > ceiling * 0.15) Severity.CRITICAL else Severity.WARNING,
                title = "On track to overspend by ${Fmt.money(over, cy)}",
                detail = "You've spent ${Fmt.money(spent, cy)} in $elapsed days — " +
                    "${Fmt.money(perDay, cy)} a day. At that pace this cycle ends at " +
                    "${Fmt.money(projected, cy)}, above $label of ${Fmt.money(ceiling, cy)}. " +
                    if (remaining > 0 && safeDaily > 0)
                        "Holding to ${Fmt.money(safeDaily, cy)} a day for the remaining " +
                            "${Fmt.plural(remaining, "day")} brings it back in line."
                    else "There is no room left in this cycle.",
                potentialSaving = over
            )

            over < -ceiling * 0.05 && elapsed >= 10 -> out += Insight(
                id = "pace_under",
                severity = Severity.WIN,
                title = "Running ${Fmt.money(-over, cy)} under",
                detail = "Spending is ${Fmt.money(perDay, cy)} a day, which lands this cycle " +
                    "around ${Fmt.money(projected, cy)} against $label of ${Fmt.money(ceiling, cy)}."
            )
        }
    }

    // -------------------------------------------------------------- budgets

    private fun budgetBreaches(
        out: MutableList<Insight>,
        cur: List<Txn>,
        cycle: Analytics.Cycle,
        budgets: Map<String, Double>,
        cy: String,
        now: Long
    ) {
        if (budgets.isEmpty()) return
        val byCat = cur.filter { it.countsAsSpend }
            .groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.amount } }

        val elapsed = cycle.elapsedDays(now).toDouble()
        val len = cycle.lengthDays.toDouble()

        for ((category, limit) in budgets) {
            if (limit <= 0) continue
            val spent = byCat[category] ?: 0.0
            if (spent <= 0) continue

            if (spent > limit) {
                out += Insight(
                    id = "budget_over_$category",
                    severity = Severity.CRITICAL,
                    title = "$category is over budget",
                    detail = "${Fmt.money(spent, cy)} spent against a ${Fmt.money(limit, cy)} " +
                        "limit — ${Fmt.money(spent - limit, cy)} over.",
                    potentialSaving = spent - limit,
                    category = category
                )
                continue
            }

            if (!cycle.isCurrent || elapsed < 5) continue
            val projected = spent / elapsed * len
            if (projected > limit * 1.05) {
                out += Insight(
                    id = "budget_pace_$category",
                    severity = Severity.WARNING,
                    title = "$category is pacing over budget",
                    detail = "${Fmt.money(spent, cy)} of ${Fmt.money(limit, cy)} used with " +
                        "${Fmt.plural(cycle.remainingDays(now), "day")} left. " +
                        "At this rate it finishes near ${Fmt.money(projected, cy)}.",
                    potentialSaving = projected - limit,
                    category = category
                )
            }
        }
    }

    // ------------------------------------------------------------- spikes

    private fun categorySpikes(
        out: MutableList<Insight>,
        all: List<Txn>,
        cur: List<Txn>,
        past: List<Analytics.Cycle>,
        cy: String
    ) {
        if (past.size < 2) return
        val baselineCycles = past.take(3)

        val curByCat = cur.filter { it.countsAsSpend }
            .groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.amount } }

        for ((category, spent) in curByCat) {
            val history = baselineCycles.map { c ->
                all.filter { c.contains(it.ts) && it.countsAsSpend && it.category == category }
                    .sumOf { it.amount }
            }
            if (history.count { it > 0 } < 2) continue
            val baseline = Analytics.median(history)
            if (baseline < 50.0) continue

            val delta = spent - baseline
            if (delta > baseline * 0.4 && delta > 150.0) {
                val pct = (delta / baseline * 100).roundToInt()
                out += Insight(
                    id = "spike_$category",
                    severity = Severity.WARNING,
                    title = "$category is up $pct% this cycle",
                    detail = "${Fmt.money(spent, cy)} against a typical " +
                        "${Fmt.money(baseline, cy)} over the last ${baselineCycles.size} cycles. " +
                        "That is ${Fmt.money(delta, cy)} more than usual.",
                    potentialSaving = delta,
                    category = category
                )
            }
        }
    }

    // ------------------------------------------------------ subscriptions

    private fun subscriptions(
        out: MutableList<Insight>,
        all: List<Txn>,
        income: Double,
        cy: String,
        now: Long
    ) {
        val subs = Analytics.detectSubscriptions(all)
        if (subs.isEmpty()) return

        // Only count ones still billing.
        val live = subs.filter { now - it.lastSeen < 75L * 24 * 60 * 60 * 1000 }
        if (live.isEmpty()) return

        val monthly = live.sumOf { Analytics.monthlyCost(it) }
        val share = if (income > 0) monthly / income else 0.0

        out += Insight(
            id = "subs_total",
            severity = if (share > 0.08) Severity.WARNING else Severity.INFO,
            title = "${Fmt.plural(live.size, "recurring charge")} costing " +
                "${Fmt.money(monthly, cy)} a month",
            detail = buildString {
                append("That is ${Fmt.money(monthly * 12, cy)} a year")
                if (income > 0) append(", ${Fmt.pct(share)} of your monthly income")
                append(". Largest: ")
                append(live.sortedByDescending { Analytics.monthlyCost(it) }.take(3)
                    .joinToString(", ") {
                        "${it.merchant} ${Fmt.money(Analytics.monthlyCost(it), cy)}"
                    })
                append(".")
            },
            potentialSaving = null
        )

        // Several charges in one category is where duplication usually hides.
        val grouped = live.groupBy { it.category }
        for ((category, list) in grouped) {
            if (list.size < 3) continue
            if (category !in setOf(Cat.SUBSCRIPTIONS, Cat.ENTERTAINMENT)) continue
            val cost = list.sumOf { Analytics.monthlyCost(it) }
            out += Insight(
                id = "subs_dupe_$category",
                severity = Severity.INFO,
                title = "${list.size} overlapping $category subscriptions",
                detail = list.joinToString(", ") {
                    "${it.merchant} (${Fmt.money(Analytics.monthlyCost(it), cy)}/mo)"
                } + ". Dropping the least-used one saves about " +
                    "${Fmt.money(cost / list.size, cy)} a month.",
                potentialSaving = cost / list.size,
                category = category
            )
        }

        // A price rise on an existing subscription is easy to miss.
        for (s in live) {
            val charges = all.filter { it.merchantNorm.isNotBlank() && it.countsAsSpend }
                .filter { it.merchant == s.merchant }
                .sortedBy { it.ts }
            if (charges.size < 4) continue
            val older = Analytics.median(charges.dropLast(2).map { it.amount })
            val latest = charges.last().amount
            if (older > 0 && latest > older * 1.15 && latest - older > 5) {
                out += Insight(
                    id = "subs_rise_${s.merchant}",
                    severity = Severity.INFO,
                    title = "${s.merchant} went up",
                    detail = "Charging ${Fmt.money(latest, cy, decimals = true)}, up from " +
                        "${Fmt.money(older, cy, decimals = true)} — " +
                        "${Fmt.money((latest - older) * 12, cy)} more a year.",
                    potentialSaving = (latest - older) * 12
                )
            }
        }
    }

    // ------------------------------------------------------------- dining

    private fun diningPattern(
        out: MutableList<Insight>,
        cur: List<Txn>,
        cycle: Analytics.Cycle,
        cy: String
    ) {
        val dining = cur.filter { it.countsAsSpend && it.category == Cat.DINING }
        if (dining.size < 6) return

        val total = dining.sumOf { it.amount }
        val avg = total / dining.size
        val groceries = cur.filter { it.countsAsSpend && it.category == Cat.GROCERIES }
            .sumOf { it.amount }
        val perWeek = dining.size.toDouble() / max(1.0, cycle.elapsedDays() / 7.0)

        val detail = buildString {
            append("${Fmt.plural(dining.size, "order")} averaging ")
            append("${Fmt.money(avg, cy, decimals = true)}, about ")
            append("${String.format("%.1f", perWeek)} a week.")
            if (groceries > 0) {
                append(" Groceries came to ${Fmt.money(groceries, cy)} over the same period.")
            }
        }

        // Conservative: replacing a quarter of orders, not all of them.
        val saving = total * 0.25
        out += Insight(
            id = "dining_freq",
            severity = if (groceries > 0 && total > groceries * 2) Severity.WARNING else Severity.INFO,
            title = "${Fmt.money(total, cy)} on dining and delivery",
            detail = "$detail Cutting roughly one order in four would keep about " +
                "${Fmt.money(saving, cy)} a cycle, or ${Fmt.money(saving * 12, cy)} a year.",
            potentialSaving = saving,
            category = Cat.DINING
        )
    }

    // ------------------------------------------------------- small spend

    private fun smallSpendLeak(out: MutableList<Insight>, cur: List<Txn>, cy: String) {
        val small = cur.filter { it.countsAsSpend && it.amount < 50.0 }
        if (small.size < 12) return
        val total = small.sumOf { it.amount }
        val spend = cur.filter { it.countsAsSpend }.sumOf { it.amount }
        if (spend <= 0 || total < spend * 0.06) return

        out += Insight(
            id = "small_spend",
            severity = Severity.INFO,
            title = "${Fmt.plural(small.size, "charge")} under ${Fmt.money(50.0, cy)} " +
                "add up to ${Fmt.money(total, cy)}",
            detail = "Individually small, but ${Fmt.pct(total / spend)} of everything you spent " +
                "this cycle. Most common: " +
                small.groupBy { it.merchant }.entries
                    .sortedByDescending { it.value.size }.take(3)
                    .joinToString(", ") { "${it.key} (${it.value.size}x)" } + ".",
            potentialSaving = total * 0.3
        )
    }

    // --------------------------------------------------------------- fees

    private fun bankFees(out: MutableList<Insight>, cur: List<Txn>, cy: String) {
        val fees = cur.filter { it.countsAsSpend && it.category == Cat.FEES }
        if (fees.isEmpty()) return
        val total = fees.sumOf { it.amount }
        if (total < 20.0) return

        out += Insight(
            id = "bank_fees",
            severity = Severity.WARNING,
            title = "${Fmt.money(total, cy)} in bank fees and charges",
            detail = "${Fmt.plural(fees.size, "charge")} this cycle: " +
                fees.groupBy { it.merchant }.entries.take(3)
                    .joinToString(", ") { "${it.key} ${Fmt.money(it.value.sumOf { t -> t.amount }, cy)}" } +
                ". Fees are usually the easiest line to remove — most are waivable by " +
                "changing account type, hitting a minimum balance, or paying on time.",
            potentialSaving = total,
            category = Cat.FEES
        )
    }

    // ------------------------------------------------------------ savings

    private fun savingsGap(
        out: MutableList<Insight>,
        spend: Double,
        income: Double,
        config: Config,
        cy: String,
        cycle: Analytics.Cycle,
        now: Long
    ) {
        if (income <= 0) return
        val kept = income - spend
        val rate = kept / income

        if (config.savingsTarget > 0) {
            val gap = config.savingsTarget - kept
            if (gap > 0 && cycle.isCurrent) {
                val daysLeft = cycle.remainingDays(now)
                out += Insight(
                    id = "savings_gap",
                    severity = if (gap > config.savingsTarget * 0.5) Severity.WARNING else Severity.INFO,
                    title = "${Fmt.money(gap, cy)} short of your savings target",
                    detail = "You've kept ${Fmt.money(kept, cy)} of ${Fmt.money(income, cy)} " +
                        "against a target of ${Fmt.money(config.savingsTarget, cy)}." +
                        if (daysLeft > 0)
                            " Capping spend at ${Fmt.money(max(0.0, (income - config.savingsTarget - spend) / daysLeft), cy)} " +
                                "a day for the rest of the cycle closes it."
                        else "",
                    potentialSaving = gap
                )
            } else if (gap <= 0) {
                out += Insight(
                    id = "savings_met",
                    severity = Severity.WIN,
                    title = "Savings target met",
                    detail = "${Fmt.money(kept, cy)} kept this cycle, " +
                        "${Fmt.money(-gap, cy)} beyond your ${Fmt.money(config.savingsTarget, cy)} target."
                )
            }
        } else {
            out += Insight(
                id = "savings_rate",
                severity = when {
                    rate < 0 -> Severity.CRITICAL
                    rate < 0.1 -> Severity.WARNING
                    rate > 0.2 -> Severity.WIN
                    else -> Severity.INFO
                },
                title = "Savings rate ${Fmt.pct(rate)}",
                detail = if (rate < 0)
                    "You spent ${Fmt.money(-(income - spend), cy)} more than came in this cycle."
                else "${Fmt.money(kept, cy)} of ${Fmt.money(income, cy)} left over. " +
                    "Set a savings target in Budgets to track this against a goal."
            )
        }
    }

    // ------------------------------------------------------ concentration

    private fun merchantConcentration(
        out: MutableList<Insight>,
        cur: List<Txn>,
        spend: Double,
        cy: String
    ) {
        if (spend <= 0) return
        val top = Analytics.topMerchants(cur, 1).firstOrNull() ?: return
        val share = top.second / spend
        if (share < 0.22 || top.second < 300) return

        out += Insight(
            id = "concentration",
            severity = Severity.INFO,
            title = "${top.first} is ${Fmt.pct(share)} of your spending",
            detail = "${Fmt.money(top.second, cy)} this cycle across " +
                "${Fmt.plural(cur.count { it.merchant == top.first && it.countsAsSpend }, "transaction")}. " +
                "One merchant this large is usually the fastest place to find a saving."
        )
    }

    // ---------------------------------------------------------------- wins

    private fun wins(
        out: MutableList<Insight>,
        cur: List<Txn>,
        past: List<Analytics.Cycle>,
        all: List<Txn>,
        cy: String
    ) {
        if (past.size < 2) return
        val baseline = past.take(3)
        val curByCat = cur.filter { it.countsAsSpend }
            .groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.amount } }

        var best: Triple<String, Double, Double>? = null
        for ((category, spent) in curByCat) {
            val history = baseline.map { c ->
                all.filter { c.contains(it.ts) && it.countsAsSpend && it.category == category }
                    .sumOf { it.amount }
            }
            if (history.count { it > 0 } < 2) continue
            val med = Analytics.median(history)
            if (med < 100) continue
            val drop = med - spent
            if (drop > med * 0.25 && drop > 100) {
                if (best == null || drop > best!!.second) best = Triple(category, drop, med)
            }
        }

        best?.let { (category, drop, med) ->
            out += Insight(
                id = "win_$category",
                severity = Severity.WIN,
                title = "$category down ${Fmt.money(drop, cy)}",
                detail = "${Fmt.money(curByCat[category] ?: 0.0, cy)} this cycle against a " +
                    "typical ${Fmt.money(med, cy)}. Keeping that up is " +
                    "${Fmt.money(drop * 12, cy)} a year.",
                category = category
            )
        }
    }

    // -------------------------------------------------------- data quality

    private fun dataQuality(out: MutableList<Insight>, cur: List<Txn>) {
        val low = cur.count { it.confidence == Conf.LOW }
        if (low < 3) return
        out += Insight(
            id = "quality",
            severity = Severity.INFO,
            title = "${Fmt.plural(low, "transaction")} need a quick check",
            detail = "These were read from alerts the parser was unsure about. Confirming " +
                "them in Transactions makes every number above more accurate."
        )
    }

    /** Total quantified opportunity, for the headline figure on the Insights screen. */
    fun totalOpportunity(insights: List<Insight>): Double =
        insights.filter { it.severity != Severity.WIN }
            .mapNotNull { it.potentialSaving }
            .filter { it > 0 }
            .sum()

    fun subscriptionsOf(all: List<Txn>): List<Subscription> = Analytics.detectSubscriptions(all)

    internal fun safeAbs(v: Double) = abs(v)
}
