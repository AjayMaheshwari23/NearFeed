package com.example.meshsocial.protocol

import com.example.meshsocial.domain.model.Post
import java.util.UUID

/**
 * Application-level protocol messages. Bluetooth does not know what these mean.
 * Phase 2 will add a MessageCodec that serializes these into bytes.
 */
sealed interface SyncMessage {
    data class Hello(val protocolVersion: Int, val peerId: UUID) : SyncMessage
    data class Inventory(val sessionId: UUID, val postIds: Set<UUID>) : SyncMessage
    data class RequestPosts(val sessionId: UUID, val postIds: Set<UUID>) : SyncMessage
    data class PostBatch(val sessionId: UUID, val batchId: UUID, val posts: List<Post>) : SyncMessage
    data class Ack(val sessionId: UUID, val batchId: UUID) : SyncMessage
    data class SyncComplete(val sessionId: UUID) : SyncMessage
}
