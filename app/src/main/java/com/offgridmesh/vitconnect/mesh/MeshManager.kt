package com.offgridmesh.vitconnect.mesh

import android.content.Context
import android.util.Log
import com.offgridmesh.vitconnect.R
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "MeshManager"

/**
 * Owns every Nearby Connections call for the app: advertising, discovery, connection
 * lifecycle, and payload send/receive. Phase 1 wires this up for direct messaging between
 * two connected devices; Phase 2 adds flood-relay + store-and-forward on top without
 * changing this class's public surface.
 *
 * One instance lives for the app's lifetime (see [com.offgridmesh.vitconnect.OffGridApp]).
 */
class MeshManager(private val appContext: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    val deviceId: String = DeviceIdentity.getOrCreateDeviceId(appContext)

    var displayName: String
        get() = DeviceIdentity.getDisplayName(appContext)
        set(value) {
            DeviceIdentity.setDisplayName(appContext, value)
            // Re-advertise under the new name so newly discovered peers see it immediately.
            if (_status.value == MeshStatus.RUNNING) {
                connectionsClient.stopAdvertising()
                startAdvertising()
            }
        }

    private val serviceId: String
        get() = appContext.getString(R.string.nearby_service_id)

    // endpointId -> PeerInfo, for currently *connected* peers only.
    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(MeshStatus.IDLE)
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    // endpointIds we've already asked to connect to (or are connected to), so onEndpointFound
    // doesn't spam requestConnection while a handshake is already in flight.
    private val pendingOrConnected = mutableSetOf<String>()

    // endpointId -> raw advertised name ("displayName|deviceId"), captured at
    // onConnectionInitiated time so we can build the PeerInfo once onConnectionResult succeeds.
    private val pendingEndpointNames = mutableMapOf<String, String>()

    fun start() {
        if (_status.value == MeshStatus.RUNNING || _status.value == MeshStatus.STARTING) return
        _status.value = MeshStatus.STARTING
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        pendingOrConnected.clear()
        pendingEndpointNames.clear()
        _peers.value = emptyMap()
        _status.value = MeshStatus.IDLE
    }

    /** Sends [body] as a TEXT message to every currently connected peer. */
    fun sendText(body: String) {
        val message = MeshMessage(
            senderId = deviceId,
            senderName = displayName,
            targetId = null,
            body = body,
        )
        _messages.update { it + message }
        broadcastToPeers(message.toBytes())
    }

    private fun broadcastToPeers(bytes: ByteArray, excludeEndpointIds: Set<String> = emptySet()) {
        val targets = _peers.value.keys - excludeEndpointIds
        for (endpointId in targets) {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener { e -> Log.w(TAG, "sendPayload to $endpointId failed", e) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Advertising + discovery
    // ---------------------------------------------------------------------------------------

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val endpointName = "$displayName|$deviceId"
        connectionsClient.startAdvertising(
            endpointName,
            serviceId,
            connectionLifecycleCallback,
            options,
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started as $endpointName")
            _status.value = MeshStatus.RUNNING
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed to start", e)
            _status.value = MeshStatus.ERROR
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options,
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started")
            _status.value = MeshStatus.RUNNING
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed to start", e)
            _status.value = MeshStatus.ERROR
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (endpointId in pendingOrConnected) return
            pendingOrConnected += endpointId
            Log.d(TAG, "Endpoint found: $endpointId (${info.endpointName})")
            val localEndpointName = "$displayName|$deviceId"
            connectionsClient.requestConnection(
                localEndpointName,
                endpointId,
                connectionLifecycleCallback,
            ).addOnFailureListener { e ->
                Log.w(TAG, "requestConnection to $endpointId failed", e)
                pendingOrConnected -= endpointId
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            // Only a discovery-radio signal — actual disconnect cleanup happens in
            // onDisconnected(), since a lost broadcast doesn't always mean a live
            // connection dropped.
        }
    }

    // ---------------------------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------------------------

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with $endpointId (${info.endpointName})")
            pendingOrConnected += endpointId
            pendingEndpointNames[endpointId] = info.endpointName
            // Auto-accept on both sides — no manual confirmation step, so two phones
            // running the app connect the instant they're in range of each other.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val rawName = pendingEndpointNames.remove(endpointId) ?: "Unknown|$endpointId"
                val parts = rawName.split("|", limit = 2)
                val peer = PeerInfo(
                    endpointId = endpointId,
                    deviceId = parts.getOrElse(1) { endpointId },
                    displayName = parts.getOrElse(0) { "Unknown" },
                )
                _peers.update { it + (endpointId to peer) }
                Log.d(TAG, "Connected: ${peer.displayName} ($endpointId)")
            } else {
                Log.w(TAG, "Connection to $endpointId failed: ${result.status.statusCode}")
                pendingOrConnected -= endpointId
                pendingEndpointNames -= endpointId
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected: $endpointId")
            pendingOrConnected -= endpointId
            pendingEndpointNames -= endpointId
            _peers.update { it - endpointId }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Payloads
    // ---------------------------------------------------------------------------------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val message = MeshMessage.fromBytes(bytes) ?: return
            Log.d(TAG, "Received ${message.type} from ${message.senderName}: ${message.body}")
            _messages.update { it + message }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Per-byte progress — used for delivery status in Phase 4. No-op for now.
        }
    }
}
