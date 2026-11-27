package com.offgridmesh.vitconnect.mesh.gateway

import org.json.JSONObject

/** The only two things a gateway will ever do on someone else's behalf. */
enum class QueryKind(val label: String) {
    SEARCH("Search"),
    ASK_AI("Ask AI");

    companion object {
        fun fromWire(raw: String?): QueryKind? = entries.find { it.name == raw }
    }
}

/** Hard limits that bound what a gateway will accept, spend and send back. */
object GatewayLimits {
    const val MAX_QUERY_CHARS = 300

    /**
     * Cap on the plaintext answer. Nearby caps a BYTES payload at 32 KB; after AES-GCM, base64
     * and the JSON envelope, 10 KB of plaintext stays comfortably below that, so an answer
     * always fits in one message and never needs chunking.
     */
    const val MAX_RESPONSE_BYTES = 10_000

    /** Most bytes we will read from an upstream HTTP response before giving up on it. */
    const val MAX_HTTP_BYTES = 512 * 1024
    const val HTTP_TIMEOUT_MS = 15_000

    /** Per requesting device: at most this many requests per window. */
    const val RATE_LIMIT_REQUESTS = 10
    const val RATE_LIMIT_WINDOW_MS = 10 * 60 * 1000L

    /** Upstream calls a gateway runs at once, so it can't be used to drain its battery/data. */
    const val MAX_CONCURRENT_EXECUTIONS = 2
}

/** Decrypted payload of a QUERY_REQUEST. [requestId] is chosen by the requester. */
data class QueryRequest(val requestId: String, val kind: QueryKind, val text: String) {
    fun toJson(): String = JSONObject()
        .put("rid", requestId)
        .put("kind", kind.name)
        .put("q", text)
        .toString()

    companion object {
        fun fromJson(raw: String): QueryRequest? = try {
            val json = JSONObject(raw)
            val kind = QueryKind.fromWire(json.getString("kind"))
            val rid = json.getString("rid")
            if (kind == null || rid.isBlank() || rid.length > 64) null
            else QueryRequest(rid, kind, json.getString("q"))
        } catch (e: Exception) {
            null
        }
    }
}

/** Decrypted payload of a QUERY_RESPONSE. */
data class QueryResponse(val requestId: String, val ok: Boolean, val text: String) {
    fun toJson(): String = JSONObject()
        .put("rid", requestId)
        .put("ok", ok)
        .put("text", text)
        .toString()

    companion object {
        fun fromJson(raw: String): QueryResponse? = try {
            val json = JSONObject(raw)
            QueryResponse(json.getString("rid"), json.getBoolean("ok"), json.getString("text"))
        } catch (e: Exception) {
            null
        }
    }
}

/** Body of a GATEWAY_ANNOUNCE: which kinds of query this gateway is currently willing to serve. */
object GatewayAnnounce {
    fun encode(kinds: Set<QueryKind>): String = JSONObject()
        .put("kinds", org.json.JSONArray(kinds.map { it.name }))
        .toString()

    fun decode(raw: String): Set<QueryKind> = try {
        val arr = JSONObject(raw).getJSONArray("kinds")
        (0 until arr.length()).mapNotNull { QueryKind.fromWire(arr.optString(it)) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }
}

enum class QueryStatus {
    /** Offline and no gateway currently known — will keep looking until the deadline. */
    WAITING_FOR_GATEWAY,

    /** Sent over the mesh; waiting for the gateway's reply. */
    SENT,

    /** Answered by this device's own internet connection. */
    RUNNING_LOCALLY,
    DONE,
    FAILED,
}

/** One query as shown in the UI. Lives in memory only — answers are not persisted. */
data class QueryItem(
    val id: String,
    val kind: QueryKind,
    val text: String,
    val status: QueryStatus,
    /** Display name and hop distance of the gateway currently handling it, if any. */
    val gatewayName: String? = null,
    val hops: Int? = null,
    val answer: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
