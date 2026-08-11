package com.example.meshsocial.data.repository

import com.example.meshsocial.domain.model.PendingSyncItem
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.SyncDirection
import com.example.meshsocial.domain.model.User
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

interface UserRepository {
    fun observeCurrentUser(): Flow<User?>
    suspend fun getCurrentUser(): User?
    suspend fun save(user: User)
}

interface PostRepository {
    fun observeAll(): Flow<List<Post>>
    suspend fun activePostIds(now: Instant): Set<UUID>
    suspend fun activePosts(ids: Set<UUID>, now: Instant): List<Post>
    suspend fun insert(post: Post): Boolean
    suspend fun insertAll(posts: List<Post>)
    suspend fun countByAuthorSince(authorId: UUID, since: Instant): Int
    suspend fun deleteExpired(now: Instant): Int
}

interface PeerStateRepository {
    suspend fun get(peerId: UUID): PeerState?
    suspend fun getAll(): List<PeerState>
    suspend fun save(state: PeerState)
}

interface PendingSyncRepository {
    suspend fun getForPeer(peerId: UUID): List<PendingSyncItem>
    suspend fun getAll(): List<PendingSyncItem>
    suspend fun countForPeer(peerId: UUID): Int
    suspend fun save(items: List<PendingSyncItem>)
    suspend fun remove(peerId: UUID, postId: UUID, direction: SyncDirection)
}
