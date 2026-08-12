package com.example.meshsocial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.protocol.MessageCodec
import com.example.meshsocial.protocol.SyncMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GATT client connection to a remote Mesh peer.
 *
 * Connection state machine:
 * CONNECTING -> CONNECTED -> DISCOVERING_SERVICES -> READY (TX notifications enabled)
 *
 * After READY it exchanges a HELLO so [remotePeerId] becomes known and the
 * connection is usable by SyncSession.
 */
class BlePeerConnection(
    context: Context,
    private val device: BluetoothDevice,
    private val localPeerId: UUID,
) : PeerConnection {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<SyncMessage> = _incomingMessages

    @Volatile
    override var remotePeerId: UUID? = null

    @Volatile
    private var ready = AtomicBoolean(false)

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "CONNECTED to ${device.address}, requesting MTU")
                    // Default ATT MTU (23) allows only ~20 bytes of payload, which
                    // truncates a 21-byte HELLO. Request a larger MTU before sending.
                    gatt.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "DISCONNECTED from ${device.address}")
                    gatt.close()
                    this@BlePeerConnection.gatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed status=$status")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(MeshGattUuids.SERVICE)
            if (service == null) {
                Log.w(TAG, "mesh service not found on ${device.address}")
                gatt.disconnect()
                return
            }
            rxCharacteristic = service.getCharacteristic(MeshGattUuids.RX)
            txCharacteristic = service.getCharacteristic(MeshGattUuids.TX)
            val tx = txCharacteristic
            if (rxCharacteristic == null || tx == null) {
                Log.w(TAG, "RX/TX characteristics missing on ${device.address}")
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(MeshGattUuids.CLIENT_CHARACTERISTIC_CONFIG)
            cccd?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic?.uuid == MeshGattUuids.TX &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                ready.set(true)
                Log.i(TAG, "READY, sending HELLO to ${device.address}")
                sendMessage(SyncMessage.Hello(protocolVersion = 1, peerId = localPeerId))
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "write failed status=$status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MeshGattUuids.TX) {
                val message = runCatching { MessageCodec.decode(value) }.getOrElse {
                    Log.w(TAG, "malformed message: ${it.message}")
                    return
                }
                if (message is SyncMessage.Hello) {
                    remotePeerId = message.peerId
                    Log.i(TAG, "HELLO from peer ${message.peerId}")
                }
                _incomingMessages.tryEmit(message)
            }
        }
    }

    /** Blocks until the connection is READY or fails. */
    @SuppressLint("MissingPermission")
    suspend fun connect(timeoutMillis: Long = 10_000): Boolean {
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        mainHandler.post {
            gatt = device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }
        // Poll READY with timeout.
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!ready.get()) {
            if (System.currentTimeMillis() > deadline) return false
            kotlinx.coroutines.delay(100)
        }
        return true
    }

    override suspend fun send(message: SyncMessage) {
        sendMessage(message)
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: SyncMessage) {
        val rx = rxCharacteristic ?: return
        val g = gatt ?: return
        val bytes = MessageCodec.encode(message)
        // V1 sends each message as a single write without response. Larger payloads
        // will need FrameCodec chunking (next milestone).
        rx.value = bytes
        val result = g.writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result == BluetoothStatusCodes.SUCCESS || result == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "sent ${message.javaClass.simpleName} (${bytes.size} bytes) to ${device.address}")
        } else {
            Log.w(TAG, "write failed result=$result")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun close() {
        val g = gatt
        if (g != null) {
            g.disconnect()
            g.close()
            gatt = null
        }
    }

    companion object {
        private const val TAG = "BlePeerConnection"
    }
}
