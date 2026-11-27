package com.offgridmesh.vitconnect.mesh

import android.content.Context
import android.util.Base64
import android.util.Log
import com.offgridmesh.vitconnect.R
import com.offgridmesh.vitconnect.mesh.gateway.GatewayAnnounce
import com.offgridmesh.vitconnect.mesh.gateway.GatewayInfo
import com.offgridmesh.vitconnect.mesh.gateway.GatewayLimits
import com.offgridmesh.vitconnect.mesh.gateway.GatewayRegistry
import com.offgridmesh.vitconnect.mesh.gateway.GatewaySettings
import com.offgridmesh.vitconnect.mesh.gateway.InternetMonitor
import com.offgridmesh.vitconnect.mesh.gateway.QueryItem
import com.offgridmesh.vitconnect.mesh.gateway.QueryKind
import com.offgridmesh.vitconnect.mesh.gateway.QueryRequest
import com.offgridmesh.vitconnect.mesh.gateway.QueryResponse
import com.offgridmesh.vitconnect.mesh.gateway.QuerySandbox
import com.offgridmesh.vitconnect.mesh.gateway.QueryStatus
import com.offgridmesh.vitconnect.mesh.gateway.RateLimiter
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "MeshManager"

// Bounds for the relay/seen-message cache: caps memory use and how long a store-and-forward
// message keeps getting opportunistically re-delivered to newly connected peers. The cache is
// persisted to disk (see ChatHistoryStore), so this window survives the sender's app restarting
// too — "store the message until the recipient's device is available" means more than just
// "while my app happens to stay open."
private const val MAX_RELAY_CACHE_SIZE = 1000
private const val RELAY_REPLAY_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours
private const val DISCOVERY_RESTART_INTERVAL_MS = 20 * 1000L // 20 seconds

