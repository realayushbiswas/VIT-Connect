package com.offgridmesh.vitconnect.mesh

import android.content.Context
import java.util.UUID

/**
 * Persistent per-install identity, stored in SharedPreferences.
 *
 * Nearby's endpointId is ephemeral (it changes every advertising/discovery session), so we
 * mint our own stable [deviceId] here and stamp it onto every advertised endpoint name and
 * every outgoing message. That's what lets Phase 2 relay logic recognize "this message is
 * addressed to me" even after it has hopped through several endpointIds it's never seen before.
 */
object DeviceIdentity {

    private const val PREFS = "offgrid_mesh_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DISPLAY_NAME = "display_name"

    // '|' is our field separator in the advertised endpoint name — never allow it in free text.
    private fun sanitize(raw: String): String = raw.replace("|", "").trim()

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    fun getDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DISPLAY_NAME, null)
        if (existing != null) return existing
        val generated = "Student-${getOrCreateDeviceId(context).takeLast(4)}"
        prefs.edit().putString(KEY_DISPLAY_NAME, generated).apply()
        return generated
    }

    fun setDisplayName(context: Context, name: String) {
        val clean = sanitize(name).ifBlank { getDisplayName(context) }.take(24)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISPLAY_NAME, clean).apply()
    }
}
