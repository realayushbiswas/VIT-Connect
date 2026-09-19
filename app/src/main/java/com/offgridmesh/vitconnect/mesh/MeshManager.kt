package com.offgridmesh.vitconnect.mesh

import android.content.Context
import android.util.Base64
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

// Bounds for the relay/seen-message cache: caps memory use and how long a store-and-forward
// message keeps getting opportunistically re-delivered to newly connected peers. The cache is
// persisted to disk (see ChatHistoryStore), so this window survives the sender's app restarting
// too — "store the message until the recipient's device is available" means more than just
// "while my app happens to stay open."
private const val MAX_RELAY_CACHE_SIZE = 1000
private const val RELAY_REPLAY_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours

/**
 * Owns every Nearby Connections call for the app: advertising, discovery, connection
 * lifecycle, and payload send/receive.
 *
 * Phase 2 relay model: every message carries a unique [MeshMessage.id], a hop-limit
 * ([MeshMessage.ttl]), and an optional [MeshMessage.targetId]. A device that receives a
 * message it hasn't seen before (checked against [relayCache]) displays it, then — unless the
 * message was addressed specifically to this device — decrements its TTL and rebroadcasts it
 * to every other connected peer except whichever peer it just arrived from. TTL reaching zero
 * stops the flood. The same [relayCache] doubles as a store-and-forward buffer: whenever a new
 * peer connects, any still-fresh cached message is replayed to it, so a message can still reach
 * a device that only joins the mesh after the message was first sent.
 *
 * One instance lives for the app's lifetime (see [com.offgridmesh.vitconnect.OffGridApp]).
 */
