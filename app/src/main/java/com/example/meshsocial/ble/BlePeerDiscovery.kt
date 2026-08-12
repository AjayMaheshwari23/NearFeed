package com.example.meshsocial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.discovery.PeerDiscovery
import com.example.meshsocial.discovery.SUPPORTED_PROTOCOL_VERSION
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

/**
 * BLE discovery adapter. It does NOT establish GATT connections yet.
 *
 * The advertisement carries the stable peer UUID in manufacturer data so the
 * scanner can resolve [PeerCandidate.knownPeerId] and protocol version without
 * a full GATT handshake (and the topology policy can rank peers deterministically).
 */
class BlePeerDiscovery(
    context: Context,
    private val localPeerId: () -> UUID?,
) : PeerDiscovery {
    companion object {
        private const val TAG = "BlePeerDiscovery"
        private const val MANUFACTURER_ID = 0x4D53 // "MS" in ASCII
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val advertiser get() = adapter?.bluetoothLeAdvertiser

    private val _peers = MutableSharedFlow<PeerCandidate>(extraBufferCapacity = 64)
    override val discoveredPeers: Flow<PeerCandidate> = _peers

    private var started = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Filter in-app instead of via ScanFilter: APCF (hardware) filtering is
            // unreliable in the emulated BLE radio. Mesh peers advertise our
            // manufacturer data carrying [version byte][peer UUID].
            val payload = result.scanRecord?.manufacturerSpecificData?.get(MANUFACTURER_ID)
            if (payload == null || payload.size != 17) return

            val buffer = ByteBuffer.wrap(payload)
            val protocolVersion = buffer.get().toInt()
            val peerId = UUID(buffer.long, buffer.long)

            Log.i(
                TAG,
                "FOUND PEER device=${result.device.address} rssi=${result.rssi} " +
                    "peerId=${peerId.toString().take(8)} proto=$protocolVersion"
            )
            _peers.tryEmit(
                PeerCandidate(
                    candidateId = result.device.address,
                    knownPeerId = peerId,
                    rssi = result.rssi,
                    discoveredAt = Instant.now(),
                    protocolVersion = protocolVersion,
                )
            )
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed error=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        if (started) return
        val localScanner = scanner ?: return
        val localAdvertiser = advertiser ?: return

        // No hardware ScanFilter: the emulated BLE radio drops filtered results, and
        // mesh-peer matching happens in onScanResult (manufacturer data).
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        localScanner.startScan(emptyList(), scanSettings, scanCallback)

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Advertise ONLY manufacturer data. A 128-bit service UUID (18 bytes) plus
        // the peer-UUID payload (20 bytes) exceeds the 31-byte legacy advertising
        // limit and startAdvertising fails. The mesh service UUID is still resolved
        // via GATT service discovery after the connection is established.
        val builder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
        localPeerId()?.let { peerId ->
            val payload = ByteBuffer.allocate(17)
                .put(SUPPORTED_PROTOCOL_VERSION.toByte())
                .putLong(peerId.mostSignificantBits)
                .putLong(peerId.leastSignificantBits)
                .array()
            builder.addManufacturerData(MANUFACTURER_ID, payload)
        }
        localAdvertiser.startAdvertising(advertiseSettings, builder.build(), advertiseCallback)
        started = true
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        if (!started) return
        scanner?.stopScan(scanCallback)
        advertiser?.stopAdvertising(advertiseCallback)
        started = false
    }
}
