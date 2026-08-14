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

import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.UUID

/**
 * GATT server exposing the Mesh service with RX (WRITE) and TX (NOTIFY).
 *
 * It only owns the radio service. Message framing / HELLO semantics are handled
 * by [BlePeerConnection] on the client side; the server acknowledges writes and
 * delivers outgoing bytes as TX notifications.
 */
class BleGattServer(context: Context) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(BluetoothManager::class.java)

    // Opened lazily in start() so no BLE permission is required at construction
    // (the app must not crash before the profile/permission flow completes).
    private var gattServer: BluetoothGattServer? = null

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
                    Timber.i("RX write from ${device.address}: ${value.size} bytes")
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
            Timber.i("server connection state ${device.address}: $newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onClientDisconnected?.invoke(device)
            }
        }
    }

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
    private fun openServer(): BluetoothGattServer? {
        gattServer?.let { return it }
        val managerRef = manager
        if (managerRef == null) {
            Timber.w("openGattServer: BluetoothManager unavailable")
            return null
        }
        val adapter = managerRef.adapter
        if (adapter == null || !adapter.isEnabled) {
            Timber.w("openGattServer: adapter not ready (enabled=${adapter?.isEnabled})")
            return null
        }
        val server = try {
            managerRef.openGattServer(appContext, callback)
        } catch (e: SecurityException) {
            Timber.w("openGattServer denied (missing BLUETOOTH_CONNECT?): ${e.message}")
            null
        } catch (e: Exception) {
            Timber.w("openGattServer failed: ${e.message}")
            null
        }
        if (server == null) {
            Timber.w("openGattServer returned null (stack refused)")
        }
        gattServer = server
        return server
    }

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean {
        if (started) return true
        // Bluetooth may not be ready the moment a profile exists; retry briefly
        // before giving up so the passive side actually has a GATT server.
        var attempts = 0
        while (attempts < 5 && !started) {
            attempts++
            val server = openServer()
            if (server == null) {
                delay(1000)
                continue
            }
            val service = BluetoothGattService(MeshGattUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(rxCharacteristic)
            service.addCharacteristic(txCharacteristic)
            val added = try {
                server.addService(service)
            } catch (e: SecurityException) {
                Timber.w("addService denied: ${e.message}")
                false
            }
            started = added
            Timber.i("server addService=$added (attempt $attempts)")
        }
        return started
    }

    @SuppressLint("MissingPermission")
    fun sendTo(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val server = gattServer ?: return false
        txCharacteristic.value = bytes
        val result = try {
            server.notifyCharacteristicChanged(device, txCharacteristic, false)
        } catch (e: SecurityException) {
            Timber.w("notify denied: ${e.message}")
            false
        }
        Timber.i("notify ${bytes.size} bytes to ${device.address} result=$result")
        return result
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        gattServer?.close()
        gattServer = null
        started = false
    }

}
