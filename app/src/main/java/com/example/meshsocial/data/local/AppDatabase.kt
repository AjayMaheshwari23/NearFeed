package com.example.meshsocial.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.meshsocial.data.local.dao.PendingSyncDao
import com.example.meshsocial.data.local.dao.PeerStateDao
import com.example.meshsocial.data.local.dao.PostDao
import com.example.meshsocial.data.local.dao.UserDao
import com.example.meshsocial.data.local.entity.PendingSyncItemEntity
import com.example.meshsocial.data.local.entity.PeerStateEntity
import com.example.meshsocial.data.local.entity.PostEntity
import com.example.meshsocial.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        PostEntity::class,
        PeerStateEntity::class,
        PendingSyncItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun postDao(): PostDao
    abstract fun peerStateDao(): PeerStateDao
    abstract fun pendingSyncDao(): PendingSyncDao
}
