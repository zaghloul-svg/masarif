package com.zaghloul.masarif.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.capture.MasarifNotificationListener
import com.zaghloul.masarif.capture.SmsBackfill
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.components.Badge
import com.zaghloul.masarif.ui.components.SectionCard
import com.zaghloul.masarif.ui.theme.MasarifColors

@Composable
fun OnboardingScreen(state: AppState, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = state.repo.prefs

    var notifOn by remember { mutableStateOf(MasarifNotificationListener.isEnabled(context)) }
    var smsOn by remember { mutableStateOf(SmsBackfill.hasPermission(context)) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SmsBackfill.Progress?>(null) }
    var imported by remember { mutableStateOf<Int?>(null) }
    var tick by remember { mutableStateOf(0) }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { smsOn = SmsBackfill.hasPermission(context); tick++ }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    LaunchedEffect(tick) {
        notifOn = MasarifNotificationListener.isEnabled(context)
        smsOn = SmsBackfill.hasPermission(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Text("Masarif", style = MaterialTheme.typography.displaySmall)
        Text(
            "Reads your bank alerts on this phone and turns them into a picture of where " +
                "your money goes. Nothing leaves the device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        SectionCard(
            title = "1 · Notification access",
            subtitle = "Lets the app read purchase alerts pushed by your banking apps. " +
                "Android has no popup for this one — it opens a system settings list, " +
                "where you switch on Masarif."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    if (notifOn) "granted" else "not yet",
                    if (notifOn) MasarifColors.positive else MasarifColors.warning
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text(if (notifOn) "Review" else "Open settings") }
            }
        }

        SectionCard(
            title = "2 · SMS access",
            subtitle = "Most UAE banks still send transaction alerts by SMS. This also lets " +
                "the app import the alerts already in your inbox, so you start with history " +
                "instead of an empty app."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    if (smsOn) "granted" else "not yet",
                    if (smsOn) MasarifColors.positive else MasarifColors.warning
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !smsOn,
                    onClick = {
                        smsLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.READ_SMS
                            )
                        )
                    }
                ) { Text(if (smsOn) "Granted" else "Grant") }
            }
        }

        SectionCard(
            title = "3 · Import your history",
            subtitle = "Reads the last 12 months of bank SMS. This is what makes the charts " +
                "useful straight away."
        ) {
            Column {
                progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.scanned.toFloat() / p.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${p.scanned} of ${p.total} scanned · ${p.imported} found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }
                imported?.let {
                    Text(
                        "$it transactions imported.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    enabled = smsOn && !importing,
                    onClick = {
                        importing = true
                        state.launch {
                            val r = SmsBackfill.run(context, months = 12) { progress = it }
                            imported = r.imported
                            importing = false
                            progress = null
                        }
                    }
                ) { Text(if (importing) "Importing…" else "Import now") }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SectionCard(
                title = "4 · Notifications (optional)",
                subtitle = "Only used for the daily spending digest, if you turn it on later."
            ) {
                OutlinedButton(onClick = {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow") }
            }
        }

        SectionCard(
            title = "One thing about Samsung",
            subtitle = "One UI aggressively sleeps background apps. If capture stops after a " +
                "few days, set Masarif to Unrestricted under Battery in app settings — " +
                "there is a shortcut for it in Settings."
        ) {}

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                prefs.onboardingDone = true
                state.refresh()
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start using Masarif") }

        TextButton(
            onClick = {
                prefs.onboardingDone = true
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Skip for now") }

        Spacer(Modifier.height(24.dp))
    }
}
