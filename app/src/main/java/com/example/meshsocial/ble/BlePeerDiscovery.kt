package com.example.meshsocial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.discovery.PeerDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

/**
 * BLE discovery adapter only. It does NOT establish GATT connections yet.
 * Caller must ensure runtime Bluetooth permissions are granted.
 */
class BlePeerDiscovery(context: Context) : PeerDiscovery {
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
            _peers.tryEmit(
                PeerCandidate(
                    candidateId = result.device.address,
                    knownPeerId = null, // learned later during HELLO
                    rssi = result.rssi,
                    discoveredAt = Instant.now(),
                )
            )
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        if (started) return
        val localScanner = scanner ?: return
        val localAdvertiser = advertiser ?: return

        val service = ParcelUuid(MeshGattUuids.SERVICE)
        val filter = ScanFilter.Builder().setServiceUuid(service).build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        localScanner.startScan(listOf(filter), scanSettings, scanCallback)

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(service)
            .setIncludeDeviceName(false)
            .build()
        localAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
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
