package com.example.meshsocial.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshsocial.ble.BlePermissions
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.ui.components.ReadyBanner
import com.example.meshsocial.ui.components.SectionHeader
import com.example.meshsocial.ui.components.StatusDot
import com.example.meshsocial.ui.theme.syncGreen
import com.example.meshsocial.ui.theme.warnAmber
import timber.log.Timber
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * Developer/debug screen. Hosts all nearby-peer discovery, connection and sync
 * activity formerly under the "Nearby" tab, plus the in-memory sync demo.
 */
@Composable
fun DebugScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val connectionLog by viewModel.connectionLog.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val backgroundRunning by viewModel.backgroundRunning.collectAsStateWithLifecycle()
    val syncEvents by viewModel.syncEvents.collectAsStateWithLifecycle()
    val log by viewModel.debugLog.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) viewModel.startDiscovery()
        else viewModel.setMessage("Nearby device permission denied")
    }

    // Periodic readiness re-check so the banner clears once BT turns on.
    var bleReady by remember { mutableStateOf(BlePermissions.isReady(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            bleReady = BlePermissions.isReady(context)
            delay(2_000)
        }
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val openSettings = {
        try {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            try {
                enableBtLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e2: Exception) {
                Timber.e("could not open bluetooth settings: ${e2.message}")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 24.dp,
        ),
    ) {
        // ── Readiness ─────────────────────────────────────────
        if (!bleReady) {
            item {
                ReadyBanner(
                    bluetoothOff = !BlePermissions.isBluetoothEnabled(context),
                    missingPermissionCount = BlePermissions.missing(context).size,
                    onAction = openSettings,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        // ── Discovery controls ────────────────────────────────
        item {
            SectionHeader("Discovery")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (scanning) viewModel.stopDiscovery()
                        else {
                            val missing = BlePermissions.missing(context)
                            if (missing.isEmpty()) viewModel.startDiscovery()
                            else permissionLauncher.launch(missing.toTypedArray())
                        }
                    },
                    shape = CircleShape,
                ) { Text(if (scanning) "Stop scan" else "Scan") }
                Button(
                    onClick = { viewModel.reconcileConnections() },
                    enabled = peers.isNotEmpty(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text("Reconcile") }
                Button(
                    onClick = viewModel::runSyncDemo,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text("Sync demo") }
            }
            if (scanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // ── Background loop status ────────────────────────────
        item {
            SectionHeader("Background loop")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (backgroundRunning) syncGreen else warnAmber)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (backgroundRunning) "Running — auto scan → top-K connect → sync" else "Stopped",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // ── Connected peers ───────────────────────────────────
        if (connectedPeers.isNotEmpty()) {
            item { SectionHeader("Connected peers") }
            items(connectedPeers, key = { it }) { peerId ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(syncGreen)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        peerId.toString().take(8),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = syncGreen,
                    )
                }
            }
        }

        // ── Discovered candidates ─────────────────────────────
        item { SectionHeader("Devices seen (${peers.size})") }
        if (peers.isEmpty()) {
            item {
                Text(
                    if (bleReady) "No devices yet. Keep the two phones near each other."
                    else "Enable Bluetooth + permissions to scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(peers, key = { it.candidateId }) { candidate ->
                PeerRow(candidate, connectedPeers)
            }
        }

        // ── Sync events ───────────────────────────────────────
        if (syncEvents.isNotEmpty()) {
            item { SectionHeader("Sync events") }
            items(syncEvents) { event ->
                Text(
                    event,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        // ── Connection log ────────────────────────────────────
        if (connectionLog.isNotEmpty()) {
            item { SectionHeader("Connection log") }
            items(connectionLog) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        // ── In-memory sync demo log ───────────────────────────
        if (log.isNotEmpty()) {
            item { SectionHeader("Sync demo log") }
            items(log) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PeerRow(candidate: PeerCandidate, connectedPeers: List<UUID>) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val isConnected = candidate.knownPeerId != null && candidate.knownPeerId in connectedPeers
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (isConnected) syncGreen else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                candidate.candidateId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                candidate.knownPeerId?.let {
                    Text("peer ${it.toString().take(8)}", style = MaterialTheme.typography.labelSmall)
                }
                Text("RSSI ${candidate.rssi}", style = MaterialTheme.typography.labelSmall)
                Text(
                    candidate.discoveredAt.atZone(ZoneId.systemDefault()).format(formatter),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (isConnected) {
            Text(
                "linked",
                style = MaterialTheme.typography.labelSmall,
                color = syncGreen,
            )
        }
    }
}
