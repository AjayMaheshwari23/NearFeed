package com.example.meshsocial

import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.discovery.SUPPORTED_PROTOCOL_VERSION
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.topology.DefaultTopologyPolicy
import com.example.meshsocial.topology.TopologyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class DefaultTopologyPolicyTest {
    private val now = Instant.parse("2026-08-12T00:00:00Z")
    private val self = UUID.randomUUID()

    private fun candidate(id: String, knownPeerId: UUID? = null, rssi: Int = -60) =
        PeerCandidate(id, knownPeerId, rssi, now)

    @Test
    fun pendingPeerWinsSelection() {
        val pendingPeer = UUID.randomUUID()
        val recentPeer = UUID.randomUUID()
        val pending = candidate("A", pendingPeer)
        val recent = candidate("B", recentPeer)

        val context = TopologyContext(
            selfPeerId = self,
            peerStates = mapOf(
                pendingPeer to PeerState(pendingPeer, lastSuccessfulSyncAt = now.minusSeconds(3600)),
                recentPeer to PeerState(recentPeer, lastSuccessfulSyncAt = now),
            ),
            pendingCounts = mapOf(pendingPeer to 3),
            pendingSince = mapOf(pendingPeer to now.minusSeconds(300)),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(1))
            .selectPeers(listOf(recent, pending), context, availableSlots = 1)

        assertEquals(pendingPeer, selected.single().knownPeerId)
    }

    @Test
    fun neverSyncedBeatsRecentlySynced() {
        val never = candidate("A", UUID.randomUUID())
        val synced = candidate("B", UUID.randomUUID())
        val context = TopologyContext(
            selfPeerId = self,
            peerStates = mapOf(synced.knownPeerId!! to PeerState(synced.knownPeerId!!, lastSuccessfulSyncAt = now)),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(2))
            .selectPeers(listOf(synced, never), context, availableSlots = 1)

        assertEquals(never.knownPeerId, selected.single().knownPeerId)
    }

    @Test
    fun p2SortsOldestSyncFirst() {
        val old = candidate("A", UUID.randomUUID())
        val fresh = candidate("B", UUID.randomUUID())
        val context = TopologyContext(
            selfPeerId = self,
            peerStates = mapOf(
                old.knownPeerId!! to PeerState(old.knownPeerId!!, lastSuccessfulSyncAt = now.minusSeconds(7200)),
                fresh.knownPeerId!! to PeerState(fresh.knownPeerId!!, lastSuccessfulSyncAt = now.minusSeconds(60)),
            ),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(3))
            .selectPeers(listOf(fresh, old), context, availableSlots = 1)

        assertEquals(old.knownPeerId, selected.single().knownPeerId)
    }

    @Test
    fun p0SortsOldestPendingWorkFirst() {
        val olderPending = candidate("A", UUID.randomUUID())
        val newerPending = candidate("B", UUID.randomUUID())
        val context = TopologyContext(
            selfPeerId = self,
            pendingCounts = mapOf(
                olderPending.knownPeerId!! to 2,
                newerPending.knownPeerId!! to 2,
            ),
            pendingSince = mapOf(
                olderPending.knownPeerId!! to now.minusSeconds(1800),
                newerPending.knownPeerId!! to now.minusSeconds(60),
            ),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(4))
            .selectPeers(listOf(newerPending, olderPending), context, availableSlots = 1)

        assertEquals(olderPending.knownPeerId, selected.single().knownPeerId)
    }

    @Test
    fun removesSelfActiveConnectingAndWeak() {
        val selfCandidate = candidate("SELF", self, rssi = -30)
        val active = candidate("ACTIVE", UUID.randomUUID(), rssi = -30)
        val connecting = candidate("CONNECTING", UUID.randomUUID(), rssi = -30)
        val weak = candidate("WEAK", UUID.randomUUID(), rssi = -120)
        val good = candidate("GOOD", UUID.randomUUID(), rssi = -50)

        val context = TopologyContext(
            selfPeerId = self,
            activeCandidateIds = setOf(active.candidateId),
            connectingCandidateIds = setOf(connecting.candidateId),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(5))
            .selectPeers(listOf(selfCandidate, active, connecting, weak, good), context, availableSlots = 10)

        assertEquals(listOf("GOOD"), selected.map { it.candidateId })
    }

    @Test
    fun removesIncompatibleProtocolVersions() {
        val incompatible = candidate("V1", UUID.randomUUID(), rssi = -40).copy(protocolVersion = 2)
        val compatible = candidate("V0", UUID.randomUUID(), rssi = -40).copy(protocolVersion = SUPPORTED_PROTOCOL_VERSION)
        val unknown = candidate("UNKNOWN", UUID.randomUUID(), rssi = -40)

        val context = TopologyContext(selfPeerId = self, now = now)

        val selected = DefaultTopologyPolicy(random = Random(6))
            .selectPeers(listOf(incompatible, compatible, unknown), context, availableSlots = 10)

        assertEquals(setOf("V0", "UNKNOWN"), selected.map { it.candidateId }.toSet())
    }

    @Test
    fun respectsFailureCooldown() {
        val cooledDown = candidate("COOL", UUID.randomUUID(), rssi = -40)
        val normal = candidate("NORMAL", UUID.randomUUID(), rssi = -40)
        val context = TopologyContext(
            selfPeerId = self,
            failureCooldownUntil = mapOf(cooledDown.knownPeerId!! to now.plusSeconds(60)),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(7))
            .selectPeers(listOf(cooledDown, normal), context, availableSlots = 10)

        assertEquals(listOf("NORMAL"), selected.map { it.candidateId })
    }

    @Test
    fun capsAtAvailableSlots() {
        val peers = (1..5).map { candidate("P$it", UUID.randomUUID(), rssi = -40) }
        val context = TopologyContext(selfPeerId = self, now = now)

        val selected = DefaultTopologyPolicy(random = Random(8))
            .selectPeers(peers, context, availableSlots = 2)

        assertEquals(2, selected.size)
    }

    @Test
    fun returnsEmptyWhenSlotsZero() {
        val peers = listOf(candidate("P1", UUID.randomUUID(), rssi = -40))
        val context = TopologyContext(selfPeerId = self, now = now)

        assertTrue(
            DefaultTopologyPolicy(random = Random(9))
                .selectPeers(peers, context, availableSlots = 0)
                .isEmpty()
        )
    }
}
