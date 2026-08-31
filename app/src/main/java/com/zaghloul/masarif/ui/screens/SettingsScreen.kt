package com.zaghloul.masarif.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zaghloul.masarif.capture.MasarifNotificationListener
import com.zaghloul.masarif.capture.SmsBackfill
import com.zaghloul.masarif.data.Prefs
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.util.Fmt
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: AppState,
    data: AppState.Data,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val prefs = state.repo.prefs

    var notifOn by remember { mutableStateOf(MasarifNotificationListener.isEnabled(context)) }
    var smsOn by remember { mutableStateOf(SmsBackfill.hasPermission(context)) }
    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(context)) }

    var backfilling by remember { mutableStateOf(false) }
    var backfillProgress by remember { mutableStateOf<SmsBackfill.Progress?>(null) }
    var backfillResult by remember { mutableStateOf<String?>(null) }

    var confirmClear by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(prefs.hasLlmKey) }
    var provider by remember { mutableStateOf(prefs.llmProvider) }
    var model by remember { mutableStateOf(prefs.llmModel) }
    var shareMerchants by remember { mutableStateOf(prefs.llmShareMerchants) }
    var notice by remember { mutableStateOf<String?>(null) }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { smsOn = SmsBackfill.hasPermission(context) }

    // Re-check the permissions the user may have changed in system settings.
    LaunchedEffect(data.totalTxns, backfilling) {
        notifOn = MasarifNotificationListener.isEnabled(context)
        smsOn = SmsBackfill.hasPermission(context)
        batteryOk = isBatteryUnrestricted(context)
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

        notice?.let {
            item {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        // ---------------------------------------------------------- capture
        item {
            SectionCard(
                title = "Capture",
                subtitle = "All three matter. Samsung's battery manager is the usual reason " +
                    "capture stops after a few days."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PermissionRow(
                        title = "Notification access",
                        body = "Reads alerts from your banking and wallet apps.",
                        granted = notifOn,
                        actionLabel = "Open settings",
                        onAction = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    )
                    PermissionRow(
                        title = "SMS access",
                        body = "Reads bank SMS, and lets the app import your existing history.",
                        granted = smsOn,
                        actionLabel = "Grant",
                        onAction = {
                            smsLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECEIVE_SMS,
                                    Manifest.permission.READ_SMS
                                )
                            )
                        }
                    )
                    PermissionRow(
                        title = "Unrestricted battery",
                        body = "Stops One UI from putting the app to sleep and missing alerts.",
                        granted = batteryOk,
                        actionLabel = "Allow",
                        onAction = { requestIgnoreBattery(context) }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    ToggleLine(
                        "Capture notifications", prefs.captureNotifications
                    ) { prefs.captureNotifications = it }
                    ToggleLine("Capture SMS", prefs.captureSms) { prefs.captureSms = it }
                }
            }
        }

        // -------------------------------------------------------- backfill
        item {
            SectionCard(
                title = "Import SMS history",
                subtitle = "Reads transaction alerts already in your inbox so the charts have " +
                    "months of data immediately."
            ) {
                Column {
                    backfillProgress?.let { p ->
                        LinearProgressIndicator(
                            progress = { if (p.total > 0) p.scanned.toFloat() / p.total else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${p.scanned} of ${p.total} scanned · ${p.imported} imported",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    backfillResult?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                    }
                    Button(
                        enabled = smsOn && !backfilling,
                        onClick = {
                            backfilling = true
                            backfillResult = null
                            state.launch {
                                val r = SmsBackfill.run(context, months = 12) { p ->
                                    backfillProgress = p
                                }
                                backfillResult = "Scanned ${r.scanned} messages · " +
                                    "${r.imported} transactions imported · " +
                                    "${r.queued} sent to the Rule Lab"
                                backfilling = false
                                backfillProgress = null
                            }
                        }
                    ) { Text(if (backfilling) "Importing…" else "Import last 12 months") }
                    if (!smsOn) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Grant SMS access above first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------- AI coach
        item {
            SectionCard(
                title = "AI coach",
                subtitle = "Optional. Uses your own API key and your own billing. " +
                    "Everything else in the app works offline."
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("none" to "Off", "anthropic" to "Anthropic", "openai" to "OpenAI")
                            .forEach { (value, label) ->
                                FilterChip(
                                    selected = provider == value,
                                    onClick = {
                                        provider = value
                                        prefs.llmProvider = value
                                        if (model.isBlank()) {
                                            model = when (value) {
                                                "anthropic" -> Prefs.DEFAULT_ANTHROPIC_MODEL
                                                "openai" -> Prefs.DEFAULT_OPENAI_MODEL
                                                else -> ""
                                            }
                                            prefs.llmModel = model
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                    }

                    if (provider != "none") {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; prefs.llmModel = it },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(if (keySaved) "Replace API key" else "API key") },
                            placeholder = { Text(if (keySaved) "A key is saved" else "") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = apiKey.isNotBlank(),
                                onClick = {
                                    prefs.llmApiKey = apiKey.trim()
                                    apiKey = ""
                                    keySaved = true
                                    notice = "API key saved, encrypted on this device."
                                }
                            ) { Text("Save key") }
                            if (keySaved) {
                                OutlinedButton(onClick = {
                                    prefs.llmApiKey = ""
                                    keySaved = false
                                    notice = "API key removed."
                                }) { Text("Remove") }
                            }
                        }

                        ToggleLine(
                            "Send merchant names",
                            shareMerchants
                        ) { shareMerchants = it; prefs.llmShareMerchants = it }
                        Text(
                            "Off by default. With it off the coach sees category totals only. " +
                                "Card numbers, balances and raw alert text are never sent either way.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ data
        item {
            SectionCard(
                title = "Data",
                subtitle = "${data.totalTxns} transactions stored on this device"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { state.launch { shareCsv(context, state) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export as CSV") }

                    OutlinedButton(
                        onClick = {
                            state.launch {
                                val n = state.repo.recategorizeAll()
                                notice = "Re-sorted $n transactions."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Re-apply categories to all transactions") }

                    OutlinedButton(
                        onClick = {
                            state.launch {
                                val r = state.repo.reprocessUnmatched()
                                notice = "Recovered ${r.recovered}; ${r.stillUnmatched} still queued."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Re-run the parser over the Rule Lab queue") }

                    TextButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete all data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            SectionCard(title = "About") {
                Text(
                    "Masarif reads bank alerts on this phone and analyses them here. " +
                        "There is no account, no server, and no analytics — the only network " +
                        "request the app can make is the optional AI coach, using your own key.\n\n" +
                        "Because it reads SMS it cannot be distributed through the Play Store, " +
                        "which is why you installed it directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "Removes all ${data.totalTxns} stored transactions, the Rule Lab queue and " +
                        "your rules. Budgets and settings stay. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    state.launch {
                        state.repo.db.deleteAllTxns()
                        state.repo.db.clearUnmatched()
                        state.repo.prefs.backfillDone = false
                        state.repo.bump()
                        notice = "All transaction data deleted."
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                com.zaghloul.masarif.ui.components.Badge(
                    if (granted) "on" else "off",
                    if (granted) com.zaghloul.masarif.ui.theme.MasarifColors.positive
                    else com.zaghloul.masarif.ui.theme.MasarifColors.negative
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            Spacer(Modifier.width(10.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ToggleLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = { value = it; onChange(it) })
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
private fun requestIgnoreBattery(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun shareCsv(context: Context, state: AppState) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "masarif-export.csv")
        file.writeText(state.repo.exportCsv())
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Masarif export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Export CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) {
        // Sharing is best-effort; the data stays in the database either way.
    }
}
