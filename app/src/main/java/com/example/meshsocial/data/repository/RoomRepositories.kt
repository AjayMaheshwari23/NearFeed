package com.example.meshsocial.data.repository

import com.example.meshsocial.data.local.dao.PendingSyncDao
import com.example.meshsocial.data.local.dao.PeerStateDao
import com.example.meshsocial.data.local.dao.PostDao
import com.example.meshsocial.data.local.dao.UserDao
import com.example.meshsocial.data.toDomain
import com.example.meshsocial.data.toEntity
import com.example.meshsocial.domain.model.PendingSyncItem
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.SyncDirection
import com.example.meshsocial.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class RoomUserRepository(private val dao: UserDao) : UserRepository {
    override fun observeCurrentUser(): Flow<User?> = dao.observeCurrentUser().map { it?.toDomain() }
    override suspend fun getCurrentUser(): User? = dao.getCurrentUser()?.toDomain()
    override suspend fun save(user: User) = dao.insert(user.toEntity())
}

class RoomPostRepository(private val dao: PostDao) : PostRepository {
    override fun observeAll(): Flow<List<Post>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun activePostIds(now: Instant): Set<UUID> =
        dao.getActivePostIds(now.toEpochMilli()).mapTo(linkedSetOf(), UUID::fromString)

    override suspend fun activePosts(ids: Set<UUID>, now: Instant): List<Post> {
        if (ids.isEmpty()) return emptyList()
        return dao.getActivePosts(ids.map(UUID::toString), now.toEpochMilli()).map { it.toDomain() }
    }

    override suspend fun insert(post: Post): Boolean = dao.insert(post.toEntity()) != -1L

    override suspend fun insertAll(posts: List<Post>) {
        if (posts.isNotEmpty()) dao.insertAll(posts.map { it.toEntity() })
    }

    override suspend fun countByAuthorSince(authorId: UUID, since: Instant): Int =
        dao.countPostsByAuthorSince(authorId.toString(), since.toEpochMilli())

    override suspend fun deleteExpired(now: Instant): Int = dao.deleteExpired(now.toEpochMilli())
}

class RoomPeerStateRepository(private val dao: PeerStateDao) : PeerStateRepository {
    override suspend fun get(peerId: UUID): PeerState? = dao.get(peerId.toString())?.toDomain()
    override suspend fun getAll(): List<PeerState> = dao.getAll().map { it.toDomain() }
    override suspend fun save(state: PeerState) = dao.upsert(state.toEntity())
}

class RoomPendingSyncRepository(private val dao: PendingSyncDao) : PendingSyncRepository {
    override suspend fun getForPeer(peerId: UUID): List<PendingSyncItem> =
        dao.getForPeer(peerId.toString()).map { it.toDomain() }

    override suspend fun getAll(): List<PendingSyncItem> = dao.getAll().map { it.toDomain() }

    override suspend fun countForPeer(peerId: UUID): Int = dao.countForPeer(peerId.toString())

    override suspend fun save(items: List<PendingSyncItem>) {
        if (items.isNotEmpty()) dao.upsert(items.map { it.toEntity() })
    }

    override suspend fun remove(peerId: UUID, postId: UUID, direction: SyncDirection) =
        dao.delete(peerId.toString(), postId.toString(), direction.name)
}
