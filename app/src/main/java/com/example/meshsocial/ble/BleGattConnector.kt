package com.example.meshsocial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.connection.PeerConnector
import com.example.meshsocial.discovery.PeerCandidate
import timber.log.Timber
import java.util.UUID

/**
 * Connects to a discovered BLE candidate via GATT.
 *
 * The remote mesh peer UUID is known at scan time from the manufacturer-data
 * advertisement, so the HELLO handshake binds the transport identity and peer
 * UUID, and collision handling can use a deterministic UUID comparison.
 *
 * Collision rule: when both peers discover each other, only one side initiates
 * the GATT connection. The side whose peer UUID is strictly greater initiates;
 * the other side waits passively (its GATT server accepts the incoming
 * connection). This avoids duplicate simultaneous links.
 */
class BleGattConnector(
    context: Context,
    private val localPeerId: suspend () -> UUID?,
    private val deviceResolver: (String) -> BluetoothDevice? = { null },
) : PeerConnector {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val appContext = context.applicationContext

    /**
     * True when this device is the designated initiator for the given candidate.
     * Deterministic tie-break so both sides reach the same answer.
     */
    suspend fun shouldInitiate(peer: PeerCandidate): Boolean {
        val local = localPeerId() ?: return true
        val remote = peer.knownPeerId ?: return true
        return local > remote
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: PeerCandidate): PeerConnection {
        if (!shouldInitiate(peer)) {
            throw IllegalStateException("peer ${peer.candidateId} should initiate (collision rule)")
        }
        val local = localPeerId() ?: throw IllegalStateException("no local profile")
        // Prefer the real BluetoothDevice from the scan result: it carries the
        // correct address type (modern phones advertise with RANDOM privacy
        // addresses). getRemoteDevice(mac) forces PUBLIC type and connectGatt
        // fails silently on real hardware.
        val device = deviceResolver(peer.candidateId)
            ?: manager.adapter?.getRemoteDevice(peer.candidateId)
            ?: throw IllegalStateException("no remote device for ${peer.candidateId}")
        val connection = BlePeerConnection(appContext, device, local)
        // Bind the peer UUID immediately from the advertisement so dedup/cooldown
        // work before the HELLO handshake completes.
        peer.knownPeerId?.let { connection.bindRemotePeerId(it) }
        val ready = connection.connect()
        if (!ready) {
            throw IllegalStateException("connect timeout for ${peer.candidateId}")
        }
        Timber.i("connection ready to ${peer.candidateId}")
        return connection
    }

}
