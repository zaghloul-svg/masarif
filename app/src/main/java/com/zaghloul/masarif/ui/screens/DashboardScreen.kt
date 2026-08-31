package com.zaghloul.masarif.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.analysis.Analytics
import com.zaghloul.masarif.data.Insight
import com.zaghloul.masarif.data.Txn
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.BurnChart
import com.zaghloul.masarif.ui.components.CategoryBreakdown
import com.zaghloul.masarif.ui.components.DonutChart
import com.zaghloul.masarif.ui.components.EmptyState
import com.zaghloul.masarif.ui.components.HistoryBars
import com.zaghloul.masarif.ui.components.InsightCard
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.ui.components.Slice
import com.zaghloul.masarif.ui.components.StatTile
import com.zaghloul.masarif.ui.components.TxnRow
import com.zaghloul.masarif.ui.theme.MasarifColors
import com.zaghloul.masarif.util.Fmt
import kotlin.math.max

@Composable
fun DashboardScreen(
    state: AppState,
    data: AppState.Data,
    onSeeAllTransactions: () -> Unit,
    onSeeInsights: () -> Unit,
    onOpenTxn: (Txn) -> Unit,
    contentPadding: PaddingValues
) {
    val currency = state.repo.prefs.currency
    val cycle = data.cycle

    if (data.totalTxns == 0) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.Center
        ) {
            EmptyState(
                icon = Icons.Filled.ReceiptLong,
                title = "No transactions yet",
                body = "Once notification access and SMS permission are on, every bank alert " +
                    "that arrives is read and sorted automatically. Import your existing SMS " +
                    "history from Settings to fill this in straight away."
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ---- cycle selector
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { state.selectCycleOffset(data.cycleIndex + 1) },
                    enabled = data.cycleIndex < data.cycles.size - 1
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous period")
                }
                Text(
                    cycle?.label ?: "",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(
                    onClick = { state.selectCycleOffset(data.cycleIndex - 1) },
                    enabled = data.cycleIndex > 0
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next period")
                }
            }
        }

        // ---- headline
        item {
            val spend = data.totals.spend
            val income = data.income
            val left = income - spend
            val elapsed = cycle?.elapsedDays() ?: 1
            val perDay = spend / max(1, elapsed)
            val remaining = cycle?.remainingDays() ?: 0

            SectionCard {
                Text(
                    "Spent this period",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    Fmt.money(spend, currency),
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatTile(
                        label = if (data.detectedSalary != null && state.repo.prefs.salary <= 0)
                            "Income (detected)" else "Income",
                        value = if (income > 0) Fmt.money(income, currency) else "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Left",
                        value = if (income > 0) Fmt.money(left, currency) else "—",
                        tone = if (left < 0) MasarifColors.negative else MasarifColors.positive,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Per day",
                        value = Fmt.money(perDay, currency),
                        sub = if (remaining > 0) "$remaining days left" else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ---- burn rate
        if (cycle != null && data.totals.spend > 0) {
            item {
                val cumulative = Analytics.cumulativeDaily(data.cycleTxns, cycle)
                val projected = Analytics.projectedSpend(data.cycleTxns, cycle)
                SectionCard(
                    title = "Pace",
                    subtitle = projected?.let {
                        "On track for ${Fmt.money(it, currency)} by the end of the period"
                    } ?: "Building up a picture of this period"
                ) {
                    BurnChart(
                        cumulative = cumulative,
                        cycleDays = cycle.lengthDays,
                        elapsedDays = cycle.elapsedDays(),
                        ceiling = data.income.takeIf { it > 0 }
                    )
                    if (data.income > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Dashed line is an even spend of your income across the period.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- where it went
        if (data.totals.byCategory.isNotEmpty()) {
            item {
                val folded = MasarifColors.foldToPalette(
                    data.totals.byCategory.entries.map { it.key to it.value }
                )
                SectionCard(title = "Where it went") {
                    DonutChart(
                        slices = folded.map { (c, v) -> Slice(c, v, MasarifColors.category(c)) },
                        centerValue = Fmt.money(data.totals.spend, currency),
                        centerLabel = "${data.totals.count} transactions"
                    )
                    Spacer(Modifier.height(16.dp))
                    CategoryBreakdown(
                        entries = folded,
                        total = data.totals.spend,
                        currency = currency
                    )
                }
            }
        }

        // ---- history
        if (data.history.size > 1) {
            item {
                val bars = data.history.map { (c, v) -> c.shortLabel to v }
                // history is oldest-first; cycles list is newest-first
                val selected = data.history.size - 1 - data.cycleIndex
                SectionCard(
                    title = "Month by month",
                    subtitle = "Spending across the periods on record"
                ) {
                    HistoryBars(
                        data = bars,
                        selectedIndex = selected,
                        currency = currency
                    )
                }
            }
        }

        // ---- top insight
        data.insights.firstOrNull()?.let { top ->
            item {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Biggest opportunity",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onSeeInsights) { Text("All insights") }
                    }
                    Spacer(Modifier.height(6.dp))
                    InsightCard(top, currency)
                }
            }
        }

        // ---- recent
        if (data.cycleTxns.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Recent",
                    trailing = {
                        TextButton(onClick = onSeeAllTransactions) { Text("See all") }
                    }
                ) {
                    Column {
                        data.cycleTxns.take(6).forEachIndexed { i, t ->
                            if (i > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            TxnRow(
                                txn = t,
                                currency = currency,
                                onClick = { onOpenTxn(t) },
                                modifier = Modifier.padding(horizontal = 0.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
