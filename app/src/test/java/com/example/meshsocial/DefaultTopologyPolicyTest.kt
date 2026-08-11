package com.example.meshsocial

import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.topology.DefaultTopologyPolicy
import com.example.meshsocial.topology.TopologyContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class DefaultTopologyPolicyTest {
    @Test
    fun pendingPeerWinsSelection() {
        val self = UUID.randomUUID()
        val pendingPeer = UUID.randomUUID()
        val recentPeer = UUID.randomUUID()
        val now = Instant.parse("2026-08-12T00:00:00Z")

        val pending = PeerCandidate("A", pendingPeer, -60, now)
        val recent = PeerCandidate("B", recentPeer, -40, now)

        val context = TopologyContext(
            selfPeerId = self,
            peerStates = mapOf(
                pendingPeer to PeerState(pendingPeer, lastSuccessfulSyncAt = now.minusSeconds(3600)),
                recentPeer to PeerState(recentPeer, lastSuccessfulSyncAt = now),
            ),
            pendingCounts = mapOf(pendingPeer to 3),
            now = now,
        )

        val selected = DefaultTopologyPolicy(random = Random(1))
            .selectPeers(listOf(recent, pending), context, availableSlots = 1)

        assertEquals(pendingPeer, selected.single().knownPeerId)
    }
}
