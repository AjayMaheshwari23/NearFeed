package com.example.meshsocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.User
import com.example.meshsocial.ui.components.Avatar
import com.example.meshsocial.ui.components.FeedDivider
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(user: User, posts: List<Post>, onPost: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Composer(user, onPost) }
        item { FeedDivider() }
        if (posts.isEmpty()) {
            item {
                Text(
                    "Nothing here yet.\nPost something — it'll be shared with nearby devices.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            items(posts, key = { it.postId }) { post ->
                PostRow(post, user)
                FeedDivider()
            }
        }
    }
}

@Composable
private fun Composer(user: User, onPost: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(user.displayName, size = 44)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What's happening nearby?") },
                shape = CircleShape,
                maxLines = 4,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onPost(text.trim())
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                Text("Post", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PostRow(post: Post, localUser: User) {
    val isLocal = post.authorId == localUser.userId
    val displayName = if (isLocal) localUser.displayName else "peer ${post.authorId.toString().take(8)}"
    val initial = if (isLocal) localUser.displayName else post.authorId.toString().take(1)

    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(initial, size = 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatRelative(post.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                post.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (!isLocal) {
                    Text(
                        "↯ received nearby",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (isLocal) "synced to nearby"
                    else "BLE · expires ${formatDuration(post.expiresAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRelative(instant: Instant): String {
    val minutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }
}

private fun formatDuration(instant: Instant): String {
    val hours = Duration.between(Instant.now(), instant).toHours().coerceAtLeast(0)
    return if (hours < 1) "${Duration.between(Instant.now(), instant).toMinutes().coerceAtLeast(0)}m" else "${hours}h"
}
