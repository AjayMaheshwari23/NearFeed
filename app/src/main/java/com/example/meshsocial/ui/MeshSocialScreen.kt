package com.example.meshsocial.ui

import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshsocial.ble.BlePermissions
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.User
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private enum class Tab { HOME, NEARBY, DEBUG }

@Composable
fun MeshSocialScreen(viewModel: MainViewModel) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val log by viewModel.debugLog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (user == null) {
            Onboarding(
                modifier = Modifier.padding(padding),
                onCreate = viewModel::createProfile,
            )
        } else {
            var tab by remember { mutableStateOf(Tab.HOME) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                Text("Mesh Social", style = MaterialTheme.typography.headlineMedium)
                Text("Local user: ${user!!.displayName} • ${user!!.userId.toString().take(8)}")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { tab = Tab.HOME }) { Text("Home") }
                    Button(onClick = { tab = Tab.NEARBY }) { Text("Nearby") }
                    Button(onClick = { tab = Tab.DEBUG }) { Text("Debug") }
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    Tab.HOME -> HomeFeed(user!!, feed, viewModel::createPost)
                    Tab.NEARBY -> NearbyPeersTab(viewModel)
                    Tab.DEBUG -> DebugScreen(log, viewModel::runSyncDemo)
                }
            }
        }
    }
}

@Composable
private fun Onboarding(modifier: Modifier = Modifier, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Create local profile", style = MaterialTheme.typography.headlineMedium)
        Text("V1 uses a locally generated UUID; no server or cryptographic identity.")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onCreate(name) }) { Text("Create profile") }
    }
}

@Composable
private fun HomeFeed(user: User, posts: List<Post>, onPost: (String) -> Unit) {
    var content by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("What's happening nearby?") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            onPost(content)
            content = ""
        }) { Text("Post") }
        Spacer(Modifier.height(16.dp))
        Text("Active 24-hour feed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(posts, key = { it.postId }) { PostCard(it, user) }
        }
    }
}

@Composable
private fun PostCard(post: Post, localUser: User) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val author = if (post.authorId == localUser.userId) localUser.displayName else post.authorId.toString().take(8)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(author, style = MaterialTheme.typography.labelLarge)
            Text(post.content, style = MaterialTheme.typography.bodyLarge)
            Text(
                post.createdAt.atZone(ZoneId.systemDefault()).format(formatter),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NearbyPeersTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val connectionLog by viewModel.connectionLog.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            viewModel.startDiscovery()
        } else {
            viewModel.setMessage("Nearby device permission denied")
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopDiscovery() }
    }

    val bleAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Header ──────────────────────────────────────────────
        item {
            Text("Nearby peer discovery", style = MaterialTheme.typography.titleLarge)
            Text("BLE scan + advertise for other Mesh Social devices. Scans are time-bounded.")
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (scanning) {
                            viewModel.stopDiscovery()
                        } else {
                            val missing = BlePermissions.missing(context)
                            if (missing.isEmpty()) {
                                viewModel.startDiscovery()
                            } else {
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        }
                    },
                    enabled = bleAvailable,
                ) {
                    Text(if (scanning) "Stop scan" else "Scan for nearby devices")
                }
                Button(
                    onClick = { viewModel.reconcileConnections() },
                    enabled = peers.isNotEmpty(),
                ) {
                    Text("Reconcile")
                }
            }
            if (!bleAvailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "BLE is not available on this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (scanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // ── Connected peers ─────────────────────────────────────
        if (connectedPeers.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${connectedPeers.size} connected peer(s)",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(connectedPeers, key = { it }) { peerId ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("●", color = MaterialTheme.colorScheme.primary)
                        Text(
                            peerId.toString().take(8),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text("connected", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ── Discovered candidates ───────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "${peers.size} device(s) seen",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        items(peers, key = { it.candidateId }) { candidate ->
            PeerCandidateCard(candidate, connectedPeers)
        }

        // ── Connection log ──────────────────────────────────────
        if (connectionLog.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("Connection log", style = MaterialTheme.typography.titleMedium)
            }
            items(connectionLog) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PeerCandidateCard(candidate: PeerCandidate, connectedPeers: List<UUID>) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val isConnected = candidate.knownPeerId != null && candidate.knownPeerId in connectedPeers
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isConnected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(candidate.candidateId, style = MaterialTheme.typography.labelLarge)
                if (isConnected) {
                    Text(
                        "● linked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("RSSI ${candidate.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                candidate.knownPeerId?.let {
                    Text("peer ${it.toString().take(8)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    candidate.discoveredAt.atZone(ZoneId.systemDefault()).format(formatter),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun DebugScreen(log: List<String>, runDemo: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Phase-1 debug", style = MaterialTheme.typography.titleLarge)
        Text("Room + resumable in-memory anti-entropy are wired. BLE discovery adapter exists in code; GATT data channel is the next milestone.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = runDemo) { Text("Run interrupted/resumed sync demo") }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(log) { line ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(line, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}
