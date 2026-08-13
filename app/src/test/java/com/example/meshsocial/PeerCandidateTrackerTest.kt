package com.example.meshsocial

import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.discovery.PeerCandidateTracker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PeerCandidateTrackerTest {
    private val now = Instant.parse("2026-08-12T00:00:00Z")

    @Test
    fun dedupesByCandidateIdKeepingLatestReading() {
        val tracker = PeerCandidateTracker()
        tracker.onCandidate(PeerCandidate("A", rssi = -70, discoveredAt = now))
        val afterSecondRead = tracker.onCandidate(PeerCandidate("A", rssi = -60, discoveredAt = now.plusSeconds(1)))

        assertEquals(1, afterSecondRead.size)
        assertEquals(-60, afterSecondRead.single().rssi)
        assertEquals(now.plusSeconds(1), afterSecondRead.single().discoveredAt)
    }

    @Test
    fun dedupesByPeerIdAcrossChangingMacs() {
        val peerId = UUID.randomUUID()
        val tracker = PeerCandidateTracker()
        tracker.onCandidate(PeerCandidate("MAC1", knownPeerId = peerId, rssi = -70, discoveredAt = now))
        val afterNewMac = tracker.onCandidate(
            PeerCandidate("MAC2", knownPeerId = peerId, rssi = -55, discoveredAt = now.plusSeconds(30))
        )

        assertEquals(1, afterNewMac.size)
        assertEquals("MAC2", afterNewMac.single().candidateId)
        assertEquals(peerId, afterNewMac.single().knownPeerId)
    }

    @Test
    fun sortsByRssiDescending() {
        val tracker = PeerCandidateTracker()
        tracker.onCandidate(PeerCandidate("A", rssi = -80, discoveredAt = now))
        tracker.onCandidate(PeerCandidate("B", rssi = -50, discoveredAt = now))
        tracker.onCandidate(PeerCandidate("C", rssi = -90, discoveredAt = now))

        val sorted = tracker.snapshot()
        assertEquals(listOf("B", "A", "C"), sorted.map { it.candidateId })
    }

    @Test
    fun capsAtMaxCandidatesDroppingOldest() {
        val tracker = PeerCandidateTracker(maxCandidates = 2)
        tracker.onCandidate(PeerCandidate("A", rssi = -70, discoveredAt = now))
        tracker.onCandidate(PeerCandidate("B", rssi = -70, discoveredAt = now))
        tracker.onCandidate(PeerCandidate("C", rssi = -70, discoveredAt = now))

        val snap = tracker.snapshot()
        assertEquals(setOf("B", "C"), snap.map { it.candidateId }.toSet())
    }
}
