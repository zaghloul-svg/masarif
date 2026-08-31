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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.data.Cat
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.BudgetMeter
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.ui.theme.MasarifColors
import com.zaghloul.masarif.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun BudgetsScreen(
    state: AppState,
    data: AppState.Data,
    contentPadding: PaddingValues
) {
    val prefs = state.repo.prefs
    val currency = prefs.currency

    var salary by remember { mutableStateOf(if (prefs.salary > 0) prefs.salary.roundToInt().toString() else "") }
    var target by remember {
        mutableStateOf(if (prefs.savingsTarget > 0) prefs.savingsTarget.roundToInt().toString() else "")
    }
    var cycleDay by remember { mutableStateOf(prefs.cycleStartDay.toString()) }
    val pendingBudgets = remember { mutableStateMapOf<String, Double>() }

    val spentByCat = data.totals.byCategory

    // Recomputing every insight on each keystroke is wasteful, so settle first
    // and then reload once. Values are written to storage immediately either way.
    LaunchedEffect(salary, target, cycleDay, pendingBudgets.toMap()) {
        delay(450)
        if (pendingBudgets.isNotEmpty()) {
            val staged = pendingBudgets.toMap()
            pendingBudgets.clear()
            withContext(Dispatchers.IO) {
                staged.forEach { (category, limit) -> state.repo.db.setBudget(category, limit) }
            }
        }
        state.refresh()
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
                title = "Your month",
                subtitle = "Leave income blank to use the salary the app detects from your credits"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = salary,
                        onValueChange = {
                            salary = it.filter { c -> c.isDigit() }
                            prefs.salary = salary.toDoubleOrNull() ?: 0.0
                        },
                        label = { Text("Monthly income ($currency)") },
                        placeholder = {
                            Text(
                                data.detectedSalary?.let { "Detected ${Fmt.money(it, currency)}" }
                                    ?: "Not set"
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = target,
                        onValueChange = {
                            target = it.filter { c -> c.isDigit() }
                            prefs.savingsTarget = target.toDoubleOrNull() ?: 0.0
                        },
                        label = { Text("Savings target per month ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = cycleDay,
                        onValueChange = {
                            cycleDay = it.filter { c -> c.isDigit() }.take(2)
                            cycleDay.toIntOrNull()?.let { d ->
                                if (d in 1..28) prefs.cycleStartDay = d
                            }
                        },
                        label = { Text("Budget period starts on day") },
                        supportingText = {
                            Text(
                                "Set this to your payday so the period matches when money arrives. " +
                                    "1 means the calendar month."
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            val budgeted = data.budgets.values.sum()
            SectionCard(
                title = "Category budgets",
                subtitle = if (budgeted > 0)
                    "${Fmt.money(budgeted, currency)} budgeted across ${data.budgets.size} categories"
                else "Set a limit on the categories you want to hold down. Blank means no limit."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cat.SPENDING.forEach { category ->
                        BudgetRow(
                            category = category,
                            spent = spentByCat[category] ?: 0.0,
                            limit = data.budgets[category] ?: 0.0,
                            currency = currency,
                            onChange = { v -> pendingBudgets[category] = v }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetRow(
    category: String,
    spent: Double,
    limit: Double,
    currency: String,
    onChange: (Double) -> Unit
) {
    var text by remember(category, limit) {
        mutableStateOf(if (limit > 0) limit.roundToInt().toString() else "")
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(category, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (limit > 0)
                        "${Fmt.money(spent, currency)} of ${Fmt.money(limit, currency)}"
                    else "${Fmt.money(spent, currency)} spent",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (limit > 0 && spent > limit) MasarifColors.negative
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.filter { c -> c.isDigit() }
                    onChange(text.toDoubleOrNull() ?: 0.0)
                },
                placeholder = { Text("—") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(110.dp)
            )
        }
        if (limit > 0) {
            Spacer(Modifier.height(8.dp))
            BudgetMeter(
                spent = spent,
                limit = limit,
                color = MasarifColors.category(category)
            )
        }
    }
}
