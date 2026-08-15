package com.example.meshsocial.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshsocial.ble.BlePermissions
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.ui.MainViewModel.CyclePhase
import com.example.meshsocial.ui.components.FeedDivider
import com.example.meshsocial.ui.components.SectionHeader
import com.example.meshsocial.ui.components.StatusDot
import com.example.meshsocial.ui.theme.syncGreen
import com.example.meshsocial.ui.theme.warnAmber
import timber.log.Timber
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
fun DebugScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val user by viewModel.user.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val connectionLog by viewModel.connectionLog.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val backgroundRunning by viewModel.backgroundRunning.collectAsStateWithLifecycle()
    val cyclePhase by viewModel.cyclePhase.collectAsStateWithLifecycle()
    val cycleElapsed by viewModel.cycleElapsed.collectAsStateWithLifecycle()
    val cycleTotal by viewModel.cycleTotal.collectAsStateWithLifecycle()
    val postsReceived by viewModel.postsReceived.collectAsStateWithLifecycle()
    val postsSent by viewModel.postsSent.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val activityLog by viewModel.activityLog.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) viewModel.startDiscovery()
        else viewModel.setMessage("Nearby device permission denied")
    }

    // Periodic readiness re-check so status flips the moment BT turns on.
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

    val missingCount = BlePermissions.missing(context).size
    val btOn = BlePermissions.isBluetoothEnabled(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 24.dp,
        ),
    ) {
        // ── Network status banner ──────────────────────────────
        item {
            if (backgroundRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(syncGreen, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Network active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Background synchronization running",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (!bleReady) {
                WarningStrip(btOff = !btOn, missing = missingCount, onAction = openSettings)
            }
            Spacer(Modifier.size(8.dp))
            FeedDivider()
        }

        // ── Bluetooth ──────────────────────────────────────────
        item { SectionHeader("Bluetooth") }
        item {
            InfoRow("Bluetooth", if (btOn) "On" else "Off", ok = btOn)
            InfoRow("Permissions", if (missingCount == 0) "Granted" else "$missingCount missing", ok = missingCount == 0)
            InfoRow("GATT server", "Running", ok = backgroundRunning)
            InfoRow("Advertising", if (backgroundRunning) "Active" else "Idle", ok = backgroundRunning)
            InfoRow("Scanner", if (scanning) "Scanning" else "Idle", ok = scanning)
        }

        // ── Discovery ──────────────────────────────────────────
        item { SectionHeader("Discovery") }
        item {
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
            }
            Spacer(Modifier.size(8.dp))
            if (peers.isEmpty()) {
                Text(
                    if (bleReady) "No devices seen yet" else "Enable Bluetooth to scan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${peers.size} peers nearby",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (peers.isNotEmpty()) {
            items(peers, key = { it.candidateId }) { candidate ->
                DiscoveryRow(candidate, connectedPeers)
            }
        }

        // ── Sync stats ─────────────────────────────────────────
        item { SectionHeader("Sync") }
        item {
            InfoRow("Cycle", cyclePhaseLabel(cyclePhase))
            InfoRow("Next reconciliation", nextReconcileLabel(cyclePhase, cycleTotal, cycleElapsed))
            InfoRow("Connected peers", connectedPeers.size.toString(), ok = connectedPeers.isNotEmpty())
            InfoRow("Posts received", postsReceived.toString())
            InfoRow("Posts sent", postsSent.toString())
            InfoRow("Pending", pendingCount.toString())
        }

        // ── Background cycle progress ──────────────────────────
        item { SectionHeader("Background cycle") }
        item {
            Text(
                cyclePhaseLabel(cyclePhase),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            if (cycleTotal > 0) {
                LinearProgressIndicator(
                    progress = { cycleElapsed.toFloat() / cycleTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "${cycleElapsed} / ${cycleTotal}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "Scan → Connect → Reconcile → Idle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Recent activity ────────────────────────────────────
        item { SectionHeader("Recent activity") }
        if (activityLog.isEmpty()) {
            item {
                Text(
                    "No activity yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(activityLog.takeLast(20)) { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        line.time,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── Connection log (raw) ───────────────────────────────
        if (connectionLog.isNotEmpty()) {
            item { SectionHeader("Raw log") }
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
    }
}

@Composable
private fun WarningStrip(btOff: Boolean, missing: Int, onAction: () -> Unit) {
    Surface(
        color = warnAmber.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (btOff) "Bluetooth is off" else "Permissions missing ($missing)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Fix",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = warnAmber,
                modifier = Modifier
                    .background(warnAmber.copy(alpha = 0.15f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clickable { onAction() },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, ok: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (ok) MaterialTheme.colorScheme.onSurface else warnAmber,
        )
    }
}

@Composable
private fun DiscoveryRow(candidate: PeerCandidate, connectedPeers: List<UUID>) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val isConnected = candidate.knownPeerId != null && candidate.knownPeerId in connectedPeers
    val seenSeconds = (System.currentTimeMillis() - candidate.discoveredAt.toEpochMilli()) / 1000
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (isConnected) syncGreen else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(10.dp))
        Text(
            candidate.candidateId,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${candidate.rssi} dBm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                isConnected -> "Connected"
                else -> "Seen ${seenSeconds}s ago"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (isConnected) syncGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun cyclePhaseLabel(phase: CyclePhase): String = when (phase) {
    CyclePhase.WAITING -> "Waiting"
    CyclePhase.SCANNING -> "Scanning"
    CyclePhase.RECONCILING -> "Reconciling"
    CyclePhase.IDLE -> "Idle"
}

private fun nextReconcileLabel(phase: CyclePhase, total: Long, elapsed: Long): String = when (phase) {
    CyclePhase.SCANNING -> "after scan window"
    CyclePhase.RECONCILING -> "now"
    CyclePhase.IDLE -> "${(total - elapsed).coerceAtLeast(0)}s"
    CyclePhase.WAITING -> "—"
}
