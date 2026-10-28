package com.kschulz.qring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.qring.ui.device.DeviceViewModel
import com.kschulz.qring.ui.device.ScanScreen
import com.kschulz.qring.ui.history.HistoryScreen
import com.kschulz.qring.ui.home.HomeScreen
import com.kschulz.qring.ui.settings.SettingsScreen
import com.kschulz.qring.ui.theme.QRingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QRingTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(device: DeviceViewModel = hiltViewModel()) {
    val bound by device.boundDevices.collectAsStateWithLifecycle()
    if (bound.isEmpty()) {
        // No device bound yet: the whole app is the scan/bind flow.
        ScanScreen(viewModel = device)
    } else {
        MainScaffold()
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    var selected by remember { mutableIntStateOf(0) }
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
        val content = Modifier.padding(innerPadding)
        when (selected) {
            0 -> HomeScreen(modifier = content)
            1 -> HistoryScreen(modifier = content)
            2 -> SettingsScreen(modifier = content)
        }
    }
}
