package com.example.meshsocial

import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.sim.InMemoryPendingSyncRepository
import com.example.meshsocial.sim.InMemoryPeerStateRepository
import com.example.meshsocial.sim.InMemoryPostRepository
import com.example.meshsocial.sync.PairwiseAntiEntropySynchronizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PairwiseAntiEntropySynchronizerTest {
    @Test
    fun interruptedSyncResumesAndConverges() = runBlocking {
        val now = Instant.parse("2026-08-12T00:00:00Z")
        val aId = UUID.randomUUID()
        val bId = UUID.randomUUID()
        val aPosts = InMemoryPostRepository()
        val bPosts = InMemoryPostRepository()
        val aPending = InMemoryPendingSyncRepository()
        val bPending = InMemoryPendingSyncRepository()
        val aPeers = InMemoryPeerStateRepository()
        val bPeers = InMemoryPeerStateRepository()

        suspend fun seed(repo: InMemoryPostRepository, author: UUID, text: String) {
            repo.insert(Post(UUID.randomUUID(), author, text, now, now.plus(Duration.ofHours(24))))
        }
        seed(aPosts, aId, "A1")
        seed(aPosts, aId, "A2")
        seed(bPosts, bId, "B1")
        seed(bPosts, bId, "B2")
        seed(bPosts, bId, "B3")

        val sync = PairwiseAntiEntropySynchronizer()
        val a = PairwiseAntiEntropySynchronizer.Node(aId, aPosts, aPeers, aPending)
        val b = PairwiseAntiEntropySynchronizer.Node(bId, bPosts, bPeers, bPending)

        val first = sync.sync(a, b, now, maxTransfersPerDirection = 1)
        assertFalse(first.fullyReconciled)
        assertTrue(first.pendingOnAForB > 0 || first.pendingOnBForA > 0)

        val second = sync.sync(a, b, now.plusSeconds(1))
        assertTrue(second.fullyReconciled)
        assertEquals(aPosts.activePostIds(now), bPosts.activePostIds(now))
        assertEquals(5, aPosts.activePostIds(now).size)
    }
}
