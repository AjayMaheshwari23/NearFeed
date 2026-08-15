package com.example.meshsocial.data

import com.example.meshsocial.data.local.entity.PendingSyncItemEntity
import com.example.meshsocial.data.local.entity.PeerStateEntity
import com.example.meshsocial.data.local.entity.PostEntity
import com.example.meshsocial.data.local.entity.UserEntity
import com.example.meshsocial.domain.model.PendingState
import com.example.meshsocial.domain.model.PendingSyncItem
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.SyncDirection
import com.example.meshsocial.domain.model.SyncStatus
import com.example.meshsocial.domain.model.User
import java.time.Instant
import java.util.UUID

fun UserEntity.toDomain() = User(
    userId = UUID.fromString(userId),
    displayName = displayName,
    onboardedOn = Instant.ofEpochMilli(onboardedOnEpochMs),
)

fun User.toEntity() = UserEntity(
    userId = userId.toString(),
    displayName = displayName,
    onboardedOnEpochMs = onboardedOn.toEpochMilli(),
)

fun PostEntity.toDomain() = Post(
    postId = UUID.fromString(postId),
    authorId = UUID.fromString(authorId),
    authorDisplayName = authorDisplayName,
    content = content,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    expiresAt = Instant.ofEpochMilli(expiresAtEpochMs),
)

fun Post.toEntity() = PostEntity(
    postId = postId.toString(),
    authorId = authorId.toString(),
    authorDisplayName = authorDisplayName,
    content = content,
    createdAtEpochMs = createdAt.toEpochMilli(),
    expiresAtEpochMs = expiresAt.toEpochMilli(),
)

fun PeerStateEntity.toDomain() = PeerState(
    peerId = UUID.fromString(peerId),
    lastSeenAt = lastSeenAtEpochMs?.let(Instant::ofEpochMilli),
    lastAttemptAt = lastAttemptAtEpochMs?.let(Instant::ofEpochMilli),
    lastSuccessfulSyncAt = lastSuccessfulSyncAtEpochMs?.let(Instant::ofEpochMilli),
    lastSyncStatus = lastSyncStatus?.let(SyncStatus::valueOf),
)

fun PeerState.toEntity() = PeerStateEntity(
    peerId = peerId.toString(),
    lastSeenAtEpochMs = lastSeenAt?.toEpochMilli(),
    lastAttemptAtEpochMs = lastAttemptAt?.toEpochMilli(),
    lastSuccessfulSyncAtEpochMs = lastSuccessfulSyncAt?.toEpochMilli(),
    lastSyncStatus = lastSyncStatus?.name,
)

fun PendingSyncItemEntity.toDomain() = PendingSyncItem(
    peerId = UUID.fromString(peerId),
    postId = UUID.fromString(postId),
    direction = SyncDirection.valueOf(direction),
    state = PendingState.valueOf(state),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
)

fun PendingSyncItem.toEntity() = PendingSyncItemEntity(
    peerId = peerId.toString(),
    postId = postId.toString(),
    direction = direction.name,
    state = state.name,
    updatedAtEpochMs = updatedAt.toEpochMilli(),
)
