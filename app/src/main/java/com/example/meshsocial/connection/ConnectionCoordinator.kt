package com.example.meshsocial.connection

import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.topology.TopologyContext
import com.example.meshsocial.topology.TopologyPolicy

/**
 * Orchestration skeleton for the LLD.
 *
 * It owns POLICY/coordination, not BLE scanning and not sync semantics.
 * Wire this after the BLE GATT PeerConnector exists.
 */
class ConnectionCoordinator(
    private val topologyPolicy: TopologyPolicy,
    private val connector: PeerConnector,
    private val maxActiveSyncs: Int = 1,
) {
    private val active = linkedMapOf<String, PeerConnection>()
    private val connecting = linkedSetOf<String>()

    suspend fun reconcileConnections(
        candidates: List<PeerCandidate>,
        baseContext: TopologyContext,
    ) {
        val slots = (maxActiveSyncs - active.size - connecting.size).coerceAtLeast(0)
        if (slots == 0) return

        val context = baseContext.copy(
            activeCandidateIds = active.keys,
            connectingCandidateIds = connecting,
        )
        val selected = topologyPolicy.selectPeers(candidates, context, slots)
        for (candidate in selected) {
            connecting += candidate.candidateId
            try {
                val connection = connector.connect(candidate)
                active[candidate.candidateId] = connection
                // TODO Phase 2: start SyncSession(connection), then close/cooldown on completion.
            } finally {
                connecting -= candidate.candidateId
            }
        }
    }

    suspend fun onDisconnected(candidateId: String) {
        active.remove(candidateId)?.close()
    }
}
