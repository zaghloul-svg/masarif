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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.analysis.Analytics
import com.zaghloul.masarif.analysis.LlmCoach
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.EmptyState
import com.zaghloul.masarif.ui.components.InsightCard
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.util.Fmt

@Composable
fun InsightsScreen(
    state: AppState,
    data: AppState.Data,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues
) {
    val prefs = state.repo.prefs
    val currency = prefs.currency

    var coachText by remember { mutableStateOf(prefs.lastCoachText) }
    var coachLoading by remember { mutableStateOf(false) }
    var coachError by remember { mutableStateOf<String?>(null) }

    if (data.insights.isEmpty() && data.totalTxns == 0) {
        Column(Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.Center) {
            EmptyState(
                icon = Icons.Filled.Lightbulb,
                title = "Nothing to analyse yet",
                body = "Insights appear once there are transactions to work from. " +
                    "The more history the app has, the sharper they get."
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        item {
            SectionCard {
                Text(
                    "Identified this period",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    Fmt.money(data.opportunity, currency),
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Total of the savings opportunities below. These are estimates from your " +
                        "own transactions, not guarantees — the subscription and fee figures " +
                        "are the most reliable of them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(data.insights, key = { it.id }) { insight ->
            InsightCard(insight, currency)
        }

        // ---- recurring charges
        if (data.subscriptions.isNotEmpty()) {
            item {
                val monthly = data.subscriptions.sumOf { Analytics.monthlyCost(it) }
                SectionCard(
                    title = "Recurring charges",
                    subtitle = "${Fmt.money(monthly, currency)} a month · " +
                        "${Fmt.money(monthly * 12, currency)} a year"
                ) {
                    Column {
                        data.subscriptions.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.merchant, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${s.category} · every ${s.avgIntervalDays.toInt()} days · " +
                                            "seen ${s.occurrences}x · last ${Fmt.date(s.lastSeen)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    Fmt.money(Analytics.monthlyCost(s), currency) + "/mo",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- optional AI coach
        item {
            SectionCard(
                title = "AI coach",
                subtitle = if (prefs.hasLlmKey)
                    "Sends category totals" +
                        (if (prefs.llmShareMerchants) " and merchant names" else "") +
                        " to ${prefs.llmProvider}. Never your card numbers or alert text."
                else "Optional. Add your own API key in Settings to turn this on."
            ) {
                Column {
                    if (!prefs.hasLlmKey) {
                        TextButton(onClick = onOpenSettings) { Text("Set up in Settings") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !coachLoading,
                                onClick = {
                                    coachLoading = true
                                    coachError = null
                                    state.launch {
                                        val cycle = data.cycle
                                        val prompt = LlmCoach.buildPrompt(
                                            cycleLabel = cycle?.label ?: "This period",
                                            txns = data.cycleTxns,
                                            insights = data.insights,
                                            income = data.income,
                                            savingsTarget = prefs.savingsTarget,
                                            shareMerchants = prefs.llmShareMerchants,
                                            currency = currency
                                        )
                                        when (val r = LlmCoach.request(prefs, prompt)) {
                                            is LlmCoach.Result.Ok -> {
                                                prefs.lastCoachText = r.text
                                                prefs.lastCoachAt = System.currentTimeMillis()
                                                coachText = r.text
                                            }
                                            is LlmCoach.Result.Error -> coachError = r.message
                                        }
                                        coachLoading = false
                                    }
                                }
                            ) {
                                if (coachLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.height(0.dp))
                                } else {
                                    Text(if (coachText.isBlank()) "Get advice" else "Refresh advice")
                                }
                            }
                        }

                        coachError?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (coachText.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(coachText, style = MaterialTheme.typography.bodyMedium)
                            if (prefs.lastCoachAt > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Generated ${Fmt.friendly(prefs.lastCoachAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
