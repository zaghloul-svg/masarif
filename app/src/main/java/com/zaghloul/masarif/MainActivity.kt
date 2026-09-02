package com.zaghloul.masarif

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zaghloul.masarif.data.Txn
import com.zaghloul.masarif.ui.AppState
import com.zaghloul.masarif.ui.screens.BudgetsScreen
import com.zaghloul.masarif.ui.screens.DashboardScreen
import com.zaghloul.masarif.ui.screens.InsightsScreen
import com.zaghloul.masarif.ui.screens.OnboardingScreen
import com.zaghloul.masarif.ui.screens.RuleLabScreen
import com.zaghloul.masarif.ui.screens.SettingsScreen
import com.zaghloul.masarif.ui.screens.TransactionsScreen
import com.zaghloul.masarif.ui.screens.TxnDetailScreen
import com.zaghloul.masarif.ui.theme.MasarifTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Overview", Icons.Filled.PieChart),
    TRANSACTIONS("Activity", Icons.Filled.ReceiptLong),
    INSIGHTS("Insights", Icons.Filled.Lightbulb),
    BUDGETS("Budgets", Icons.Filled.AccountBalanceWallet)
}

private sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay
    data object RuleLab : Overlay
    data class Detail(val txn: Txn) : Overlay
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MasarifTheme {
                Surface(Modifier.fillMaxSize()) {
                    MasarifRoot()
                }
            }
        }
    }
}

// Named MasarifRoot, not MasarifApp: the Application subclass in MasarifApp.kt
// already owns that name in this package, and a constructor plus a function
// sharing a name is an overload-resolution ambiguity.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasarifRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { AppState.get(context) }
    val data by state.data.collectAsState()

    var showOnboarding by remember { mutableStateOf(!state.repo.prefs.onboardingDone) }
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }

    if (showOnboarding) {
        OnboardingScreen(state) { showOnboarding = false }
        return
    }

    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }
    BackHandler(enabled = overlay == Overlay.None && tab != Tab.DASHBOARD) {
        tab = Tab.DASHBOARD
    }

    val title = when (val o = overlay) {
        is Overlay.Settings -> "Settings"
        is Overlay.RuleLab -> "Rule Lab"
        is Overlay.Detail -> o.txn.merchant
        Overlay.None -> when (tab) {
            Tab.DASHBOARD -> "Masarif"
            else -> tab.label
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (overlay != Overlay.None) {
                        IconButton(onClick = { overlay = Overlay.None }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (overlay == Overlay.None) {
                        IconButton(onClick = { overlay = Overlay.RuleLab }) {
                            BadgedBox(badge = {
                                if (data.unmatchedCount > 0) {
                                    Badge { Text(data.unmatchedCount.coerceAtMost(99).toString()) }
                                }
                            }) {
                                Icon(Icons.Filled.Build, "Rule Lab")
                            }
                        }
                        IconButton(onClick = { overlay = Overlay.Settings }) {
                            Icon(Icons.Filled.Settings, "Settings")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (overlay == Overlay.None) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (val o = overlay) {
                is Overlay.Settings ->
                    SettingsScreen(state, data, padding)

                is Overlay.RuleLab ->
                    RuleLabScreen(state, padding)

                is Overlay.Detail ->
                    TxnDetailScreen(state, o.txn, { overlay = Overlay.None }, padding)

                Overlay.None -> when (tab) {
                    Tab.DASHBOARD -> DashboardScreen(
                        state = state,
                        data = data,
                        onSeeAllTransactions = { tab = Tab.TRANSACTIONS },
                        onSeeInsights = { tab = Tab.INSIGHTS },
                        onOpenTxn = { overlay = Overlay.Detail(it) },
                        contentPadding = padding
                    )

                    Tab.TRANSACTIONS -> TransactionsScreen(
                        state = state,
                        data = data,
                        onOpenTxn = { overlay = Overlay.Detail(it) },
                        contentPadding = padding
                    )

                    Tab.INSIGHTS -> InsightsScreen(
                        state = state,
                        data = data,
                        onOpenSettings = { overlay = Overlay.Settings },
                        contentPadding = padding
                    )

                    Tab.BUDGETS -> BudgetsScreen(
                        state = state,
                        data = data,
                        contentPadding = padding
                    )
                }
            }
        }
    }
}
