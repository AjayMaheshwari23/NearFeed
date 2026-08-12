package com.example.meshsocial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.example.meshsocial.connection.PeerConnection
import com.example.meshsocial.connection.PeerConnector
import com.example.meshsocial.discovery.PeerCandidate
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
        val adapter = manager.adapter ?: throw IllegalStateException("bluetooth unavailable")
        val device = adapter.getRemoteDevice(peer.candidateId)
        val connection = BlePeerConnection(appContext, device, local)
        val ready = connection.connect()
        if (!ready) {
            throw IllegalStateException("connect timeout for ${peer.candidateId}")
        }
        Log.i(TAG, "connection ready to ${peer.candidateId}")
        return connection
    }

    companion object {
        private const val TAG = "BleGattConnector"
    }
}
