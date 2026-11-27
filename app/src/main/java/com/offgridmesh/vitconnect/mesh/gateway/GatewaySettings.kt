package com.offgridmesh.vitconnect.mesh.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The gateway owner's choices, kept in app-private storage on their device only.
 *
 * Sharing is opt-in and off by default: serving the mesh spends the owner's data and battery and
 * their API quota, so nobody becomes a gateway unless they turn it on and supply their own keys.
 */
class GatewaySettings(context: Context) : ApiKeys {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _shareEnabled = MutableStateFlow(prefs.getBoolean(KEY_SHARE, false))
    val shareEnabled: StateFlow<Boolean> = _shareEnabled.asStateFlow()

    fun setShareEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHARE, enabled).apply()
        _shareEnabled.value = enabled
    }

    override val braveKey: String? get() = prefs.getString(KEY_BRAVE, null)
    override val anthropicKey: String? get() = prefs.getString(KEY_ANTHROPIC, null)

    fun setKeys(brave: String, anthropic: String) {
        prefs.edit()
            .putString(KEY_BRAVE, brave.trim())
            .putString(KEY_ANTHROPIC, anthropic.trim())
            .apply()
    }

    /** Which kinds of query this device could answer, given the keys it has. */
    fun capabilities(): Set<QueryKind> = buildSet {
        if (!braveKey.isNullOrBlank()) add(QueryKind.SEARCH)
        if (!anthropicKey.isNullOrBlank()) add(QueryKind.ASK_AI)
    }

    private companion object {
        const val PREFS = "vitconnect_gateway"
        const val KEY_SHARE = "share_enabled"
        const val KEY_BRAVE = "brave_key"
        const val KEY_ANTHROPIC = "anthropic_key"
    }
}

/** Whether this device currently has a working (validated) internet connection. */
class InternetMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    init {
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        } catch (e: SecurityException) {
            // Without ACCESS_NETWORK_STATE we can't watch changes; keep the initial reading.
        }
    }
}
