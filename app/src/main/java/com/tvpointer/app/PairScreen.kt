package com.tvpointer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    Page {
        Text("TV Pointer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your phone becomes a Bluetooth mouse and keyboard for your TV. " +
                "Allow Bluetooth access so the TV can find and connect to your phone.",
        )
        Button(onClick = onAllow) { Text("Allow Bluetooth") }
    }
}

@Composable
fun PairScreen(
    vm: RemoteViewModel,
    state: HidState,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
) {
    val paired by vm.remote.paired.collectAsStateWithLifecycle()
    val found by vm.remote.found.collectAsStateWithLifecycle()
    val searching by vm.remote.searching.collectAsStateWithLifecycle()

    Page {
        Text("TV Pointer", style = MaterialTheme.typography.headlineMedium)

        when (state) {
            HidState.Unsupported -> {
                Text(
                    "Sorry, this phone doesn't support acting as a Bluetooth mouse and keyboard. " +
                        "Some phone makers turn this feature off.",
                    color = MaterialTheme.colorScheme.error,
                )
                return@Page
            }
            HidState.BluetoothOff -> {
                Text("Bluetooth is off.")
                Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
                return@Page
            }
            HidState.Starting -> Busy("Starting mouse mode…")
            is HidState.Ready -> {
                state.connecting?.let { Busy("Connecting to $it…") }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.remote.retry() }) { Text("Try again") }
                }
                if (state.connecting == null && state.error == null) {
                    Text("Mouse mode is on. Your phone is ready to pair.",
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            is HidState.Connected -> Unit
        }

        Text("Option A: pair from the TV", style = MaterialTheme.typography.titleMedium)
        Steps(
            "Tap \"Make phone visible\" below and allow it (it stays visible for 5 minutes).",
            "On the TV: Settings → Remotes & Accessories → Pair remote or accessory.",
            "Pick your phone's name on the TV and accept the pairing code on both screens.",
            "The trackpad opens here automatically once the TV connects.",
        )
        Button(onClick = onMakeDiscoverable) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Make phone visible")
        }

        Spacer(Modifier.size(8.dp))
        Text("Option B: find the TV from your phone", style = MaterialTheme.typography.titleMedium)
        Steps(
            "On the TV, open Settings → Remotes & Accessories (keep that screen open).",
            "Tap \"Search for TV\" and pick your TV, then accept the code on both screens.",
        )
        Button(enabled = !searching, onClick = { vm.remote.startSearch() }) {
            Text(if (searching) "Searching…" else "Search for TV")
        }
        if (searching) Busy("Looking for nearby Bluetooth devices…")
        found.forEach { device ->
            Card(Modifier.fillMaxWidth().clickable { vm.remote.pairAndConnect(device.address) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        Text("Already paired? Tap your TV", style = MaterialTheme.typography.titleMedium)
        if (paired.isEmpty()) {
            Text("No paired devices yet.", style = MaterialTheme.typography.bodySmall)
        }
        paired.forEach { device ->
            Card(Modifier.fillMaxWidth().clickable { vm.remote.connect(device.address) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tv, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        OutlinedButton(onClick = { vm.remote.refreshPaired() }) { Text("Refresh list") }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}

@Composable
private fun Steps(vararg steps: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
