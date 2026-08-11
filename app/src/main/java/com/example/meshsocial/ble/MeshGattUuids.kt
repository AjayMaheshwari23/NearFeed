package com.example.meshsocial.ble

import java.util.UUID

object MeshGattUuids {
    // Random app-specific UUIDs. Keep stable once devices need protocol compatibility.
    val SERVICE: UUID = UUID.fromString("9bc88654-0ccf-4bc2-9f65-46e84b9f2521")
    val RX: UUID = UUID.fromString("185d144f-d50b-4c6b-a399-55d3db33fd54")
    val TX: UUID = UUID.fromString("32851847-b987-4a19-a553-fb9fc0dac1f0")
}
