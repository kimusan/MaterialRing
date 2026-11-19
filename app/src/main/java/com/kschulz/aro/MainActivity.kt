package com.kschulz.aro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.aro.ui.device.BlePermissions
import com.kschulz.aro.ui.device.DeviceViewModel
import com.kschulz.aro.ui.device.ScanScreen
import com.kschulz.aro.ui.history.HistoryScreen
import com.kschulz.aro.ui.home.HomeScreen
import com.kschulz.aro.ui.settings.SettingsScreen
import com.kschulz.aro.ui.theme.AroTheme
import dagger.hilt.android.AndroidEntryPoint

/** Entry point so Hilt generates the component holder that hiltViewModel() resolves
 *  against. Missing this throws at runtime, not compile time. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AroTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(device: DeviceViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBlePermissions by remember {
        mutableStateOf(BlePermissions.hasAllPermissions(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasBlePermissions = results.values.all { it } || BlePermissions.hasAllPermissions(context)
        if (hasBlePermissions) {
            device.onPermissionsGranted()
        }
    }

    // Request BLE permissions automatically on startup if not yet granted.
    LaunchedEffect(Unit) {
        if (!BlePermissions.hasAllPermissions(context)) {
            permissionLauncher.launch(BlePermissions.requiredPermissions())
        } else {
            hasBlePermissions = true
            device.onPermissionsGranted()
        }
    }

    // Re-check permissions when returning from system settings or permission dialog.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = BlePermissions.hasAllPermissions(context)
                if (granted && !hasBlePermissions) {
                    hasBlePermissions = true
                    device.onPermissionsGranted()
                } else {
                    hasBlePermissions = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestPermissions = {
        permissionLauncher.launch(BlePermissions.requiredPermissions())
    }

    val bound by device.boundDevices.collectAsStateWithLifecycle()
    var addingRing by remember { mutableStateOf(false) }

    if (bound.isEmpty() || addingRing) {
        // Either no device bound yet, or the user tapped "Add ring" in Settings.
        ScanScreen(
            viewModel = device,
            hasPermissions = hasBlePermissions,
            onRequestPermissions = requestPermissions,
            canCancel = bound.isNotEmpty(),
            onCancel = { addingRing = false },
            alreadyBoundMacs = bound.map { it.macAddress }.toSet(),
            onBound = { addingRing = false },
        )
    } else {
        MainScaffold(
            hasPermissions = hasBlePermissions,
            onRequestPermissions = requestPermissions,
            onAddRing = { addingRing = true },
        )
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onAddRing: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    var focusMetric by remember { mutableStateOf<com.kschulz.aro.ui.history.HistoryMetric?>(null) }
    val tabs = listOf(
        Tab("Home", Icons.Filled.Home),
        Tab("History", Icons.Filled.History),
        Tab("Settings", Icons.Filled.Settings),
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (!hasPermissions) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Bluetooth permission required to talk to your ring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRequestPermissions) {
                            Text("Grant")
                        }
                    }
                }
            }
            val content = Modifier.weight(1f)
            when (selected) {
                0 -> HomeScreen(
                    modifier = content,
                    onOpenHistory = { metric ->
                        focusMetric = metric
                        selected = 1
                    },
                )
                1 -> HistoryScreen(
                    modifier = content,
                    focusMetric = focusMetric,
                    onFocusMetricConsumed = { focusMetric = null },
                )
                2 -> SettingsScreen(modifier = content, onAddRing = onAddRing)
            }
        }
    }
}
