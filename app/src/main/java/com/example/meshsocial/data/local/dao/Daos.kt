package com.example.meshsocial.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.meshsocial.data.local.entity.PendingSyncItemEntity
import com.example.meshsocial.data.local.entity.PeerStateEntity
import com.example.meshsocial.data.local.entity.PostEntity
import com.example.meshsocial.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun observeCurrentUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getCurrentUser(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)
}

@Dao
interface PostDao {
    @Query("SELECT * FROM posts ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<PostEntity>>

    @Query("SELECT postId FROM posts WHERE expiresAtEpochMs > :nowEpochMs")
    suspend fun getActivePostIds(nowEpochMs: Long): List<String>

    @Query("SELECT * FROM posts WHERE postId IN (:postIds) AND expiresAtEpochMs > :nowEpochMs")
    suspend fun getActivePosts(postIds: List<String>, nowEpochMs: Long): List<PostEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(post: PostEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(posts: List<PostEntity>)

    @Query("SELECT COUNT(*) FROM posts WHERE authorId = :authorId AND createdAtEpochMs >= :sinceEpochMs")
    suspend fun countPostsByAuthorSince(authorId: String, sinceEpochMs: Long): Int

    @Query("DELETE FROM posts WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int
}

@Dao
interface PeerStateDao {
    @Query("SELECT * FROM peer_state WHERE peerId = :peerId LIMIT 1")
    suspend fun get(peerId: String): PeerStateEntity?

    @Query("SELECT * FROM peer_state")
    suspend fun getAll(): List<PeerStateEntity>

    @Upsert
    suspend fun upsert(state: PeerStateEntity)
}

@Dao
interface PendingSyncDao {
    @Query("SELECT * FROM pending_sync WHERE peerId = :peerId ORDER BY updatedAtEpochMs ASC")
    suspend fun getForPeer(peerId: String): List<PendingSyncItemEntity>

    @Query("SELECT * FROM pending_sync")
    suspend fun getAll(): List<PendingSyncItemEntity>

    @Query("SELECT COUNT(*) FROM pending_sync WHERE peerId = :peerId")
    suspend fun countForPeer(peerId: String): Int

    @Upsert
    suspend fun upsert(items: List<PendingSyncItemEntity>)

    @Query("DELETE FROM pending_sync WHERE peerId = :peerId AND postId = :postId AND direction = :direction")
    suspend fun delete(peerId: String, postId: String, direction: String)
}
