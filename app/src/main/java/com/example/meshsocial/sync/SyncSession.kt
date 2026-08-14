package com.example.meshsocial.sync


import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.data.repository.PendingSyncRepository
import com.example.meshsocial.data.repository.PeerStateRepository
import com.example.meshsocial.data.repository.PostRepository
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.PendingState
import com.example.meshsocial.domain.model.PendingSyncItem
import com.example.meshsocial.domain.model.SyncDirection
import com.example.meshsocial.domain.model.SyncStatus
import com.example.meshsocial.protocol.SyncMessage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import timber.log.Timber

/**
 * Runs one anti-entropy exchange over a [PeerConnection].
 *
 * start() sends HELLO + our Inventory, then drives the response side by
 * collecting the connection's incoming messages. It is idempotent: posts are
 * inserted by unique post_id (IGNORE on conflict) and RECEIVE-side pending work
 * is persisted before the request so interrupted transfers resume.
 *
 * Completion: a side sends SyncComplete once it has no outstanding RECEIVE work;
 * when both sides have exchanged SyncComplete the session is marked successful
 * in PeerState.
 */
class SyncSession(
    private val localPeerId: UUID,
    private val connection: PeerConnection,
    private val posts: PostRepository,
    private val pending: PendingSyncRepository,
    private val peerStates: PeerStateRepository,
    private val onEvent: (String) -> Unit = {},
) {
    val sessionId: UUID = UUID.randomUUID()

    private var sentComplete = false
    private var receivedComplete = false

    suspend fun start(
        now: Instant = Instant.now(),
        resyncInterval: Duration = Duration.ofSeconds(30),
    ) {
        markAttempt(now)
        connection.send(SyncMessage.Hello(protocolVersion = 1, peerId = localPeerId))
        connection.send(SyncMessage.Inventory(sessionId, posts.activePostIds(now)))
        Timber.i("started session $sessionId with ${connection.remotePeerId?.toString()?.take(8)}")
        onEvent("sync session started with peer ${connection.remotePeerId?.toString()?.take(8)}")

        // Persistent connection: keep re-exchanging inventory on a timer so posts
        // created AFTER the initial sync still propagate, until the link drops.
        coroutineScope {
            launch {
                while (isActive) {
                    delay(resyncInterval.toMillis())
                    val ids = posts.activePostIds(Instant.now())
                    Timber.i("re-inventory: ${ids.size} post(s)")
                    onEvent("re-inventory: ${ids.size} post(s)")
                    connection.send(SyncMessage.Inventory(sessionId, ids))
                }
            }
            connection.incomingMessages.collect { message ->
                handle(message, now)
            }
        }    }

    suspend fun handle(message: SyncMessage, now: Instant = Instant.now()) {
        when (message) {
            is SyncMessage.Hello -> Unit // transport already binds remotePeerId
            is SyncMessage.Inventory -> onInventory(message, now)
            is SyncMessage.RequestPosts -> {
                val requested = posts.activePosts(message.postIds, now)
                if (requested.isNotEmpty()) {
                    connection.send(
                        SyncMessage.PostBatch(
                            sessionId = message.sessionId,
                            batchId = UUID.randomUUID(),
                            posts = requested,
                        )
                    )
                    onEvent("sent ${requested.size} post(s): ${requested.map { it.content.take(30) }}")
                }
                Timber.i("sent PostBatch (${requested.size} posts) to ${connection.remotePeerId?.toString()?.take(8)}")
            }
            is SyncMessage.PostBatch -> onPostBatch(message, now)
            is SyncMessage.Ack -> Unit
            is SyncMessage.SyncComplete -> {
                receivedComplete = true
                maybeComplete(now)
            }
        }
    }

    private suspend fun onInventory(message: SyncMessage.Inventory, now: Instant) {
        val remoteId = connection.remotePeerId ?: run {
            Timber.w("inventory before peer identity known; ignoring")
            return
        }
        val local = posts.activePostIds(now)
        val missing = message.postIds - local
        if (missing.isEmpty()) {
            Timber.i("nothing missing from ${remoteId.toString().take(8)}; sending SyncComplete")
            onEvent("nothing missing from peer ${remoteId.toString().take(8)}")
            sendComplete(now)
            return
        }
        // Persist RECEIVE-side pending work BEFORE requesting so interrupted
        // transfers resume on the next connection.
        pending.save(missing.map { postId ->
            PendingSyncItem(
                peerId = remoteId,
                postId = postId,
                direction = SyncDirection.RECEIVE,
                state = PendingState.PENDING,
                updatedAt = now,
            )
        })
        connection.send(SyncMessage.RequestPosts(message.sessionId, missing))
        onEvent("requesting ${missing.size} missing post(s) from peer ${remoteId.toString().take(8)}: ${missing.map { it.toString().take(6) }}")
        Timber.i("requesting ${missing.size} missing post(s) from ${remoteId.toString().take(8)}")
    }

    private suspend fun onPostBatch(message: SyncMessage.PostBatch, now: Instant) {
        val remoteId = connection.remotePeerId
        posts.insertAll(message.posts)
        if (remoteId != null) {
            message.posts.forEach { pending.remove(remoteId, it.postId, SyncDirection.RECEIVE) }
        }
        connection.send(SyncMessage.Ack(message.sessionId, message.batchId))
        onEvent("inserted ${message.posts.size} post(s): ${message.posts.map { it.content.take(30) }}")
        Timber.i("inserted ${message.posts.size} post(s), acked ${message.batchId.toString().take(8)}")

        val remaining = remoteId?.let { pending.countForPeer(it) } ?: 0
        if (remaining == 0) {
            sendComplete(now)
        }
    }

    private suspend fun sendComplete(now: Instant) {
        if (sentComplete) return
        sentComplete = true
        connection.send(SyncMessage.SyncComplete(sessionId))
        maybeComplete(now)
    }

    private suspend fun maybeComplete(now: Instant) {
        if (!sentComplete || !receivedComplete) return
        val remoteId = connection.remotePeerId ?: return
        Timber.i("session complete with ${remoteId.toString().take(8)}")
        onEvent("sync complete with peer ${remoteId.toString().take(8)}")
        val old = peerStates.get(remoteId) ?: PeerState(remoteId)
        peerStates.save(old.copy(
            lastSeenAt = now,
            lastAttemptAt = now,
            lastSuccessfulSyncAt = now,
            lastSyncStatus = SyncStatus.SUCCESS,
        ))
    }

    private suspend fun markAttempt(now: Instant) {
        val remoteId = connection.remotePeerId ?: return
        val old = peerStates.get(remoteId) ?: PeerState(remoteId)
        peerStates.save(old.copy(lastSeenAt = now, lastAttemptAt = now))
    }

}
