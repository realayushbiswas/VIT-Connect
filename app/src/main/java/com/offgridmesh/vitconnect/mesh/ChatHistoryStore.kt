package com.offgridmesh.vitconnect.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Persists group chat history, direct (1:1) chat history, and the relay/store-and-forward
 * cache to disk (SharedPreferences), so all three survive an app restart — chat history
 * "just like contacts," and undelivered messages keep waiting for their recipient to show up
 * even if the sender's phone gets closed and reopened in the meantime.
 *
 * Deliberately simple (a JSON blob per prefs key) rather than a database — message volumes in
 * this app are small (a phone's own conversations), so this is a reasonable trade for a
 * hackathon build; it would be the first thing to swap for SQLite/Room if this needed to scale.
 */
object ChatHistoryStore {
    private const val PREFS = "offgrid_mesh_history"
    private const val KEY_GROUP_MESSAGES = "group_messages"
    private const val KEY_DIRECT_CONVERSATIONS = "direct_conversations"
    private const val KEY_RELAY_CACHE = "relay_cache"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun messageListToJson(messages: List<MeshMessage>): JSONArray =
        JSONArray().apply {
            messages.forEach { put(String(it.toBytes(), StandardCharsets.UTF_8)) }
        }

    private fun jsonToMessageList(array: JSONArray): List<MeshMessage> =
        (0 until array.length()).mapNotNull { i ->
            MeshMessage.fromBytes(array.getString(i).toByteArray(StandardCharsets.UTF_8))
        }

    fun saveGroupMessages(context: Context, messages: List<MeshMessage>) {
        prefs(context).edit().putString(KEY_GROUP_MESSAGES, messageListToJson(messages).toString()).apply()
    }

    fun loadGroupMessages(context: Context): List<MeshMessage> {
        val raw = prefs(context).getString(KEY_GROUP_MESSAGES, null) ?: return emptyList()
        return try {
            jsonToMessageList(JSONArray(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDirectConversations(context: Context, conversations: Map<String, List<MeshMessage>>) {
        val json = JSONObject()
        conversations.forEach { (deviceId, messages) -> json.put(deviceId, messageListToJson(messages)) }
        prefs(context).edit().putString(KEY_DIRECT_CONVERSATIONS, json.toString()).apply()
    }

    fun loadDirectConversations(context: Context): Map<String, List<MeshMessage>> {
        val raw = prefs(context).getString(KEY_DIRECT_CONVERSATIONS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { deviceId -> jsonToMessageList(json.getJSONArray(deviceId)) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveRelayCache(context: Context, cache: Collection<MeshMessage>) {
        prefs(context).edit().putString(KEY_RELAY_CACHE, messageListToJson(cache.toList()).toString()).apply()
    }

    fun loadRelayCache(context: Context): List<MeshMessage> {
        val raw = prefs(context).getString(KEY_RELAY_CACHE, null) ?: return emptyList()
        return try {
            jsonToMessageList(JSONArray(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
