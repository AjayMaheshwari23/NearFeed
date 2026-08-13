package com.example.meshsocial

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.example.meshsocial.ble.BleGattConnector
import com.example.meshsocial.ble.BleGattServer
import com.example.meshsocial.ble.BleGattServerConnection
import com.example.meshsocial.ble.BlePeerDiscovery
import com.example.meshsocial.connection.ConnectionCoordinator
import com.example.meshsocial.data.local.AppDatabase
import com.example.meshsocial.data.repository.RoomPendingSyncRepository
import com.example.meshsocial.data.repository.RoomPeerStateRepository
import com.example.meshsocial.data.repository.RoomPostRepository
import com.example.meshsocial.data.repository.RoomUserRepository
import com.example.meshsocial.discovery.PeerDiscovery
import com.example.meshsocial.domain.usecase.CreatePostUseCase
import com.example.meshsocial.sync.SyncSession
import com.example.meshsocial.topology.DefaultTopologyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "AppContainer"

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
        localPeerId = { runBlockingOnIo { users.getCurrentUser()?.userId } },
    )

    val gattServer = BleGattServer(appContext)

    val connectionCoordinator = ConnectionCoordinator(
        topologyPolicy = DefaultTopologyPolicy(),
        connector = BleGattConnector(appContext, localPeerId = {
            runBlockingOnIo { users.getCurrentUser()?.userId }
        }),
        maxActiveSyncs = 3, // top-K connection slots
    )

    val createPost = CreatePostUseCase(posts)

    /** Dev/diagnostic events from sync sessions (posts requested/inserted, completions). */
    val syncEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private val serverConnections = mutableMapOf<String, BleGattServerConnection>()

    init {
        // Client-initiated connections: start a sync session per established link.
        connectionCoordinator.onConnected = { connection ->
            appScope.launch {
                try {
                    val localId = users.getCurrentUser()?.userId ?: return@launch
                    SyncSession(
                        localId, connection, posts, pendingSync, peerStates,
                        onEvent = { syncEvents.tryEmit(it) },
                    ).start()
                } finally {
                    // Session flow ended => link dropped; free the sync slot.
                    connectionCoordinator.onLinkClosed(connection)
                }
            }
        }

        // Server-accepted connections: route incoming bytes into a server-side
        // connection and start a sync session on it (the passive peer).
        gattServer.onIncoming = { device, bytes ->
            val serverConn = serverConnections.getOrPut(device.address) {
                BleGattServerConnection(gattServer, device).also { conn ->
                    appScope.launch {
                        try {
                            val localId = users.getCurrentUser()?.userId ?: return@launch
                            SyncSession(
                                localId, conn, posts, pendingSync, peerStates,
                                onEvent = { syncEvents.tryEmit(it) },
                            ).start()
                        } finally {
                            connectionCoordinator.onLinkClosed(conn)
                        }
                    }
                }
            }
            serverConn.onBytes(bytes)
        }

        gattServer.onClientDisconnected = { device ->
            serverConnections.remove(device.address)?.let { conn ->
                Log.i(TAG, "cleaned up server connection for ${device.address}")
                appScope.launch { conn.close() }
            }
        }
    }

    suspend fun currentPeerId(): UUID? = users.getCurrentUser()?.userId

    private fun <T> runBlockingOnIo(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
