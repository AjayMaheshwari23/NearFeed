package com.example.meshsocial.sim

import com.example.meshsocial.data.repository.PendingSyncRepository
import com.example.meshsocial.data.repository.PeerStateRepository
import com.example.meshsocial.data.repository.PostRepository
import com.example.meshsocial.domain.model.PendingSyncItem
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.SyncDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

class InMemoryPostRepository : PostRepository {
    private val rows = linkedMapOf<UUID, Post>()
    private val state = MutableStateFlow<List<Post>>(emptyList())

    override fun observeAll(): Flow<List<Post>> = state

    override suspend fun activePostIds(now: Instant): Set<UUID> =
        rows.values.filter { it.expiresAt.isAfter(now) }.mapTo(linkedSetOf()) { it.postId }

    override suspend fun activePosts(ids: Set<UUID>, now: Instant): List<Post> =
        ids.mapNotNull(rows::get).filter { it.expiresAt.isAfter(now) }

    override suspend fun insert(post: Post): Boolean {
        val inserted = rows.putIfAbsent(post.postId, post) == null
        if (inserted) publish()
        return inserted
    }

    override suspend fun insertAll(posts: List<Post>) {
        posts.forEach { rows.putIfAbsent(it.postId, it) }
        publish()
    }

    override suspend fun countByAuthorSince(authorId: UUID, since: Instant): Int =
        rows.values.count { it.authorId == authorId && !it.createdAt.isBefore(since) }

    override suspend fun deleteExpired(now: Instant): Int {
        val before = rows.size
        rows.entries.removeAll { !it.value.expiresAt.isAfter(now) }
        publish()
        return before - rows.size
    }

    private fun publish() {
        state.value = rows.values.sortedByDescending { it.createdAt }
    }
}

class InMemoryPeerStateRepository : PeerStateRepository {
    private val rows = linkedMapOf<UUID, PeerState>()
    override suspend fun get(peerId: UUID): PeerState? = rows[peerId]
    override suspend fun getAll(): List<PeerState> = rows.values.toList()
    override suspend fun save(state: PeerState) { rows[state.peerId] = state }
}

class InMemoryPendingSyncRepository : PendingSyncRepository {
    private val rows = linkedMapOf<Triple<UUID, UUID, SyncDirection>, PendingSyncItem>()

    override suspend fun getForPeer(peerId: UUID): List<PendingSyncItem> =
        rows.values.filter { it.peerId == peerId }.sortedBy { it.updatedAt }

    override suspend fun getAll(): List<PendingSyncItem> = rows.values.toList()

    override suspend fun countForPeer(peerId: UUID): Int = rows.values.count { it.peerId == peerId }

    override suspend fun save(items: List<PendingSyncItem>) {
        items.forEach { rows[Triple(it.peerId, it.postId, it.direction)] = it }
    }

    override suspend fun remove(peerId: UUID, postId: UUID, direction: SyncDirection) {
        rows.remove(Triple(peerId, postId, direction))
    }
}
