package com.example.meshsocial.connection

import android.util.Log
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.topology.TopologyContext
import com.example.meshsocial.topology.TopologyPolicy
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Orchestration for the LLD.
 *
 * It owns POLICY/coordination, not BLE scanning and not sync semantics.
 *
 * Selection flow per reconcile cycle:
 *   1. Ask the policy for the top-K candidates (remove-rules + P0/P1/P2 sort).
 *   2. Mark the candidate as connecting, then connect via the [PeerConnector].
 *   3. On success the connection is tracked as active and handed to [onConnected].
 *   4. On failure the peer enters a short cooldown so it is not retried immediately.
 */
class ConnectionCoordinator(
    private val topologyPolicy: TopologyPolicy,
    private val connector: PeerConnector,
    private val maxActiveSyncs: Int = 1,
    private val failureCooldown: Duration = Duration.ofSeconds(30),
) {
    private val active = linkedMapOf<String, PeerConnection>()
    private val connecting = linkedSetOf<String>()
    private val cooldownUntil = mutableMapOf<UUID, Instant>()

    /** Invoked after a connection becomes active. */
    var onConnected: (suspend (PeerConnection) -> Unit)? = null

    val activeCount: Int get() = active.size

    /** Returns the remote peer UUIDs of all active connections. */
    fun activeConnections(): List<UUID> =
        active.values.mapNotNull { it.remotePeerId }

    suspend fun reconcileConnections(
        candidates: List<PeerCandidate>,
        baseContext: TopologyContext,
    ) {
        val slots = (maxActiveSyncs - active.size - connecting.size).coerceAtLeast(0)
        if (slots == 0) return

        val context = baseContext.copy(
            activeCandidateIds = active.keys,
            connectingCandidateIds = connecting,
            failureCooldownUntil = cooldownUntil,
        )
        val selected = topologyPolicy.selectPeers(candidates, context, slots)
        Log.i(TAG, "Topology selected ${selected.size} of ${candidates.size} candidates")

        for (candidate in selected) {
            // Collision rule: if the connector designates this device as the passive
            // (server) side for a peer pair, do not initiate a client connection.
            val bleConnector = connector as? com.example.meshsocial.ble.BleGattConnector
            if (bleConnector != null && !bleConnector.shouldInitiate(candidate)) {
                Log.i(TAG, "Passive for ${candidate.candidateId}; peer will initiate")
                continue
            }
            connecting += candidate.candidateId
            try {
                val connection = connector.connect(candidate)
                active[candidate.candidateId] = connection
                Log.i(TAG, "Connected to ${candidate.candidateId}")
                candidate.knownPeerId?.let(cooldownUntil::remove)
                onConnected?.invoke(connection)
            } catch (t: Throwable) {
                Log.w(TAG, "Connect failed for ${candidate.candidateId}: ${t.message}")
                candidate.knownPeerId?.let {
                    cooldownUntil[it] = Instant.now().plus(failureCooldown)
                }
            } finally {
                connecting -= candidate.candidateId
            }
        }
    }

    suspend fun onDisconnected(candidateId: String) {
        active.remove(candidateId)?.close()
    }

    companion object {
        private const val TAG = "ConnectionCoordinator"
    }
}
