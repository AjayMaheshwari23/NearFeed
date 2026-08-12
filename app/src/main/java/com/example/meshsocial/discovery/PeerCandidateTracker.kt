package com.example.meshsocial.discovery

/**
 * Pure in-memory tracker for BLE scan results. The radio can emit the same
 * device multiple times per scan window, so results are deduplicated by
 * [PeerCandidate.candidateId] keeping the latest reading, and surfaced sorted
 * by signal strength (strongest first).
 */
class PeerCandidateTracker(
    private val maxCandidates: Int = 50,
) {
    private val byId = LinkedHashMap<String, PeerCandidate>()

    /** Adds a reading and returns the current snapshot sorted by RSSI. */
    fun onCandidate(candidate: PeerCandidate): List<PeerCandidate> {
        byId[candidate.candidateId] = candidate
        if (byId.size > maxCandidates) {
            byId.remove(byId.keys.first())
        }
        return snapshot()
    }

    fun snapshot(): List<PeerCandidate> =
        byId.values.sortedByDescending { it.rssi }

    fun clear() {
        byId.clear()
    }
}
