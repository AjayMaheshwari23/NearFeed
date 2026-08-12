package com.example.meshsocial.discovery

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

const val SUPPORTED_PROTOCOL_VERSION = 1

data class PeerCandidate(
    // Temporary transport identity (for BLE V1 this can be the scan result device address).
    val candidateId: String,
    // Becomes known after a HELLO handshake, or from a future compact identity hint.
    val knownPeerId: UUID? = null,
    val rssi: Int,
    val discoveredAt: Instant = Instant.now(),
    // Null until the peer announces its protocol version in HELLO.
    val protocolVersion: Int? = null,
)

interface PeerDiscovery {
    val discoveredPeers: Flow<PeerCandidate>
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
}
