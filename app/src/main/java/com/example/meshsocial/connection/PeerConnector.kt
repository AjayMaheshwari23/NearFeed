package com.example.meshsocial.connection

import com.example.meshsocial.discovery.PeerCandidate

interface PeerConnector {
    suspend fun connect(peer: PeerCandidate): PeerConnection
}
