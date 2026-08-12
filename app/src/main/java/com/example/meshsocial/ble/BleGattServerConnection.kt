package com.example.meshsocial.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.protocol.MessageCodec
import com.example.meshsocial.protocol.SyncMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Server-side half of a GATT connection, exposed as a [PeerConnection] so the
 * passive peer can run a SyncSession just like the initiating side.
 *
 * Incoming bytes arrive from the client's RX writes (via [BleGattServer]);
 * outgoing messages go out as TX notifications to the client.
 *
 * Inbound messages are buffered in a Channel so none are lost between the first
 * RX write and the session coroutine subscribing to [incomingMessages].
 */
class BleGattServerConnection(
    private val gattServer: BleGattServer,
    private val device: BluetoothDevice,
) : PeerConnection {

    private val inbound = Channel<SyncMessage>(Channel.UNLIMITED)
    override val incomingMessages: Flow<SyncMessage> = inbound.receiveAsFlow()

    @Volatile
    override var remotePeerId: UUID? = null

    val transportId: String get() = device.address

    /** Feed a decoded byte payload received on RX. */
    fun onBytes(bytes: ByteArray) {
        val message = runCatching { MessageCodec.decode(bytes) }.getOrElse {
            Log.w(TAG, "malformed server message: ${it.message}")
            return
        }
        if (message is SyncMessage.Hello) {
            remotePeerId = message.peerId
        }
        inbound.trySend(message)
    }

    override suspend fun send(message: SyncMessage) {
        gattServer.sendTo(device, MessageCodec.encode(message))
    }

    override suspend fun close() {
        inbound.close()
    }

    companion object {
        private const val TAG = "BleGattServerConnection"
    }
}
