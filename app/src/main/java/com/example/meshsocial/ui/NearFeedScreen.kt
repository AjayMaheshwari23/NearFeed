package com.example.meshsocial.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshsocial.ble.BlePermissions
import com.example.meshsocial.ui.components.Avatar
import com.example.meshsocial.ui.components.FeedDivider

private enum class Destination(val label: String) { HOME("Home"), DEBUG("Debug") }

@Composable
fun NearFeedScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val user by viewModel.user.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }

    // Auto-request BLE permissions once a profile exists.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(user != null) {
        if (user != null) {
            val missing = BlePermissions.missing(context)
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (user != null) {
                AppBottomNav(
                    destination = destination,
                    onSelect = { destination = it },
                )
            }
        },
    ) { padding ->
        if (user == null) {
            Onboarding(
                onCreate = viewModel::createProfile,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                AppHeader(user!!.displayName)
                FeedDivider()
                when (destination) {
                    Destination.HOME -> HomeScreen(user!!, feed, viewModel::createPost, viewModel::reconcileConnections)
                    Destination.DEBUG -> DebugScreen(viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(displayName: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "NEAR-FEED",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Avatar(displayName, size = 28)
    }
}

@Composable
private fun AppBottomNav(
    destination: Destination,
    onSelect: (Destination) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = destination == Destination.HOME,
            onClick = { onSelect(Destination.HOME) },
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = destination == Destination.DEBUG,
            onClick = { onSelect(Destination.DEBUG) },
            icon = { Icon(Icons.Outlined.Build, contentDescription = null) },
            label = { Text("Debug") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun Onboarding(modifier: Modifier = Modifier, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "NEAR-FEED",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Post to the mesh. Discovered nearby, synced peer-to-peer.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name") },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(16.dp))
        Button(
            onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
            enabled = name.isNotBlank(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create profile", fontWeight = FontWeight.Bold)
        }
    }
}
