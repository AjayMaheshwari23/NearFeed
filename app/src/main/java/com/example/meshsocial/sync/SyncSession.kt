package com.example.meshsocial.sync

import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.data.repository.PendingSyncRepository
import com.example.meshsocial.data.repository.PostRepository
import com.example.meshsocial.protocol.SyncMessage
import java.time.Instant
import java.util.UUID

/**
 * Phase-2 network session skeleton.
 * PairwiseAntiEntropySynchronizer already proves the algorithm without Bluetooth.
 * Implement this after BlePeerConnection exists.
 */
class SyncSession(
    private val localPeerId: UUID,
    private val connection: PeerConnection,
    private val posts: PostRepository,
    private val pending: PendingSyncRepository,
) {
    val sessionId: UUID = UUID.randomUUID()

    suspend fun start(now: Instant = Instant.now()) {
        connection.send(SyncMessage.Hello(protocolVersion = 1, peerId = localPeerId))
        connection.send(SyncMessage.Inventory(sessionId, posts.activePostIds(now)))
    }

    suspend fun handle(message: SyncMessage, now: Instant = Instant.now()) {
        when (message) {
            is SyncMessage.Hello -> Unit // validate protocol + bind transport identity to peer UUID
            is SyncMessage.Inventory -> {
                val local = posts.activePostIds(now)
                val missingLocally = message.postIds - local
                if (missingLocally.isNotEmpty()) {
                    connection.send(SyncMessage.RequestPosts(message.sessionId, missingLocally))
                }
            }
            is SyncMessage.RequestPosts -> {
                val requested = posts.activePosts(message.postIds, now)
                connection.send(
                    SyncMessage.PostBatch(
                        sessionId = message.sessionId,
                        batchId = UUID.randomUUID(),
                        posts = requested,
                    )
                )
            }
            is SyncMessage.PostBatch -> {
                posts.insertAll(message.posts)
                connection.send(SyncMessage.Ack(message.sessionId, message.batchId))
            }
            is SyncMessage.Ack -> Unit
            is SyncMessage.SyncComplete -> Unit
        }
    }
}
