package com.example.meshsocial

import android.content.Context
import androidx.room.Room
import com.example.meshsocial.data.local.AppDatabase
import com.example.meshsocial.data.repository.RoomPendingSyncRepository
import com.example.meshsocial.data.repository.RoomPeerStateRepository
import com.example.meshsocial.data.repository.RoomPostRepository
import com.example.meshsocial.data.repository.RoomUserRepository
import com.example.meshsocial.domain.usecase.CreatePostUseCase

class AppContainer(context: Context) {
    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "mesh-social.db",
    ).build()

    val users = RoomUserRepository(database.userDao())
    val posts = RoomPostRepository(database.postDao())
    val peerStates = RoomPeerStateRepository(database.peerStateDao())
    val pendingSync = RoomPendingSyncRepository(database.pendingSyncDao())

    val createPost = CreatePostUseCase(posts)
}