class MeshManager(private val appContext: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val crypto = CryptoManager(appContext)

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

    // deviceId -> displayName, for every device we know about mesh-wide — including ones
    // reachable only through relay hops, never directly connected. Populated from direct
    // connections, gossiped KEY_ANNOUNCE messages, and saved QR contacts. This is the roster
    // direct-chat targets are picked from.
    private val _knownDevices = MutableStateFlow<Map<String, String>>(emptyMap())
    val knownDevices: StateFlow<Map<String, String>> = _knownDevices.asStateFlow()

    // deviceId -> their EC public key, learned from KEY_ANNOUNCE gossip or a scanned QR
    // contact. Required to encrypt an outgoing direct message or decrypt an incoming one.
    private val knownPublicKeys = mutableMapOf<String, ByteArray>()

    // deviceIds added via in-person QR scan — a stronger trust level than mesh gossip, since
    // the key came directly from the person rather than being relayed. Shown as "verified" in
    // the directory. Loaded from ContactsStore at startup so it survives app restarts.
    private val _verifiedContactIds = MutableStateFlow<Set<String>>(emptySet())
    val verifiedContactIds: StateFlow<Set<String>> = _verifiedContactIds.asStateFlow()

    /** The JSON payload this device's "My QR Code" screen encodes. */
    val myQrPayload: String
        get() = QrContact(deviceId, displayName, Base64.encodeToString(crypto.publicKeyBytes, Base64.NO_WRAP)).toJson()

    /**
     * Adds (or updates) a QR-verified contact: persists it to disk, learns their public key
     * immediately, and lists them in the directory — all without needing them to be reachable
     * in the mesh right now. Returns false if [contact] is malformed or is our own code.
     */
    fun addContactFromQr(contact: QrContact): Boolean {
        if (contact.deviceId == deviceId) return false
        val keyBytes = try {
            Base64.decode(contact.publicKeyBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (keyBytes.isEmpty()) return false
        ContactsStore.save(appContext, contact)
        adoptContact(contact)
        return true
    }

    private fun adoptContact(contact: QrContact) {
        val keyBytes = try {
            Base64.decode(contact.publicKeyBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return
        }
        knownPublicKeys[contact.deviceId] = keyBytes
        _knownDevices.update { it + (contact.deviceId to contact.name) }
        _verifiedContactIds.update { it + contact.deviceId }
    }

    // Direct (1:1) conversations, keyed by the OTHER participant's deviceId. Values are always
    // plaintext — encryption/decryption happens at the send/receive boundary, never touching
    // this state. Entirely separate from [messages] (the mesh-wide broadcast/group feed).
    private val _directConversations = MutableStateFlow<Map<String, List<MeshMessage>>>(emptyMap())
    val directConversations: StateFlow<Map<String, List<MeshMessage>>> = _directConversations.asStateFlow()

    /** Null if we haven't learned [theirDeviceId]'s public key yet (they may be out of the mesh). */
    fun safetyNumberFor(theirDeviceId: String): String? =
        knownPublicKeys[theirDeviceId]?.let { crypto.safetyNumber(deviceId, theirDeviceId, it) }

    // endpointIds we've already asked to connect to (or are connected to), so onEndpointFound
    // doesn't spam requestConnection while a handshake is already in flight.
    private val pendingOrConnected = mutableSetOf<String>()

    // endpointId -> raw advertised name ("displayName|deviceId"), captured at
    // onConnectionInitiated time so we can build the PeerInfo once onConnectionResult succeeds.
    private val pendingEndpointNames = mutableMapOf<String, String>()

    // messageId -> message, insertion-ordered. Doubles as (a) the seen-message dedupe cache
    // that stops flood-relay loops, and (b) the store-and-forward buffer replayed to peers
    // that connect after a message was already in flight. Bounded by MAX_RELAY_CACHE_SIZE.
    private val relayCache = LinkedHashMap<String, MeshMessage>()

    private fun rememberForRelay(message: MeshMessage, persist: Boolean = true) {
        relayCache[message.id] = message
        if (relayCache.size > MAX_RELAY_CACHE_SIZE) {
            relayCache.remove(relayCache.keys.first())
        }
        if (persist) ChatHistoryStore.saveRelayCache(appContext, relayCache.values)
    }

    private fun persistGroupMessages() = ChatHistoryStore.saveGroupMessages(appContext, _messages.value)

    private fun persistDirectConversations() =
        ChatHistoryStore.saveDirectConversations(appContext, _directConversations.value)

    init {
        // Restore everything persisted from a previous session — chat history and any
        // still-undelivered store-and-forward messages survive an app restart this way.
        // `persist = false` avoids immediately re-writing back to disk the data we just read.
        for (message in ChatHistoryStore.loadRelayCache(appContext)) {
            rememberForRelay(message, persist = false)
        }
        _messages.value = ChatHistoryStore.loadGroupMessages(appContext)
        _directConversations.value = ChatHistoryStore.loadDirectConversations(appContext)
        for (contact in ContactsStore.loadAll(appContext)) {
            adoptContact(contact)
        }
    }

    fun start() {
        if (_status.value == MeshStatus.RUNNING || _status.value == MeshStatus.STARTING) return
        _status.value = MeshStatus.STARTING
        ensureKeyAnnounced()
        startAdvertising()
        startDiscovery()
    }

    /**
     * Seeds our own public key into [relayCache] as a KEY_ANNOUNCE message. It doesn't need to
     * be actively (re-)broadcast on a timer — [replayPendingMessagesTo] resends anything still
     * in the cache to every peer as it connects, which is exactly the distribution KEY_ANNOUNCE
     * needs. Idempotent: calling it again is harmless (same message id, cache just overwrites).
     */
    private fun ensureKeyAnnounced() {
        val announce = MeshMessage(
            // Stable, not random: our public key never changes across restarts, so repeated
            // start()/stop() cycles should overwrite the same cache slot rather than pile up
            // duplicate announcements under different ids.
            id = "key-announce-$deviceId",
            type = MeshMessageType.KEY_ANNOUNCE,
            senderId = deviceId,
            senderName = displayName,
            targetId = null,
            body = Base64.encodeToString(crypto.publicKeyBytes, Base64.NO_WRAP),
        )
        rememberForRelay(announce)
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        pendingOrConnected.clear()
        pendingEndpointNames.clear()
        relayCache.clear()
        _peers.value = emptyMap()
        _status.value = MeshStatus.IDLE
    }

    /** Sends [body] as a TEXT message, flooded to every currently connected peer. */
    fun sendText(body: String) {
        val message = MeshMessage(
            senderId = deviceId,
            senderName = displayName,
            targetId = null,
            body = body,
        )
        rememberForRelay(message)
        _messages.update { it + message }
        persistGroupMessages()
        broadcastToPeers(message.toBytes())
    }

    /**
     * Sends [body] as an end-to-end encrypted direct message to [targetDeviceId] — readable
     * only by that specific device, even though it may travel through several relay hops to
     * get there. Returns false (and sends nothing) if we haven't learned that device's public
     * key yet, which happens if it's never been reachable anywhere in the mesh so far.
     */
    fun sendDirectMessage(targetDeviceId: String, body: String): Boolean {
        val recipientKey = knownPublicKeys[targetDeviceId] ?: run {
            Log.w(TAG, "No known public key for $targetDeviceId — cannot send yet")
            return false
        }
        val ciphertext = crypto.encrypt(body, recipientKey)
        val message = MeshMessage(
            senderId = deviceId,
            senderName = displayName,
            targetId = targetDeviceId,
            body = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            encrypted = true,
        )
        rememberForRelay(message)
        // Store the plaintext (not the ciphertext-bearing message object) for our own display —
        // we obviously don't need to decrypt what we just typed ourselves.
        val localCopy = message.copy(body = body)
        _directConversations.update { conv ->
            conv + (targetDeviceId to (conv[targetDeviceId].orEmpty() + localCopy))
        }
        persistDirectConversations()
        broadcastToPeers(message.toBytes())
        return true
    }

    private fun connectedDeviceIds(): Set<String> = _peers.value.values.map { it.deviceId }.toSet()

    private fun broadcastToPeers(bytes: ByteArray, excludeEndpointIds: Set<String> = emptySet()) {
        val targets = _peers.value.keys - excludeEndpointIds
        for (endpointId in targets) {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener { e -> Log.w(TAG, "sendPayload to $endpointId failed", e) }
        }
    }

    /**
     * Store-and-forward: a freshly connected peer may have missed messages that were already
     * flooding through the mesh (or addressed to it) before it joined. Replay anything still
     * within [RELAY_REPLAY_WINDOW_MS] and with hops left — duplicates are harmless, the
     * receiving device's own relayCache silently drops anything it's already seen.
     */
    private fun replayPendingMessagesTo(endpointId: String) {
        val cutoff = System.currentTimeMillis() - RELAY_REPLAY_WINDOW_MS
        val toReplay = relayCache.values.filter { msg ->
            // KEY_ANNOUNCE is identity material, not a perishable chat message — always worth
            // replaying regardless of age, so encryption keeps working for long-running sessions.
            msg.ttl > 0 && (msg.type == MeshMessageType.KEY_ANNOUNCE || msg.timestamp >= cutoff)
        }
        for (message in toReplay) {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.toBytes()))
                .addOnFailureListener { e -> Log.w(TAG, "replay to $endpointId failed", e) }
        }
        if (toReplay.isNotEmpty()) {
            Log.d(TAG, "Replayed ${toReplay.size} store-and-forward message(s) to $endpointId")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Advertising + discovery
    // ---------------------------------------------------------------------------------------

    private fun startAdvertising() {
        // setLowPower(false): trades battery for shorter BLE advertise/scan intervals, which
        // materially cuts the time it takes two phones to discover each other. Default Nearby
        // behavior otherwise favors battery life over discovery speed.
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .setLowPower(false)
            .build()
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
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .setLowPower(false)
            .build()
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
            // The same physical device can surface as a second, different endpointId — e.g. if
            // it's rediscovered on another medium before the first connection fully settles.
            // Recognize it by the deviceId embedded in its advertised name and skip requesting
            // a redundant second connection, which is what was causing one person to show up
            // twice in the peer list.
            val discoveredDeviceId = info.endpointName.split("|", limit = 2).getOrNull(1)
            if (discoveredDeviceId != null && discoveredDeviceId in connectedDeviceIds()) {
                Log.d(TAG, "Already connected to device $discoveredDeviceId via another endpoint — skipping $endpointId")
                return
            }
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
                // Defensive de-dup: the onEndpointFound guard prevents most duplicate
                // connections, but two devices discovering each other on two mediums at nearly
                // the same instant can still race past it. If we already have a *different*
                // live endpoint for this same deviceId, collapse it down to one.
                _peers.value.values.firstOrNull { it.deviceId == peer.deviceId && it.endpointId != endpointId }
                    ?.let { stale ->
                        Log.d(TAG, "Dropping duplicate connection to ${stale.deviceId} (${stale.endpointId})")
                        connectionsClient.disconnectFromEndpoint(stale.endpointId)
                        pendingOrConnected -= stale.endpointId
                        _peers.update { it - stale.endpointId }
                    }
                _peers.update { it + (endpointId to peer) }
                _knownDevices.update { it + (peer.deviceId to peer.displayName) }
                Log.d(TAG, "Connected: ${peer.displayName} ($endpointId)")
                replayPendingMessagesTo(endpointId)
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

            if (relayCache.containsKey(message.id)) {
                // Already processed this exact message via this or another path in the mesh —
                // this is the loop-prevention check. Drop it silently.
                return
            }
            // Cache the decremented (post-hop) copy right away — even if we end up not
            // relaying it further below, this is what must be stored, so a later
            // store-and-forward replay to a new peer never resurrects this message with a
            // stale, larger TTL than it actually has hops left for.
            val forwarded = message.forwarded()
            rememberForRelay(forwarded)
            Log.d(
                TAG,
                "Received ${message.type} from ${message.senderName} " +
                    "(hop ${message.hopCount}, ttl ${message.ttl})",
            )

            var stopRelaying = false
            when {
                message.type == MeshMessageType.KEY_ANNOUNCE -> {
                    handleKeyAnnounce(message)
                    // Never shown in any chat UI — falls through to the relay step below so
                    // the key keeps propagating across the mesh.
                }
                message.targetId == null -> {
                    // Broadcast / group chat — plaintext, shown to everyone, relayed onward.
                    _messages.update { it + message }
                    persistGroupMessages()
                }
                message.targetId == deviceId -> {
                    // Addressed specifically to me — decrypt and deliver, fully done relaying.
                    deliverDirectMessage(message)
                    stopRelaying = true
                }
                else -> {
                    // Addressed to someone else — pure relay. We have no key that could
                    // decrypt it even if we wanted to; it stays opaque ciphertext to us and is
                    // never added to any visible chat state.
                }
            }

            if (stopRelaying || forwarded.ttl <= 0) return
            broadcastToPeers(forwarded.toBytes(), excludeEndpointIds = setOf(endpointId))
        }

        private fun handleKeyAnnounce(message: MeshMessage) {
            val pubKeyBytes = try {
                Base64.decode(message.body, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Malformed KEY_ANNOUNCE from ${message.senderId}", e)
                return
            }
            knownPublicKeys[message.senderId] = pubKeyBytes
            _knownDevices.update { it + (message.senderId to message.senderName) }
            Log.d(TAG, "Learned public key for ${message.senderName} (${message.senderId})")
        }

        private fun deliverDirectMessage(message: MeshMessage) {
            val senderKey = knownPublicKeys[message.senderId]
            val plaintext = try {
                if (senderKey == null) throw IllegalStateException("no known key for ${message.senderId}")
                crypto.decrypt(Base64.decode(message.body, Base64.NO_WRAP), senderKey)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt direct message from ${message.senderId}", e)
                "[unable to decrypt message]"
            }
            val displayMessage = message.copy(body = plaintext)
            _directConversations.update { conv ->
                conv + (message.senderId to (conv[message.senderId].orEmpty() + displayMessage))
            }
            persistDirectConversations()
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Per-byte progress — used for delivery status in Phase 4. No-op for now.
        }
    }
}
