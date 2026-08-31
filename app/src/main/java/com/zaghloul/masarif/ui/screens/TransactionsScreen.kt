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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.data.Cat
import com.zaghloul.masarif.data.Conf
import com.zaghloul.masarif.data.Txn
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.EmptyState
import com.zaghloul.masarif.ui.components.TxnRow
import com.zaghloul.masarif.util.Fmt

@Composable
fun TransactionsScreen(
    state: AppState,
    data: AppState.Data,
    onOpenTxn: (Txn) -> Unit,
    contentPadding: PaddingValues
) {
    val currency = state.repo.prefs.currency

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var allPeriods by remember { mutableStateOf(false) }
    var needsReview by remember { mutableStateOf(false) }
    var allTxns by remember { mutableStateOf<List<Txn>>(emptyList()) }

    LaunchedEffect(allPeriods, data.totalTxns) {
        if (allPeriods) allTxns = state.repo.db.allTxns()
    }

    val source = if (allPeriods) allTxns else data.cycleTxns
    val filtered = source.filter { t ->
        (category == null || t.category == category) &&
            (!needsReview || t.confidence == Conf.LOW) &&
            (query.isBlank() ||
                t.merchant.contains(query, true) ||
                t.category.contains(query, true) ||
                t.note.contains(query, true) ||
                t.raw.contains(query, true))
    }

    val shownTotal = filtered.filter { it.countsAsSpend }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search merchant, category or alert text") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = allPeriods,
                onClick = { allPeriods = !allPeriods },
                label = { Text(if (allPeriods) "All time" else "This period") }
            )
            FilterChip(
                selected = needsReview,
                onClick = { needsReview = !needsReview },
                label = { Text("Needs review") }
            )
            FilterChip(
                selected = category == null,
                onClick = { category = null },
                label = { Text("All categories") }
            )
            Cat.ALL.forEach { c ->
                val count = source.count { it.category == c }
                if (count == 0 && category != c) return@forEach
                FilterChip(
                    selected = category == c,
                    onClick = { category = if (category == c) null else c },
                    label = { Text("$c ($count)") }
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "${Fmt.plural(filtered.size, "transaction")} · ${Fmt.money(shownTotal, currency)} spent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(4.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = "Nothing matches",
                body = if (source.isEmpty())
                    "No transactions in this period yet."
                else "Try clearing the filters or searching for something else."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    bottom = contentPadding.calculateBottomPadding() + 24.dp
                )
            ) {
                items(filtered, key = { it.id }) { t ->
                    TxnRow(txn = t, currency = currency, onClick = { onOpenTxn(t) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
