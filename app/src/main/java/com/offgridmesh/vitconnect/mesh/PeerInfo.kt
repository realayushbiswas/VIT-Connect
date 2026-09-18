package com.offgridmesh.vitconnect.mesh

/** A currently-connected peer, keyed in [MeshManager] by Nearby's ephemeral endpointId. */
data class PeerInfo(
    val endpointId: String,
    val deviceId: String,
    val displayName: String,
    val connectedAtMillis: Long = System.currentTimeMillis(),
)

enum class MeshStatus {
    IDLE,
    STARTING,
    RUNNING,
    ERROR,
}
