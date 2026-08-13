package com.example.meshsocial.discovery

/**
 * Pure in-memory tracker for BLE scan results.
 *
 * The radio can emit the same device multiple times per scan window, and a
 * device can re-advertise under a fresh transport address each cycle (the
 * emulator randomizes its MAC). Results are therefore keyed by the STABLE peer
 * UUID when known, falling back to the transport id, keeping only the latest
 * reading per peer. Snapshot is sorted by signal strength (strongest first).
 */
class PeerCandidateTracker(
    private val maxCandidates: Int = 50,
) {
    private val byKey = LinkedHashMap<String, PeerCandidate>()

    /** Adds a reading and returns the current snapshot sorted by RSSI. */
    fun onCandidate(candidate: PeerCandidate): List<PeerCandidate> {
        val key = candidate.knownPeerId?.toString() ?: candidate.candidateId
        byKey[key] = candidate
        if (byKey.size > maxCandidates) {
            byKey.remove(byKey.keys.first())
        }
        return snapshot()
    }

    fun snapshot(): List<PeerCandidate> =
        byKey.values.sortedByDescending { it.rssi }

    fun clear() {
        byKey.clear()
    }
}
