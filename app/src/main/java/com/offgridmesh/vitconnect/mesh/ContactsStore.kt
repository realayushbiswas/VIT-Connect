package com.offgridmesh.vitconnect.mesh

import android.content.Context
import org.json.JSONObject

/**
 * Persists QR-verified contacts to disk (SharedPreferences), so someone you scan once stays
 * known — with encryption already working — across app restarts, "just like contacts" rather
 * than something the mesh has to re-discover every session.
 */
object ContactsStore {
    private const val PREFS = "offgrid_mesh_contacts"
    private const val KEY_CONTACTS = "contacts_json"

    fun loadAll(context: Context): List<QrContact> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { deviceId ->
                val entry = json.optJSONObject(deviceId) ?: return@mapNotNull null
                QrContact(
                    deviceId = deviceId,
                    name = entry.optString("name", deviceId),
                    publicKeyBase64 = entry.optString("key", ""),
                )
            }.filter { it.publicKeyBase64.isNotEmpty() }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, contact: QrContact) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONTACTS, null)
        val json = if (raw != null) try { JSONObject(raw) } catch (e: Exception) { JSONObject() } else JSONObject()
        json.put(
            contact.deviceId,
            JSONObject().apply {
                put("name", contact.name)
                put("key", contact.publicKeyBase64)
            },
        )
        prefs.edit().putString(KEY_CONTACTS, json.toString()).apply()
    }
}
