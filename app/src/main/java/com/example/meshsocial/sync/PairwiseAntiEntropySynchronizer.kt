package com.example.meshsocial.sync

import com.example.meshsocial.data.repository.PendingSyncRepository
import com.example.meshsocial.data.repository.PeerStateRepository
import com.example.meshsocial.data.repository.PostRepository
import com.example.meshsocial.domain.model.PendingState
import com.example.meshsocial.domain.model.PendingSyncItem
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.SyncDirection
import com.example.meshsocial.domain.model.SyncStatus
import java.time.Instant
import java.util.UUID

/**
 * Working Phase-1 synchronizer that runs pairwise directly against two repositories.
 *
 * It deliberately does NOT use Bluetooth. Its purpose is to prove:
 *  - set-difference anti-entropy,
 *  - durable pending work,
 *  - retry/idempotency,
 *  - eventual pairwise convergence.
 *
 * Phase 2 replaces the direct remote repository calls with SyncMessage exchanges over PeerConnection.
 */
class PairwiseAntiEntropySynchronizer {

    data class Node(
        val peerId: UUID,
        val posts: PostRepository,
        val peers: PeerStateRepository,
        val pending: PendingSyncRepository,
    )

    data class Report(
        val sentAToB: Int,
        val sentBToA: Int,
        val pendingOnAForB: Int,
        val pendingOnBForA: Int,
        val fullyReconciled: Boolean,
    )

    suspend fun sync(
        a: Node,
        b: Node,
        now: Instant = Instant.now(),
        maxTransfersPerDirection: Int = Int.MAX_VALUE,
    ): Report {
        markAttempt(a, b.peerId, now)
        markAttempt(b, a.peerId, now)

        // 1) Resume persisted unfinished RECEIVE work first.
        var sentBToA = transferPending(receiver = a, sender = b, now, maxTransfersPerDirection)
        var sentAToB = transferPending(receiver = b, sender = a, now, maxTransfersPerDirection)

        // 2) Fresh inventories are authoritative. Historical "posts_synced" is not.
        val aIds = a.posts.activePostIds(now)
        val bIds = b.posts.activePostIds(now)

        val missingOnA = bIds - aIds
        val missingOnB = aIds - bIds

        addPending(receiver = a, senderPeerId = b.peerId, missingOnA, now)
        addPending(receiver = b, senderPeerId = a.peerId, missingOnB, now)

        // 3) Transfer newly discovered work. Budget simulates a connection dropping mid-sync.
        val remainingBudgetBA = (maxTransfersPerDirection - sentBToA).coerceAtLeast(0)
        val remainingBudgetAB = (maxTransfersPerDirection - sentAToB).coerceAtLeast(0)
        sentBToA += transferPending(receiver = a, sender = b, now, remainingBudgetBA)
        sentAToB += transferPending(receiver = b, sender = a, now, remainingBudgetAB)

        val pendingA = a.pending.getForPeer(b.peerId).count { it.direction == SyncDirection.RECEIVE }
        val pendingB = b.pending.getForPeer(a.peerId).count { it.direction == SyncDirection.RECEIVE }
        val complete = pendingA == 0 && pendingB == 0

        if (complete) {
            markSuccess(a, b.peerId, now)
            markSuccess(b, a.peerId, now)
        } else {
            markInterrupted(a, b.peerId, now)
            markInterrupted(b, a.peerId, now)
        }

        return Report(sentAToB, sentBToA, pendingA, pendingB, complete)
    }

    private suspend fun addPending(
        receiver: Node,
        senderPeerId: UUID,
        ids: Set<UUID>,
        now: Instant,
    ) {
        receiver.pending.save(ids.map { postId ->
            PendingSyncItem(
                peerId = senderPeerId,
                postId = postId,
                direction = SyncDirection.RECEIVE,
                state = PendingState.PENDING,
                updatedAt = now,
            )
        })
    }

    private suspend fun transferPending(
        receiver: Node,
        sender: Node,
        now: Instant,
        budget: Int,
    ): Int {
        if (budget <= 0) return 0
        val pending = receiver.pending.getForPeer(sender.peerId)
            .filter { it.direction == SyncDirection.RECEIVE }
            .take(budget)
        if (pending.isEmpty()) return 0

        val requestedIds = pending.map { it.postId }.toSet()
        val posts = sender.posts.activePosts(requestedIds, now)
        val byId = posts.associateBy { it.postId }

        var transferred = 0
        for (item in pending) {
            val post = byId[item.postId]
            if (post != null) {
                // Durable insert first. Duplicate insert is safe because post_id is unique.
                receiver.posts.insert(post)
                transferred += 1
            }
            // If sender no longer has it (for example TTL expired), clear stale pending too.
            receiver.pending.remove(item.peerId, item.postId, SyncDirection.RECEIVE)
        }
        return transferred
    }

    private suspend fun markAttempt(node: Node, peerId: UUID, now: Instant) {
        val old = node.peers.get(peerId) ?: PeerState(peerId)
        node.peers.save(old.copy(lastSeenAt = now, lastAttemptAt = now))
    }

    private suspend fun markSuccess(node: Node, peerId: UUID, now: Instant) {
        val old = node.peers.get(peerId) ?: PeerState(peerId)
        node.peers.save(old.copy(
            lastSeenAt = now,
            lastAttemptAt = now,
            lastSuccessfulSyncAt = now,
            lastSyncStatus = SyncStatus.SUCCESS,
        ))
    }

    private suspend fun markInterrupted(node: Node, peerId: UUID, now: Instant) {
        val old = node.peers.get(peerId) ?: PeerState(peerId)
        node.peers.save(old.copy(
            lastSeenAt = now,
            lastAttemptAt = now,
            lastSyncStatus = SyncStatus.INTERRUPTED,
        ))
    }
}
