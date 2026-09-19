package com.offgridmesh.vitconnect.mesh

import org.json.JSONObject

/**
 * The payload encoded into a "my QR code" / decoded from a scanned one. Carries everything
 * needed to add someone as a trusted contact on sight: their persistent deviceId, current
 * display name, and EC public key — all exchanged in person, out-of-band from the mesh itself,
 * which is a *stronger* trust anchor than the mesh's own gossiped KEY_ANNOUNCE (you're looking
 * at the person whose code you're scanning, so there's no possibility of a relay hop lying
 * about whose key this is).
 */
data class QrContact(
    val deviceId: String,
    val name: String,
    val publicKeyBase64: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("id", deviceId)
        put("name", name)
        put("key", publicKeyBase64)
    }.toString()

    companion object {
        fun fromJson(raw: String): QrContact? = try {
            val json = JSONObject(raw)
            QrContact(
                deviceId = json.getString("id"),
                name = json.getString("name"),
                publicKeyBase64 = json.getString("key"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
