package com.zaghloul.masarif.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.data.Cat
import com.zaghloul.masarif.data.Dir
import com.zaghloul.masarif.data.Txn
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.ui.theme.MasarifColors
import com.zaghloul.masarif.util.Fmt

@Composable
fun TxnDetailScreen(
    state: AppState,
    txn: Txn,
    onDone: () -> Unit,
    contentPadding: PaddingValues
) {
    val currency = state.repo.prefs.currency
    var category by remember { mutableStateOf(txn.category) }
    var applyToAll by remember { mutableStateOf(true) }
    var isTransfer by remember { mutableStateOf(txn.isTransfer) }
    var excluded by remember { mutableStateOf(txn.excluded) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp, end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        SectionCard {
            Text(
                txn.merchant,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                (if (txn.direction == Dir.CREDIT) "+" else "−") +
                    Fmt.money(txn.amount, txn.currency, decimals = true),
                style = MaterialTheme.typography.displaySmall,
                color = if (txn.direction == Dir.CREDIT) MasarifColors.positive
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                Fmt.dateFull(txn.ts) + " · " + Fmt.dateTime(txn.ts).substringAfter(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (txn.account.isNotBlank()) AssistChip({}, { Text("Card ••${txn.account}") })
                AssistChip({}, { Text(txn.source.lowercase()) })
                AssistChip({}, { Text(txn.confidence.lowercase()) })
            }
            txn.balance?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Balance after: ${Fmt.money(it, txn.currency, decimals = true)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(
            title = "Category",
            subtitle = "Fixing one here teaches the app for every future alert"
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Cat.ALL.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "Apply to every transaction from ${txn.merchant}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        SectionCard(title = "Counting") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow(
                    title = "Internal transfer",
                    body = "Money moved between your own accounts. Kept on record, never counted as spending.",
                    checked = isTransfer,
                    onChange = { isTransfer = it }
                )
                ToggleRow(
                    title = "Exclude from totals",
                    body = "Leave this out of every chart and insight.",
                    checked = excluded,
                    onChange = { excluded = it }
                )
            }
        }

        SectionCard(title = "Original alert") {
            Text(
                txn.raw,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    state.launch { state.repo.deleteTxn(txn.id) }
                    onDone()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Delete") }

            Button(
                onClick = {
                    state.launch {
                        if (category != txn.category) {
                            state.repo.setCategory(txn, category, applyToAll)
                        }
                        if (isTransfer != txn.isTransfer || excluded != txn.excluded) {
                            state.repo.setFlags(txn.id, isTransfer, excluded)
                        }
                    }
                    onDone()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
