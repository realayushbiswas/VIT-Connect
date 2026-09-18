package com.offgridmesh.vitconnect.mesh

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Message kinds. Only TEXT is used in Phase 1; SOS / ACK land in later phases. */
enum class MeshMessageType {
    TEXT,
    SOS,
    ACK;

    companion object {
        fun fromWire(raw: String): MeshMessageType =
            entries.find { it.name == raw } ?: TEXT
    }
}

/**
 * The envelope every byte payload sent over Nearby Connections is wrapped in.
 *
 * [targetId] is null for a mesh-wide broadcast (group chat / SOS) or set to a specific
 * [DeviceIdentity] deviceId for a 1:1 message. [ttl] and [hopCount] are unused by the Phase 1
 * direct-send path but are already part of the wire format so Phase 2's flood-relay logic
 * doesn't need a breaking format change.
 */
data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: MeshMessageType = MeshMessageType.TEXT,
    val senderId: String,
    val senderName: String,
    val targetId: String? = null,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = DEFAULT_TTL,
    val hopCount: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("senderId", senderId)
            put("senderName", senderName)
            put("targetId", targetId ?: JSONObject.NULL)
            put("body", body)
            put("timestamp", timestamp)
            put("ttl", ttl)
            put("hopCount", hopCount)
            put("lat", lat ?: JSONObject.NULL)
            put("lon", lon ?: JSONObject.NULL)
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Returns a copy with hopCount+1 and ttl-1, used when relaying (Phase 2). */
    fun forwarded(): MeshMessage = copy(ttl = ttl - 1, hopCount = hopCount + 1)

    companion object {
        const val DEFAULT_TTL = 6

        fun fromBytes(bytes: ByteArray): MeshMessage? = try {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            MeshMessage(
                id = json.getString("id"),
                type = MeshMessageType.fromWire(json.getString("type")),
                senderId = json.getString("senderId"),
                senderName = json.getString("senderName"),
                targetId = if (json.isNull("targetId")) null else json.getString("targetId"),
                body = json.getString("body"),
                timestamp = json.getLong("timestamp"),
                ttl = json.getInt("ttl"),
                hopCount = json.getInt("hopCount"),
                lat = if (json.isNull("lat")) null else json.getDouble("lat"),
                lon = if (json.isNull("lon")) null else json.getDouble("lon"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
