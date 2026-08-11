package com.example.meshsocial.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "users", primaryKeys = ["userId"])
data class UserEntity(
    val userId: String,
    val displayName: String,
    val onboardedOnEpochMs: Long,
)

@Entity(
    tableName = "posts",
    primaryKeys = ["postId"],
    indices = [
        Index("authorId"),
        Index("createdAtEpochMs"),
        Index("expiresAtEpochMs"),
    ],
)
data class PostEntity(
    val postId: String,
    val authorId: String,
    val content: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Entity(tableName = "peer_state", primaryKeys = ["peerId"])
data class PeerStateEntity(
    val peerId: String,
    val lastSeenAtEpochMs: Long?,
    val lastAttemptAtEpochMs: Long?,
    val lastSuccessfulSyncAtEpochMs: Long?,
    val lastSyncStatus: String?,
)

@Entity(
    tableName = "pending_sync",
    primaryKeys = ["peerId", "postId", "direction"],
    indices = [Index("peerId"), Index("postId")],
)
data class PendingSyncItemEntity(
    val peerId: String,
    val postId: String,
    val direction: String,
    val state: String,
    val updatedAtEpochMs: Long,
)
