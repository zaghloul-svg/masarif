package com.zaghloul.masarif.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.zaghloul.masarif.data.ParseRule
import com.zaghloul.masarif.data.RuleBuilder
import com.zaghloul.masarif.data.Unmatched
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.EmptyState
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.util.Fmt

/**
 * Where an unreadable alert becomes a permanent rule.
 *
 * Rather than asking for a regex, the message is broken into tappable tokens: pick
 * which token is the amount, which run is the merchant, and the app writes the
 * pattern and re-runs it over everything still in the queue.
 */
@Composable
fun RuleLabScreen(
    state: AppState,
    contentPadding: PaddingValues
) {
    var editing by remember { mutableStateOf<Unmatched?>(null) }
    var queue by remember { mutableStateOf<List<Unmatched>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ParseRule>>(emptyList()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(reloadKey) {
        queue = state.repo.db.unmatched()
        rules = state.repo.db.rules()
    }

    val target = editing
    if (target != null) {
        RuleBuilderPane(
            state = state,
            message = target,
            onCancel = { editing = null },
            onSaved = { recovered ->
                editing = null
                banner = "Rule saved — $recovered message${if (recovered == 1) "" else "s"} recovered"
                reloadKey++
            },
            contentPadding = contentPadding
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        banner?.let {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "How this works",
                subtitle = "Bank alerts that the parser could not read land here. Teach it one " +
                    "message and it handles every future alert of that shape."
            ) {}
        }

        if (queue.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Inbox,
                    title = "Queue is empty",
                    body = "Every bank-shaped message so far has been read successfully. " +
                        "Anything the parser trips over will show up here."
                )
            }
        } else {
            item {
                Text(
                    "${Fmt.plural(queue.size, "message")} waiting",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(queue, key = { it.id }) { u ->
                SectionCard {
                    Column {
                        Text(
                            u.sender.ifBlank { u.sourcePkg }.ifBlank { "Unknown sender" } +
                                " · " + Fmt.friendly(u.ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(u.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { editing = u }) { Text("Teach the parser") }
                            TextButton(onClick = {
                                state.launch { state.repo.dismissUnmatched(u.id) }
                                reloadKey++
                            }) { Text("Not a transaction") }
                        }
                    }
                }
            }
        }

        if (rules.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Your rules",
                    subtitle = "Tried before the built-in heuristics, highest priority first"
                ) {
                    Column {
                        rules.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "matched ${r.hits} time${if (r.hits == 1) "" else "s"}" +
                                            if (r.senderFilter.isNotBlank()) " · ${r.senderFilter}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    state.launch { state.repo.deleteRule(r.id) }
                                    reloadKey++
                                }) {
                                    Icon(Icons.Filled.Delete, "Delete rule")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Tok(val text: String, val start: Int, val end: Int)

private fun tokenize(s: String): List<Tok> {
    val out = mutableListOf<Tok>()
    Regex("\\S+").findAll(s).forEach {
        out += Tok(it.value, it.range.first, it.range.last + 1)
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleBuilderPane(
    state: AppState,
    message: Unmatched,
    onCancel: () -> Unit,
    onSaved: (Int) -> Unit,
    contentPadding: PaddingValues
) {
    val tokens = remember(message.id) { tokenize(message.body) }
    var field by remember { mutableStateOf(RuleBuilder.Field.AMOUNT) }
    val picks = remember(message.id) {
        androidx.compose.runtime.mutableStateMapOf<RuleBuilder.Field, Set<Int>>()
    }
    var saving by remember { mutableStateOf(false) }

    fun selectionsFor(): List<RuleBuilder.Selection> =
        picks.mapNotNull { (f, idxs) ->
            if (idxs.isEmpty()) return@mapNotNull null
            val chosen = idxs.mapNotNull { tokens.getOrNull(it) }
            if (chosen.isEmpty()) return@mapNotNull null
            RuleBuilder.Selection(f, chosen.minOf { it.start }, chosen.maxOf { it.end })
        }

    val built = remember(picks.toMap(), message.id) {
        RuleBuilder.build(message.body, selectionsFor())
    }

    val preview = remember(built) {
        built?.let {
            RuleBuilder.preview(
                ParseRule(
                    name = "preview",
                    pattern = it.pattern,
                    gAmount = it.gAmount,
                    gMerchant = it.gMerchant,
                    gAccount = it.gAccount,
                    gBalance = it.gBalance,
                    priority = 500
                ),
                message.body,
                message.sender
            )
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "Pick the amount",
                subtitle = "Choose a field below, then tap the words in the message that hold it. " +
                    "Only the amount is required."
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleBuilder.Field.entries.forEach { f ->
                        FilterChip(
                            selected = field == f,
                            onClick = { field = f },
                            label = {
                                val n = picks[f]?.size ?: 0
                                Text(f.name.lowercase() + if (n > 0) " ✓" else "")
                            }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "The message") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tokens.forEachIndexed { i, tok ->
                        val owner = RuleBuilder.Field.entries.firstOrNull { picks[it]?.contains(i) == true }
                        val selected = owner != null
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val current = picks[field].orEmpty()
                                // A token belongs to one field at a time.
                                RuleBuilder.Field.entries.forEach { f ->
                                    if (f != field) picks[f] = picks[f].orEmpty() - i
                                }
                                picks[field] = if (i in current) current - i else current + i
                            },
                            label = {
                                Text(
                                    if (owner != null) "${tok.text} · ${owner.name.take(3).lowercase()}"
                                    else tok.text
                                )
                            }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Preview",
                subtitle = if (built == null) "Pick the amount to build a rule"
                else "This is what the rule reads out of the message"
            ) {
                val txn = preview?.txn
                if (txn == null) {
                    Text(
                        if (built == null) "No amount selected yet."
                        else "The rule did not match. Try including the words either side of the amount.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PreviewLine("Amount", Fmt.money(txn.amount, txn.currency, decimals = true))
                        PreviewLine("Direction", txn.direction.lowercase())
                        PreviewLine("Merchant", txn.merchant)
                        if (txn.account.isNotBlank()) PreviewLine("Card", "••${txn.account}")
                        txn.balance?.let {
                            PreviewLine("Balance", Fmt.money(it, txn.currency, decimals = true))
                        }
                        PreviewLine("Category", txn.category)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    enabled = built != null && preview?.txn != null && !saving,
                    onClick = {
                        val b = built ?: return@Button
                        saving = true
                        state.launch {
                            state.repo.saveRule(
                                ParseRule(
                                    name = message.sender.ifBlank { message.sourcePkg }
                                        .ifBlank { "Custom rule" },
                                    pattern = b.pattern,
                                    gAmount = b.gAmount,
                                    gMerchant = b.gMerchant,
                                    gAccount = b.gAccount,
                                    gBalance = b.gBalance,
                                    senderFilter = message.sender.ifBlank { message.sourcePkg },
                                    priority = 500
                                )
                            )
                            val result = state.repo.reprocessUnmatched()
                            saving = false
                            onSaved(result.recovered)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (saving) "Saving…" else "Save rule") }
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