// Internet-gateway tuning. A gateway re-announces every GATEWAY_HEARTBEAT_MS; requesters drop one
// that has been silent for GatewayRegistry.DEFAULT_TTL_MS. Gateway traffic is perishable, so it
// is only replayed to newly connected peers for a short window (unlike chat's 24 hours).
private const val GATEWAY_TICK_MS = 10 * 1000L
private const val GATEWAY_HEARTBEAT_MS = 45 * 1000L
private const val MAX_EPHEMERAL_CACHE_SIZE = 200
private const val ANNOUNCE_REPLAY_WINDOW_MS = 60 * 1000L
private const val QUERY_REPLAY_WINDOW_MS = 10 * 60 * 1000L
private const val QUERY_DEADLINE_MS = 3 * 60 * 1000L // give up on a query after 3 minutes
private const val QUERY_ATTEMPT_TIMEOUT_MS = 30 * 1000L // per gateway, before trying the next-nearest
private const val QUERY_POLL_MS = 3 * 1000L // how often a waiting query re-checks for a gateway
private const val MAX_QUERY_ATTEMPTS = 3
private const val MAX_QUERIES_SHOWN = 30

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
                // Refresh + rebroadcast the cached KEY_ANNOUNCE too — otherwise it keeps
                // gossiping the *old* name to the rest of the mesh indefinitely (and, since
                // it's persisted to disk, even across app restarts), which is what was
                // showing up as stale names reappearing seemingly at random.
                ensureKeyAnnounced()
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

    // deviceId -> timestamp of the update currently reflected in _knownDevices. A cached
    // KEY_ANNOUNCE can get replayed well after a device has since sent a fresher one (or
    // renamed and rebroadcast) — without this, whichever update happens to arrive *last* wins,
    // which is what caused old names to reappear seemingly at random. Every write site below
    // goes through updateKnownDevice() so a stale update can never clobber a newer one.
    private val knownDeviceTimestamps = mutableMapOf<String, Long>()

    private fun updateKnownDevice(id: String, name: String, timestamp: Long) {
        val existing = knownDeviceTimestamps[id]
        if (existing != null && existing > timestamp) return
        knownDeviceTimestamps[id] = timestamp
        _knownDevices.update { it + (id to name) }
    }

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
        // A QR scan is in-person and "now" — it should always win over older gossip.
        updateKnownDevice(contact.deviceId, contact.name, System.currentTimeMillis())
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

    // ---- Internet gateway state (see the "Internet gateway" section below) ----

    /** The owner's opt-in switch and API keys for serving the mesh. Off by default. */
    val gatewaySettings = GatewaySettings(appContext)
    private val internet = InternetMonitor(appContext)

    /** True while this device itself has working internet. */
    val hasInternet: StateFlow<Boolean> = internet.online

    private val sandbox = QuerySandbox(gatewaySettings)
    private val gatewayRegistry = GatewayRegistry()
    private val requestLimiter =
        RateLimiter(GatewayLimits.RATE_LIMIT_REQUESTS, GatewayLimits.RATE_LIMIT_WINDOW_MS)

    private val _gateways = MutableStateFlow<List<GatewayInfo>>(emptyList())

    /** Gateways currently reachable through the mesh, nearest first. */
    val gateways: StateFlow<List<GatewayInfo>> = _gateways.asStateFlow()

    private val _servingAsGateway = MutableStateFlow(false)

    /** True while this device is online, opted in, has a key, and is announcing itself as a gateway. */
    val servingAsGateway: StateFlow<Boolean> = _servingAsGateway.asStateFlow()

    private val _queries = MutableStateFlow<List<QueryItem>>(emptyList())

    /** Search / AI queries made on this device, newest first. In memory only. */
    val queries: StateFlow<List<QueryItem>> = _queries.asStateFlow()

    // messageId -> message for gateway traffic (announces, requests, responses). Same dedupe +
    // short store-and-forward job as relayCache, but in memory only: these are perishable and
    // can carry multi-KB answers, so they must not crowd out chat history or be written to disk.
    private val ephemeralCache = LinkedHashMap<String, MeshMessage>()

    private class PendingQuery(val gatewayId: String, val reply: CompletableDeferred<QueryResponse>)

    private val pendingQueries = mutableMapOf<String, PendingQuery>()
    private var activeExecutions = 0
    private var gatewayLoopJob: Job? = null
    private var lastAnnounceAt = 0L
    private var wasServing = false

    private fun rememberEphemeral(message: MeshMessage) {
        ephemeralCache[message.id] = message
        if (ephemeralCache.size > MAX_EPHEMERAL_CACHE_SIZE) {
            ephemeralCache.remove(ephemeralCache.keys.first())
        }
    }

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
        startDiscoveryWatchdog()
        startGatewayLoop()
    }

    // Main-thread scope shared by the discovery watchdog and all gateway work, so the mutable
    // state below is only ever touched from the thread Nearby's callbacks already run on.
    // Blocking network calls hop to Dispatchers.IO and come straight back (see runQuery /
    // handleQueryRequest).
    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var discoveryWatchdogJob: Job? = null

    /**
     * Several OEM Android BLE stacks (Samsung and Xiaomi in particular are widely reported)
     * silently go stale after running for a while — they stop surfacing new advertisements
     * until the scan is restarted, with no error callback to tell us it happened. This is the
     * "connection is spotty" symptom: nothing's actually wrong, the scanner just needs a kick.
     * Periodically cycling discovery (not advertising — that could interrupt an in-progress
     * handshake) is a cheap, safe mitigation and doesn't affect already-connected endpoints.
     */
    private fun startDiscoveryWatchdog() {
        discoveryWatchdogJob?.cancel()
        discoveryWatchdogJob = meshScope.launch {
            while (isActive) {
                delay(DISCOVERY_RESTART_INTERVAL_MS)
                if (_status.value == MeshStatus.RUNNING || _status.value == MeshStatus.STARTING) {
                    Log.d(TAG, "Restarting discovery (periodic BLE scanner refresh)")
                    connectionsClient.stopDiscovery()
                    // stopDiscovery() doesn't return a Task, so there's no way to await its
                    // teardown — restarting immediately after can race with it and trigger a
                    // spurious failure callback. A short delay gives the BLE stack time to
                    // actually stop first.
                    delay(300)
                    startDiscovery()
                }
            }
        }
    }

    /**
     * (Re-)seeds our own public key + current name into [relayCache] as a KEY_ANNOUNCE message,
     * and immediately broadcasts it to whoever's already connected — new peers still pick it up
     * later via [replayPendingMessagesTo]. Safe to call anytime our name changes, not just at
     * startup: same stable id, so it overwrites the previous cache entry rather than piling up
     * duplicates, and re-broadcasting means already-connected peers get the update too instead
     * of being stuck with whatever name we had when they first connected.
     */
    private fun ensureKeyAnnounced() {
        val announce = MeshMessage(
            // Stable, not random: identifies "my current identity announcement" as one slot —
            // a rename overwrites it rather than adding a second, stale copy alongside it.
            id = "key-announce-$deviceId",
            type = MeshMessageType.KEY_ANNOUNCE,
            senderId = deviceId,
            senderName = displayName,
            targetId = null,
            body = Base64.encodeToString(crypto.publicKeyBytes, Base64.NO_WRAP),
        )
        rememberForRelay(announce)
        broadcastToPeers(announce.toBytes())
    }

    fun stop() {
        discoveryWatchdogJob?.cancel()
        gatewayLoopJob?.cancel()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        pendingOrConnected.clear()
        pendingEndpointNames.clear()
        relayCache.clear()
        ephemeralCache.clear()
        gatewayRegistry.clear()
        _gateways.value = emptyList()
        _servingAsGateway.value = false
        wasServing = false
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

        // Gateway traffic gets the same treatment on a much shorter leash: a request addressed to
        // a gateway that wasn't reachable yet can still get there, but a stale announce must not
        // keep a vanished gateway looking alive.
        val now = System.currentTimeMillis()
        for (message in ephemeralCache.values) {
            val window = if (message.type == MeshMessageType.GATEWAY_ANNOUNCE) ANNOUNCE_REPLAY_WINDOW_MS else QUERY_REPLAY_WINDOW_MS
            if (message.ttl > 0 && now - message.timestamp <= window) {
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.toBytes()))
                    .addOnFailureListener { e -> Log.w(TAG, "gateway replay to $endpointId failed", e) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internet gateway: search / AI for devices with no connection of their own
    // ---------------------------------------------------------------------------------------
    //
    // A device with working internet, whose owner has opted in and supplied API keys, announces
    // itself as a gateway. A device without internet picks the nearest gateway (fewest hops),
    // sends it an encrypted QUERY_REQUEST through the normal relay mesh, and the gateway runs the
    // query inside [QuerySandbox] and returns an encrypted QUERY_RESPONSE the same way. If the
    // gateway doesn't answer in time the next-nearest is tried. Relays in between only ever see
    // ciphertext; only the gateway sees the query text.

    private fun gatewayKindsOffered(): Set<QueryKind> =
        if (gatewaySettings.shareEnabled.value && internet.online.value) gatewaySettings.capabilities() else emptySet()

    /** Heartbeat: expire stale gateways, and (re)announce ourselves while we're one. */
    private fun startGatewayLoop() {
        gatewayLoopJob?.cancel()
        gatewayLoopJob = meshScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                _gateways.value = gatewayRegistry.active(now)

                val kinds = gatewayKindsOffered()
                val serving = kinds.isNotEmpty()
                _servingAsGateway.value = serving
                when {
                    serving && (!wasServing || now - lastAnnounceAt >= GATEWAY_HEARTBEAT_MS) -> {
                        announceGateway(kinds)
                        lastAnnounceAt = now
                    }
                    // Just stopped (sharing switched off, or we lost internet): tell the mesh now
                    // instead of letting requesters discover it by timing out.
                    !serving && wasServing -> announceGateway(emptySet())
                }
                wasServing = serving
                delay(GATEWAY_TICK_MS)
            }
        }
    }

    private fun announceGateway(kinds: Set<QueryKind>) {
        sendGatewayMessage(
            MeshMessage(
                type = MeshMessageType.GATEWAY_ANNOUNCE,
                senderId = deviceId,
                senderName = displayName,
                targetId = null,
                body = GatewayAnnounce.encode(kinds),
            ),
        )
    }

    private fun sendGatewayMessage(message: MeshMessage) {
        rememberEphemeral(message)
        broadcastToPeers(message.toBytes())
    }

    /** Dedupe, handle if it's for us, and keep flooding it onward otherwise. */
    private fun handleGatewayTraffic(fromEndpointId: String, message: MeshMessage) {
        if (ephemeralCache.containsKey(message.id)) return
        val forwarded = message.forwarded()
        rememberEphemeral(forwarded)

        var addressedToMe = false
        when (message.type) {
            MeshMessageType.GATEWAY_ANNOUNCE -> handleGatewayAnnounce(message)
            MeshMessageType.QUERY_REQUEST -> if (message.targetId == deviceId) {
                addressedToMe = true
                handleQueryRequest(message)
            }
            MeshMessageType.QUERY_RESPONSE -> if (message.targetId == deviceId) {
                addressedToMe = true
                handleQueryResponse(message)
            }
            else -> return
        }
        if (addressedToMe || forwarded.ttl <= 0) return
        broadcastToPeers(forwarded.toBytes(), excludeEndpointIds = setOf(fromEndpointId))
    }

    private fun handleGatewayAnnounce(message: MeshMessage) {
        if (message.senderId == deviceId) return
        val now = System.currentTimeMillis()
        gatewayRegistry.update(
            GatewayInfo(
                deviceId = message.senderId,
                name = message.senderName,
                // hopCount counts relays so far, so a directly connected gateway arrives with 0.
                hops = message.hopCount + 1,
                kinds = GatewayAnnounce.decode(message.body),
                lastSeen = now,
            ),
        )
        _gateways.value = gatewayRegistry.active(now)
    }

    // ---- Requester side ----

    /** Starts a search / AI query. Returns false (and does nothing) if [rawText] is blank or too long. */
    fun submitQuery(kind: QueryKind, rawText: String): Boolean {
        val text = QuerySandbox.cleanQuery(rawText) ?: return false
        val item = QueryItem(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            kind = kind,
            text = text,
            status = QueryStatus.WAITING_FOR_GATEWAY,
        )
        _queries.update { (listOf(item) + it).take(MAX_QUERIES_SHOWN) }
        meshScope.launch { runQuery(item) }
        return true
    }

    private fun updateQuery(id: String, transform: (QueryItem) -> QueryItem) {
        _queries.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun finishQuery(id: String, ok: Boolean, text: String) {
        pendingQueries.remove(id)
        updateQuery(id) { it.copy(status = if (ok) QueryStatus.DONE else QueryStatus.FAILED, answer = text) }
    }

    private suspend fun runQuery(item: QueryItem) {
        val deadline = System.currentTimeMillis() + QUERY_DEADLINE_MS
        val tried = mutableSetOf<String>()
        var attempts = 0
        var lastError: String? = null

        while (System.currentTimeMillis() < deadline && attempts < MAX_QUERY_ATTEMPTS) {
            // Already online with the right key? Then no need to involve the mesh at all.
            if (internet.online.value && item.kind in gatewaySettings.capabilities()) {
                updateQuery(item.id) { it.copy(status = QueryStatus.RUNNING_LOCALLY, gatewayName = null, hops = null) }
                val response = withContext(Dispatchers.IO) {
                    sandbox.execute(QueryRequest(item.id, item.kind, item.text))
                }
                finishQuery(item.id, response.ok, response.text)
                return
            }

            // Nearest gateway we haven't tried yet and already hold the public key of.
            val gateway = gatewayRegistry
                .candidates(item.kind, System.currentTimeMillis(), tried)
                .firstOrNull { it.deviceId in knownPublicKeys }
            if (gateway == null) {
                updateQuery(item.id) { it.copy(status = QueryStatus.WAITING_FOR_GATEWAY, gatewayName = null, hops = null) }
                delay(QUERY_POLL_MS)
                continue
            }

            attempts++
            tried += gateway.deviceId
            val pending = PendingQuery(gateway.deviceId, CompletableDeferred())
            pendingQueries[item.id] = pending
            updateQuery(item.id) {
                it.copy(status = QueryStatus.SENT, gatewayName = gateway.name, hops = gateway.hops)
            }
            sendQueryRequest(gateway, QueryRequest(item.id, item.kind, item.text))

            val response = withTimeoutOrNull(QUERY_ATTEMPT_TIMEOUT_MS) { pending.reply.await() }
            pendingQueries.remove(item.id)
            when {
                response == null -> {
                    lastError = "${gateway.name} didn't reply in time."
                    // Likely out of range by now — don't keep offering it to later queries.
                    gatewayRegistry.remove(gateway.deviceId)
                }
                response.ok -> {
                    finishQuery(item.id, true, response.text)
                    return
                }
                else -> lastError = response.text
            }
        }

        finishQuery(
            item.id,
            ok = false,
            text = lastError
                ?: "No internet gateway found in the mesh. Ask someone with internet to turn on " +
                    "\"Share my internet\" in VIT Connect, then try again.",
        )
    }

    private fun sendQueryRequest(gateway: GatewayInfo, request: QueryRequest) {
        val gatewayKey = knownPublicKeys[gateway.deviceId] ?: return
        val ciphertext = crypto.encrypt(request.toJson(), gatewayKey)
        sendGatewayMessage(
            MeshMessage(
                type = MeshMessageType.QUERY_REQUEST,
                senderId = deviceId,
                senderName = displayName,
                targetId = gateway.deviceId,
                body = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                encrypted = true,
            ),
        )
    }

    private fun handleQueryResponse(message: MeshMessage) {
        val gatewayKey = knownPublicKeys[message.senderId] ?: return
        val response = try {
            QueryResponse.fromJson(crypto.decrypt(Base64.decode(message.body, Base64.NO_WRAP), gatewayKey))
        } catch (e: Exception) {
            Log.w(TAG, "Undecryptable QUERY_RESPONSE from ${message.senderId}", e)
            null
        } ?: return
        // The request id is random and was only ever sent encrypted, and the reply must come from
        // the gateway we actually asked — together that stops anyone else injecting an answer.
        val pending = pendingQueries[response.requestId] ?: return
        if (pending.gatewayId != message.senderId) return
        pending.reply.complete(response)
    }

    // ---- Gateway side ----

    private fun handleQueryRequest(message: MeshMessage) {
        // Decrypting with the *claimed* sender's known key doubles as authentication: a request
        // forged under someone else's deviceId fails the AES-GCM tag check.
        val senderKey = knownPublicKeys[message.senderId] ?: return
        val request = try {
            QueryRequest.fromJson(crypto.decrypt(Base64.decode(message.body, Base64.NO_WRAP), senderKey))
        } catch (e: Exception) {
            Log.w(TAG, "Undecryptable QUERY_REQUEST from ${message.senderId}", e)
            null
        } ?: return

        fun reply(ok: Boolean, text: String) =
            sendQueryResponse(message.senderId, QueryResponse(request.requestId, ok, text))

        val offered = gatewayKindsOffered()
        when {
            offered.isEmpty() -> reply(false, "This device isn't sharing its internet right now.")
            request.kind !in offered -> reply(false, "This gateway can't do ${request.kind.label}.")
            !requestLimiter.tryAcquire(message.senderId, System.currentTimeMillis()) ->
                reply(false, "Too many requests from you — wait a few minutes and try again.")
            activeExecutions >= GatewayLimits.MAX_CONCURRENT_EXECUTIONS ->
                reply(false, "This gateway is busy. Try again in a moment.")
            else -> {
                activeExecutions++
                meshScope.launch {
                    val response = try {
                        withContext(Dispatchers.IO) { sandbox.execute(request) }
                    } finally {
                        activeExecutions--
                    }
                    sendQueryResponse(message.senderId, response)
                }
            }
        }
    }

    private fun sendQueryResponse(requesterId: String, response: QueryResponse) {
        val requesterKey = knownPublicKeys[requesterId] ?: return
        val ciphertext = crypto.encrypt(response.toJson(), requesterKey)
        sendGatewayMessage(
            MeshMessage(
                type = MeshMessageType.QUERY_RESPONSE,
                senderId = deviceId,
                senderName = displayName,
                targetId = requesterId,
                body = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                encrypted = true,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Advertising + discovery
    // ---------------------------------------------------------------------------------------

    /**
     * A failed startAdvertising()/startDiscovery() call only means "the mesh never came up" if
     * we weren't already running. Once we've genuinely reached RUNNING, existing connections
     * keep working regardless — a transient hiccup on a periodic discovery restart (the
     * watchdog) shouldn't be able to flip the UI into a permanent, misleading error state for a
     * session that's otherwise working fine.
     */
    private fun markErrorUnlessAlreadyRunning() {
        if (_status.value != MeshStatus.RUNNING) {
            _status.value = MeshStatus.ERROR
        }
    }

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
            markErrorUnlessAlreadyRunning()
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
            markErrorUnlessAlreadyRunning()
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
                // A live connection's advertised name is as fresh as it gets — always wins.
                updateKnownDevice(peer.deviceId, peer.displayName, System.currentTimeMillis())
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

            if (message.type.isGatewayTraffic) {
                handleGatewayTraffic(endpointId, message)
                return
            }

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
            // Use the announcement's own timestamp, not "now" — a replayed copy of an old
            // announcement must never be able to overwrite a name update that arrived since.
            updateKnownDevice(message.senderId, message.senderName, message.timestamp)
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
