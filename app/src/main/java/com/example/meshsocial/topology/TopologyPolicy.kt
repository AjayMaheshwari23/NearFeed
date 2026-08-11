package com.example.meshsocial.topology

import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.domain.model.PeerState
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

data class TopologyContext(
    val selfPeerId: UUID,
    val activeCandidateIds: Set<String> = emptySet(),
    val connectingCandidateIds: Set<String> = emptySet(),
    val peerStates: Map<UUID, PeerState> = emptyMap(),
    val pendingCounts: Map<UUID, Int> = emptyMap(),
    val failureCooldownUntil: Map<UUID, Instant> = emptyMap(),
    val now: Instant = Instant.now(),
)

interface TopologyPolicy {
    fun selectPeers(
        candidates: List<PeerCandidate>,
        context: TopologyContext,
        availableSlots: Int,
    ): List<PeerCandidate>
}

/**
 * P0: unfinished sync
 * P1: never successfully synced
 * P2: least recently successfully synced
 * Tie: fairness through old last-attempt, then small randomization.
 */
class DefaultTopologyPolicy(
    private val minUsableRssi: Int = -90,
    private val random: Random = Random.Default,
) : TopologyPolicy {
    override fun selectPeers(
        candidates: List<PeerCandidate>,
        context: TopologyContext,
        availableSlots: Int,
    ): List<PeerCandidate> {
        if (availableSlots <= 0) return emptyList()

        return candidates
            .asSequence()
            .filter { it.rssi >= minUsableRssi }
            .filterNot { it.candidateId in context.activeCandidateIds }
            .filterNot { it.candidateId in context.connectingCandidateIds }
            .filterNot { it.knownPeerId == context.selfPeerId }
            .filterNot { candidate ->
                val peerId = candidate.knownPeerId ?: return@filterNot false
                val until = context.failureCooldownUntil[peerId] ?: return@filterNot false
                until.isAfter(context.now)
            }
            // Shuffle first so exact ties do not always choose the same peer.
            .toList()
            .shuffled(random)
            .sortedWith(
                compareByDescending<PeerCandidate> { candidate ->
                    candidate.knownPeerId?.let { context.pendingCounts[it] ?: 0 } ?: 0
                }.thenBy { candidate ->
                    // null means never synced -> oldest possible value -> highest priority.
                    candidate.knownPeerId
                        ?.let(context.peerStates::get)
                        ?.lastSuccessfulSyncAt
                        ?: Instant.EPOCH
                }.thenBy { candidate ->
                    candidate.knownPeerId
                        ?.let(context.peerStates::get)
                        ?.lastAttemptAt
                        ?: Instant.EPOCH
                }
            )
            .take(availableSlots)
    }
}
