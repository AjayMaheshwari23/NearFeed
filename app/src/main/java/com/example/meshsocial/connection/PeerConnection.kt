package com.example.meshsocial.connection

import com.example.meshsocial.protocol.SyncMessage
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A transport-agnostic connected peer. BLE GATT will implement this later. */
interface PeerConnection {
    val remotePeerId: UUID?
    val incomingMessages: Flow<SyncMessage>
    suspend fun send(message: SyncMessage)
    suspend fun close()
}
