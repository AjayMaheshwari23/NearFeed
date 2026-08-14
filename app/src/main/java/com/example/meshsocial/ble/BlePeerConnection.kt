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

import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.protocol.MessageCodec
import com.example.meshsocial.protocol.SyncMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
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

    private val _incomingMessages = Channel<SyncMessage>(Channel.UNLIMITED)
    override val incomingMessages: Flow<SyncMessage> = _incomingMessages.receiveAsFlow()

    @Volatile
    override var remotePeerId: UUID? = null

    /** Pre-bind the peer UUID from the advertisement (before HELLO completes). */
    fun bindRemotePeerId(peerId: UUID) {
        remotePeerId = peerId
    }

    @Volatile
    private var ready = AtomicBoolean(false)

    private val discoveryRequested = AtomicBoolean(false)

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.i("connectionStateChange ${device.address} status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("CONNECTED to ${device.address}, requesting MTU")
                    // Default ATT MTU (23) allows only ~20 bytes of payload, which
                    // truncates a 21-byte HELLO. Request a larger MTU before sending.
                    gatt.requestMtu(247)
                    // Some devices never deliver onMtuChanged (or fail it). Always
                    // proceed to service discovery so the link reaches READY.
                    discover()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.w("DISCONNECTED from ${device.address}")
                    gatt.close()
                    this@BlePeerConnection.gatt = null
                    // Signal listeners that this link is gone so the coordinator
                    // can prune the active connection and free a sync slot.
                    _incomingMessages.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.i("MTU changed to $mtu status=$status")
            // Discover regardless of MTU result; a small MTU only limits payload size.
            discover()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("service discovery failed status=$status")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(MeshGattUuids.SERVICE)
            if (service == null) {
                Timber.w("mesh service not found on ${device.address}")
                gatt.disconnect()
                return
            }
            rxCharacteristic = service.getCharacteristic(MeshGattUuids.RX)
            txCharacteristic = service.getCharacteristic(MeshGattUuids.TX)
            val tx = txCharacteristic
            if (rxCharacteristic == null || tx == null) {
                Timber.w("RX/TX characteristics missing on ${device.address}")
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
                Timber.i("READY, awaiting session start")
                // The SyncSession owns the HELLO handshake; do not send one here.
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("write failed status=$status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MeshGattUuids.TX) {
                Timber.i("RX notify ${value.size} bytes from ${device.address}")
                val message = runCatching { MessageCodec.decode(value) }.getOrElse {
                    Timber.w("malformed message: ${it.message}")
                    return
                }
                if (message is SyncMessage.Hello) {
                    remotePeerId = message.peerId
                    Timber.i("HELLO from peer ${message.peerId}")
                }
                val sent = _incomingMessages.trySend(message)
                Timber.i("queued ${message.javaClass.simpleName} to channel ok=$sent (${device.address})")
            }
        }
    }

    /** Kick off service discovery once (called from CONNECTED and MTU callbacks). */
    @SuppressLint("MissingPermission")
    private fun discover() {
        val g = gatt ?: return
        if (discoveryRequested.compareAndSet(false, true)) {
            Timber.i("discovering services on ${device.address}")
            g.discoverServices()
        }
    }

    /** Blocks until the connection is READY or fails. */
    @SuppressLint("MissingPermission")
    suspend fun connect(timeoutMillis: Long = 15_000): Boolean {
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        mainHandler.post {
            val g = device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
            gatt = g
            Timber.i("connectGatt called addr=${device.address} type=${device.type} gatt=$g")
        }
        // Poll READY with timeout.
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!ready.get()) {
            if (System.currentTimeMillis() > deadline) {
                Timber.w("connect timeout for ${device.address}; ready=${ready.get()} gatt=${gatt != null}")
                return false
            }
            kotlinx.coroutines.delay(100)
        }
        return true
    }

    override suspend fun send(message: SyncMessage) {
        sendMessage(message, retriesRemaining = 3)
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendMessage(message: SyncMessage, retriesRemaining: Int) {
        val rx = rxCharacteristic ?: return
        val g = gatt ?: return
        val bytes = MessageCodec.encode(message)
        // V1 sends each message as a single write without response. Larger payloads
        // will need FrameCodec chunking (next milestone).
        rx.value = bytes
        val result = g.writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result == BluetoothStatusCodes.SUCCESS || result == BluetoothGatt.GATT_SUCCESS) {
            Timber.i("sent ${message.javaClass.simpleName} (${bytes.size} bytes) to ${device.address}")
        } else {
            Timber.w("write failed result=$result (${message.javaClass.simpleName})")
            // 201 = ERROR_DEVICE_DISCONNECTED. The emulated GATT link can briefly
            // report the device as disconnected right after READY; retry shortly.
            if (retriesRemaining > 0 && gatt != null) {
                delay(300)
                sendMessage(message, retriesRemaining - 1)
            }
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
        _incomingMessages.close()
    }

}
