package com.zaghloul.masarif.ui

import android.content.Context
import com.zaghloul.masarif.analysis.Analytics
import com.zaghloul.masarif.analysis.InsightEngine
import com.zaghloul.masarif.data.Insight
import com.zaghloul.masarif.data.Repo
import com.zaghloul.masarif.data.Subscription
import com.zaghloul.masarif.data.Txn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped state holder. Recomputes everything the UI shows whenever the
 * repository signals a change, so screens stay declarative and never query directly.
 *
 * Application-scoped rather than a ViewModel so a rotation or a trip through the
 * permission screens does not throw away the derived analysis.
 */
class AppState private constructor(val repo: Repo) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Data(
        val loading: Boolean = true,
        val cycles: List<Analytics.Cycle> = emptyList(),
        val cycleIndex: Int = 0,
        val cycleTxns: List<Txn> = emptyList(),
        val totals: Analytics.Totals = Analytics.Totals(0.0, 0.0, emptyMap(), 0, 0.0),
        val insights: List<Insight> = emptyList(),
        val subscriptions: List<Subscription> = emptyList(),
        val budgets: Map<String, Double> = emptyMap(),
        val income: Double = 0.0,
        val detectedSalary: Double? = null,
        val unmatchedCount: Int = 0,
        val totalTxns: Int = 0,
        /** Spend per cycle across the loaded history, oldest first. */
        val history: List<Pair<Analytics.Cycle, Double>> = emptyList(),
        val recent: List<Txn> = emptyList()
    ) {
        val cycle: Analytics.Cycle?
            get() = cycles.getOrNull(cycleIndex)

        val opportunity: Double
            get() = InsightEngine.totalOpportunity(insights)
    }

    private val _data = MutableStateFlow(Data())
    val data: StateFlow<Data> = _data

    /** Which cycle the user is looking at; 0 is the newest. */
    private var selectedOffset = 0

    init {
        scope.launch {
            repo.revision.collect { reload() }
        }
        scope.launch { reload() }
    }

    fun selectCycleOffset(offset: Int) {
        selectedOffset = offset.coerceAtLeast(0)
        scope.launch { reload() }
    }

    fun refresh() {
        scope.launch { reload() }
    }

    private fun reload() {
        val prefs = repo.prefs
        val startDay = prefs.cycleStartDay

        val all = repo.db.allTxns()
        val earliest = all.minOfOrNull { it.ts }

        // Show enough cycles to cover stored history, at least six.
        val monthsBack = earliest?.let {
            val months = ((System.currentTimeMillis() - it) / (30L * 24 * 60 * 60 * 1000)).toInt()
            (months + 2).coerceIn(6, 36)
        } ?: 6

        val cyclesOldestFirst = Analytics.recentCycles(monthsBack, startDay)
        val cyclesNewestFirst = cyclesOldestFirst.reversed()
        val index = selectedOffset.coerceIn(0, (cyclesNewestFirst.size - 1).coerceAtLeast(0))
        val cycle = cyclesNewestFirst.getOrNull(index)

        val cycleTxns = cycle?.let { c -> all.filter { c.contains(it.ts) } } ?: emptyList()
        val totals = Analytics.totals(cycleTxns)
        val budgets = repo.db.budgets()

        val detectedSalary = Analytics.detectSalary(all)
        val income = when {
            prefs.salary > 0 -> prefs.salary
            detectedSalary != null -> detectedSalary
            else -> totals.income
        }

        val insights = if (cycle != null) {
            InsightEngine.generate(
                all = all,
                cycle = cycle,
                history = cyclesOldestFirst,
                budgets = budgets,
                config = InsightEngine.Config(
                    salary = income,
                    savingsTarget = prefs.savingsTarget,
                    cycleStartDay = startDay,
                    currency = prefs.currency
                )
            )
        } else emptyList()

        val history = cyclesOldestFirst.map { c ->
            c to all.filter { c.contains(it.ts) && it.countsAsSpend }.sumOf { it.amount }
        }

        _data.value = Data(
            loading = false,
            cycles = cyclesNewestFirst,
            cycleIndex = index,
            cycleTxns = cycleTxns,
            totals = totals,
            insights = insights,
            subscriptions = Analytics.detectSubscriptions(all),
            budgets = budgets,
            income = income,
            detectedSalary = detectedSalary,
            unmatchedCount = repo.db.unmatchedCount(),
            totalTxns = all.size,
            history = history,
            recent = all.take(12)
        )
    }

    /** Runs [block] off the main thread; the repo bump refreshes the UI. */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    companion object {
        @Volatile
        private var instance: AppState? = null

        fun get(context: Context): AppState =
            instance ?: synchronized(this) {
                instance ?: AppState(Repo.get(context.applicationContext)).also { instance = it }
            }
    }
}
