package com.example.meshsocial.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.meshsocial.ui.theme.warnAmber

/** Thin horizontal separator. */
@Composable
fun FeedDivider(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = modifier.fillMaxWidth().height(1.dp)) {}
}

/** Small colored dot for status indicators. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/** Circular avatar showing first character of a name. */
@Composable
fun Avatar(initial: String, size: Int = 40, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial.take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Section title inside debug/settings screens. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/**
 * Warning banner shown when BLE isn't ready (Bluetooth off / permissions missing).
 * Kept top-level so both Home and Debug can surface it.
 */
@Composable
fun ReadyBanner(
    bluetoothOff: Boolean,
    missingPermissionCount: Int,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = warnAmber.copy(alpha = 0.15f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (bluetoothOff) "Bluetooth is off" else "Missing permissions",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (bluetoothOff) "Sync can't work until Bluetooth is on."
                    else "$missingPermissionCount permission(s) needed for nearby sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (bluetoothOff) "Turn on" else "Grant",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = warnAmber,
                modifier = Modifier
                    .background(warnAmber.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
