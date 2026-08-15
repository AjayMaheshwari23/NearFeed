package com.example.meshsocial.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.meshsocial.data.local.dao.PendingSyncDao
import com.example.meshsocial.data.local.dao.PeerStateDao
import com.example.meshsocial.data.local.dao.PostDao
import com.example.meshsocial.data.local.dao.UserDao
import com.example.meshsocial.data.local.entity.PendingSyncItemEntity
import com.example.meshsocial.data.local.entity.PeerStateEntity
import com.example.meshsocial.data.local.entity.PostEntity
import com.example.meshsocial.data.local.entity.UserEntity

/**
 * v1 → v2: posts carry the author's display name so remote peers can show the
 * author instead of a bare UUID.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE posts ADD COLUMN authorDisplayName TEXT NOT NULL DEFAULT ''"
        )
    }
}

@Database(
    entities = [
        UserEntity::class,
        PostEntity::class,
        PeerStateEntity::class,
        PendingSyncItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun postDao(): PostDao
    abstract fun peerStateDao(): PeerStateDao
    abstract fun pendingSyncDao(): PendingSyncDao
}
