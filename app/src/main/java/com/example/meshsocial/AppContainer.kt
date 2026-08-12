package com.example.meshsocial

import android.content.Context
import androidx.room.Room
import com.example.meshsocial.ble.BleGattConnector
import com.example.meshsocial.ble.BleGattServer
import com.example.meshsocial.ble.BlePeerDiscovery
import com.example.meshsocial.connection.ConnectionCoordinator
import com.example.meshsocial.data.local.AppDatabase
import com.example.meshsocial.data.repository.RoomPendingSyncRepository
import com.example.meshsocial.data.repository.RoomPeerStateRepository
import com.example.meshsocial.data.repository.RoomPostRepository
import com.example.meshsocial.data.repository.RoomUserRepository
import com.example.meshsocial.discovery.PeerDiscovery
import com.example.meshsocial.domain.usecase.CreatePostUseCase
import com.example.meshsocial.protocol.MessageCodec
import com.example.meshsocial.protocol.SyncMessage
import com.example.meshsocial.topology.DefaultTopologyPolicy
import java.util.UUID

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "mesh-social.db",
    ).build()

    val users = RoomUserRepository(database.userDao())
    val posts = RoomPostRepository(database.postDao())
    val peerStates = RoomPeerStateRepository(database.peerStateDao())
    val pendingSync = RoomPendingSyncRepository(database.pendingSyncDao())

    val peerDiscovery: PeerDiscovery = BlePeerDiscovery(
        appContext,
        localPeerId = {
            kotlinx.coroutines.runBlocking { users.getCurrentUser()?.userId }
        },
    )

    val gattServer = BleGattServer(appContext)

    val connectionCoordinator = ConnectionCoordinator(
        topologyPolicy = DefaultTopologyPolicy(),
        connector = BleGattConnector(appContext, localPeerId = {
            kotlinx.coroutines.runBlocking { users.getCurrentUser()?.userId }
        }),
        maxActiveSyncs = 3, // top-K connection slots
    )

    val createPost = CreatePostUseCase(posts)

    init {
        // GATT server side of the HELLO handshake: reply with our own HELLO so the
        // connecting client can learn our peer UUID.
        gattServer.onIncoming = { device, bytes ->
            val message = runCatching { MessageCodec.decode(bytes) }.getOrNull()
            val localPeerId = kotlinx.coroutines.runBlocking { users.getCurrentUser()?.userId }
            if (message is SyncMessage.Hello && localPeerId != null) {
                val reply = MessageCodec.encode(SyncMessage.Hello(protocolVersion = 1, peerId = localPeerId))
                val ok = gattServer.sendTo(device, reply)
                android.util.Log.i("AppContainer", "replied HELLO to ${device.address} ok=$ok")
            }
        }
    }

    suspend fun currentPeerId(): UUID? = users.getCurrentUser()?.userId
}
