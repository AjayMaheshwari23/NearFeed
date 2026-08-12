package com.example.meshsocial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * GATT server exposing the Mesh service with RX (WRITE) and TX (NOTIFY).
 *
 * It only owns the radio service. Message framing / HELLO semantics are handled
 * by [BlePeerConnection] on the client side; the server acknowledges writes and
 * delivers outgoing bytes as TX notifications.
 */
class BleGattServer(context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid == MeshGattUuids.RX) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothStatusCodes.SUCCESS, offset, null)
                }
                if (value != null) {
                    Log.i(TAG, "RX write from ${device.address}: ${value.size} bytes")
                    onIncoming?.invoke(device, value)
                }
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothStatusCodes.SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            // Accept CCCD subscription. V1 ignores the exact value.
            gattServer?.sendResponse(device, requestId, BluetoothStatusCodes.SUCCESS, offset, null)
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "server connection state ${device.address}: $newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onClientDisconnected?.invoke(device)
            }
        }
    }

    private val gattServer: BluetoothGattServer? = manager.openGattServer(context, callback)

    private val txCharacteristic = BluetoothGattCharacteristic(
        MeshGattUuids.TX,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0, // no permission needed for notify
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                MeshGattUuids.CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
    }

    private val rxCharacteristic = BluetoothGattCharacteristic(
        MeshGattUuids.RX,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    private var started = false

    /** Called for every complete payload received on RX, with the sending device. */
    var onIncoming: ((BluetoothDevice, ByteArray) -> Unit)? = null

    /** Called when a connected client disconnects. */
    var onClientDisconnected: ((BluetoothDevice) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (started) return true
        val server = gattServer ?: return false
        val service = BluetoothGattService(MeshGattUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rxCharacteristic)
        service.addCharacteristic(txCharacteristic)
        val added = server.addService(service)
        started = added
        Log.i(TAG, "server addService=$added")
        return added
    }

    @SuppressLint("MissingPermission")
    fun sendTo(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val server = gattServer ?: return false
        txCharacteristic.value = bytes
        val result = server.notifyCharacteristicChanged(device, txCharacteristic, false)
        Log.i(TAG, "notify ${bytes.size} bytes to ${device.address} result=$result")
        return result
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        gattServer?.close()
        started = false
    }

    companion object {
        private const val TAG = "BleGattServer"
    }
}
