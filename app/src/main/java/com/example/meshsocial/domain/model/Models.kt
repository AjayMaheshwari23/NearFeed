package com.example.meshsocial.domain.model

import java.time.Instant
import java.util.UUID

data class User(
    val userId: UUID,
    val displayName: String,
    val onboardedOn: Instant,
)

data class Post(
    val postId: UUID,
    val authorId: UUID,
    val authorDisplayName: String,
    val content: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class PeerState(
    val peerId: UUID,
    val lastSeenAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val lastSuccessfulSyncAt: Instant? = null,
    val lastSyncStatus: SyncStatus? = null,
)

enum class SyncStatus {
    SUCCESS,
    FAILED,
    INTERRUPTED,
}

enum class SyncDirection {
    RECEIVE,
    SEND,
}

enum class PendingState {
    PENDING,
    IN_FLIGHT,
}

data class PendingSyncItem(
    val peerId: UUID,
    val postId: UUID,
    val direction: SyncDirection,
    val state: PendingState,
    val updatedAt: Instant,
)
