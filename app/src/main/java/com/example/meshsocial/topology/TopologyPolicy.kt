package com.example.meshsocial.topology

import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.discovery.SUPPORTED_PROTOCOL_VERSION
import com.example.meshsocial.domain.model.PeerState
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

data class TopologyContext(
    val selfPeerId: UUID,
    val activeCandidateIds: Set<String> = emptySet(),
    val connectingCandidateIds: Set<String> = emptySet(),
    val peerStates: Map<UUID, PeerState> = emptyMap(),
    val pendingCounts: Map<UUID, Int> = emptyMap(),
    // Oldest pending sync item's updatedAt per peer, used to rank unfinished-sync peers.
    val pendingSince: Map<UUID, Instant> = emptyMap(),
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
 * Connection Topology Algorithm.
 *
 * ```
 * selectPeers(candidates, K):
 *   1. Remove: self, already-connected, already-connecting,
 *      incompatible protocol versions, short failure cooldown, weak peers.
 *   2. Partition:
 *        P0 = peers with pending sync
 *        P1 = peers never synced
 *        P2 = previously synced peers
 *   3. Sort:
 *        P0 -> oldest pending work first
 *        P1 -> oldest discovered/attempted first
 *        P2 -> oldest successful sync first
 *   4. Small randomness for ties.
 *   5. Select at most K peers.
 * ```
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

        // 1) Remove-rules.
        val eligible = candidates
            .filter { it.rssi >= minUsableRssi }
            .filterNot { it.candidateId in context.activeCandidateIds }
            .filterNot { it.candidateId in context.connectingCandidateIds }
            .filterNot { it.knownPeerId == context.selfPeerId }
            .filter { it.protocolVersion == null || it.protocolVersion == SUPPORTED_PROTOCOL_VERSION }
            .filterNot { candidate ->
                val peerId = candidate.knownPeerId ?: return@filterNot false
                val until = context.failureCooldownUntil[peerId] ?: return@filterNot false
                until.isAfter(context.now)
            }

        // 2) Partition.
        val (p0, rest) = eligible.partition { candidate ->
            val peerId = candidate.knownPeerId ?: return@partition false
            (context.pendingCounts[peerId] ?: 0) > 0
        }
        val (p1, p2) = rest.partition { candidate ->
            val peerId = candidate.knownPeerId ?: return@partition true
            context.peerStates[peerId]?.lastSuccessfulSyncAt == null
        }

        // One stable random tie-breaker per candidate per call.
        val tieBreaker = mutableMapOf<String, Float>()
        fun tieFor(candidate: PeerCandidate): Float =
            tieBreaker.getOrPut(candidate.candidateId) { random.nextFloat() }

        // 3) Sort within each partition; 4) random tie-break.
        val p0Sorted = p0.sortedWith(
            compareBy<PeerCandidate> { candidate ->
                candidate.knownPeerId?.let { context.pendingSince[it] } ?: Instant.EPOCH
            }.thenBy { tieFor(it) }
        )
        val p1Sorted = p1.sortedWith(
            compareBy<PeerCandidate> { candidate ->
                candidate.knownPeerId?.let { context.peerStates[it]?.lastAttemptAt } ?: candidate.discoveredAt
            }.thenBy { tieFor(it) }
        )
        val p2Sorted = p2.sortedWith(
            compareBy<PeerCandidate> { candidate ->
                candidate.knownPeerId?.let { context.peerStates[it]?.lastSuccessfulSyncAt }
            }.thenBy { tieFor(it) }
        )

        // 5) Select at most K (P0 first, then P1, then P2).
        return (p0Sorted + p1Sorted + p2Sorted).take(availableSlots)
    }
}
