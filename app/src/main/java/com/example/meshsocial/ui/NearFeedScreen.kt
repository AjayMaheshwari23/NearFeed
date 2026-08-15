package com.example.meshsocial.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshsocial.R
import com.example.meshsocial.ble.BlePermissions
import com.example.meshsocial.ui.components.Avatar
import com.example.meshsocial.ui.components.FeedDivider
import com.example.meshsocial.ui.components.NearFeedMark

private enum class Destination(val label: String) { HOME("Home"), DEBUG("Diagnostics") }

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
            OnboardingScreen(
                onCreate = viewModel::createProfile,
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
        NearFeedMark(size = 22.dp)
        Spacer(Modifier.size(8.dp))
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
            icon = {
                Icon(
                    if (destination == Destination.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = "Home",
                    tint = if (destination == Destination.HOME) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            label = {
                Text(
                    "Home",
                    fontWeight = if (destination == Destination.HOME) FontWeight.Bold else FontWeight.Normal,
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.surface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        NavigationBarItem(
            selected = destination == Destination.DEBUG,
            onClick = { onSelect(Destination.DEBUG) },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_diagnostics),
                    contentDescription = "Diagnostics",
                    tint = if (destination == Destination.DEBUG) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            label = {
                Text(
                    "Diagnostics",
                    fontWeight = if (destination == Destination.DEBUG) FontWeight.Bold else FontWeight.Normal,
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.surface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}
