package com.example.meshsocial.connection


import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.topology.TopologyContext
import com.example.meshsocial.topology.TopologyPolicy
import java.time.Duration
import java.time.Instant
import java.util.UUID
import timber.log.Timber

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
 *
 * Each active connection is watched: when its incoming flow closes (link dropped),
 * the connection is pruned so its sync slot is freed for future cycles.
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

    /** Dev/diagnostic events (passive/connect/fail/link-closed) for the UI log. */
    var onEvent: (String) -> Unit = {}

    val activeCount: Int get() = active.size

    /** Returns the remote peer UUIDs of all active connections. */
    fun activeConnections(): List<UUID> =
        active.values.mapNotNull { it.remotePeerId }

    suspend fun reconcileConnections(
        candidates: List<PeerCandidate>,
        baseContext: TopologyContext,
    ) {
        // Emulators (and real devices) re-advertise under a fresh MAC each cycle,
        // so the same physical peer can look like a new candidate. Deduplicate by
        // the stable peer UUID before counting slots: if we already hold a live
        // link to that peer, do not open another one.
        val activePeerIds = active.values.mapNotNull { it.remotePeerId }.toSet()
        val candidatesAfterDedup = candidates.filter { candidate ->
            candidate.knownPeerId == null || candidate.knownPeerId !in activePeerIds
        }

        val slots = (maxActiveSyncs - active.size - connecting.size).coerceAtLeast(0)
        if (slots == 0) {
            onEvent("coordinator: no free slots (active=${active.size})")
            return
        }

        val context = baseContext.copy(
            activeCandidateIds = active.keys,
            connectingCandidateIds = connecting,
            failureCooldownUntil = cooldownUntil,
        )
        val selected = topologyPolicy.selectPeers(candidatesAfterDedup, context, slots)
        Timber.i("Topology selected ${selected.size} of ${candidates.size} candidates (dedup'd ${candidates.size - candidatesAfterDedup.size})")

        for (candidate in selected) {
            // Collision rule: if the connector designates this device as the passive
            // (server) side for a peer pair, do not initiate a client connection.
            val bleConnector = connector as? com.example.meshsocial.ble.BleGattConnector
            if (bleConnector != null && !bleConnector.shouldInitiate(candidate)) {
                Timber.i("Passive for ${candidate.candidateId}; peer will initiate")
                onEvent("coordinator: PASSIVE for peer ${candidate.knownPeerId?.toString()?.take(8)}; waiting for peer to initiate")
                continue
            }
            connecting += candidate.candidateId
            try {
                val connection = connector.connect(candidate)
                active[candidate.candidateId] = connection
                Timber.i("Connected to ${candidate.candidateId}")
                onEvent("coordinator: CONNECTED to peer ${candidate.knownPeerId?.toString()?.take(8)}")
                candidate.knownPeerId?.let(cooldownUntil::remove)
                onConnected?.invoke(connection)
            } catch (t: Throwable) {
                Timber.w("Connect failed for ${candidate.candidateId}: ${t.message}")
                onEvent("coordinator: CONNECT FAILED for ${candidate.knownPeerId?.toString()?.take(8)}: ${t.message}")
                candidate.knownPeerId?.let {
                    cooldownUntil[it] = Instant.now().plus(failureCooldown)
                }
            } finally {
                connecting -= candidate.candidateId
            }
        }
    }

    /**
     * Called by the session owner when the connection's message flow terminates
     * (link dropped / channel closed). Prunes the active link so its sync slot
     * is freed for future cycles.
     */
    suspend fun onLinkClosed(connection: PeerConnection) {
        val candidateId = active.entries.firstOrNull { it.value === connection }?.key ?: return
        active.remove(candidateId)?.close()
        Timber.i("Link closed for $candidateId; freed sync slot (active=${active.size})")
        onEvent("coordinator: LINK CLOSED, freed slot (active=${active.size})")
    }

    suspend fun onDisconnected(candidateId: String) {
        active.remove(candidateId)?.close()
    }

}
