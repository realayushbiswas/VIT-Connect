package com.offgridmesh.vitconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offgridmesh.vitconnect.OffGridApp
import com.offgridmesh.vitconnect.mesh.MeshMessage
import com.offgridmesh.vitconnect.mesh.MeshStatus
import com.offgridmesh.vitconnect.mesh.PeerInfo
import com.offgridmesh.vitconnect.mesh.QrContact
import com.offgridmesh.vitconnect.mesh.gateway.GatewayInfo
import com.offgridmesh.vitconnect.mesh.gateway.QueryItem
import com.offgridmesh.vitconnect.mesh.gateway.QueryKind
import kotlinx.coroutines.flow.StateFlow

/** Thin Compose-friendly wrapper around the app-scoped [com.offgridmesh.vitconnect.mesh.MeshManager]. */
class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val meshManager get() = getApplication<OffGridApp>().meshManager

    val peers: StateFlow<Map<String, PeerInfo>> = meshManager.peers
    val messages: StateFlow<List<MeshMessage>> = meshManager.messages
    val status: StateFlow<MeshStatus> = meshManager.status
    val knownDevices: StateFlow<Map<String, String>> = meshManager.knownDevices
    val directConversations: StateFlow<Map<String, List<MeshMessage>>> = meshManager.directConversations
    val verifiedContactIds: StateFlow<Set<String>> = meshManager.verifiedContactIds

    val deviceId: String get() = meshManager.deviceId
    val myQrPayload: String get() = meshManager.myQrPayload

    var displayName: String
        get() = meshManager.displayName
        set(value) {
            meshManager.displayName = value
        }

    fun start() = meshManager.start()

    fun sendText(body: String) {
        if (body.isNotBlank()) meshManager.sendText(body.trim())
    }

    /** Returns false if we haven't learned [targetDeviceId]'s public key yet (message not sent). */
    fun sendDirectMessage(targetDeviceId: String, body: String): Boolean {
        if (body.isBlank()) return true
        return meshManager.sendDirectMessage(targetDeviceId, body.trim())
    }

    /** Null if we haven't learned this device's public key yet. */
    fun safetyNumberFor(theirDeviceId: String): String? = meshManager.safetyNumberFor(theirDeviceId)

    /** Adds a contact scanned from a QR code. Returns false if the payload was invalid. */
    fun addContactFromQr(contact: QrContact): Boolean = meshManager.addContactFromQr(contact)

    // ---- Internet gateway: search / AI for devices without a connection ----

    val queries: StateFlow<List<QueryItem>> = meshManager.queries
    val gateways: StateFlow<List<GatewayInfo>> = meshManager.gateways
    val hasInternet: StateFlow<Boolean> = meshManager.hasInternet
    val servingAsGateway: StateFlow<Boolean> = meshManager.servingAsGateway
    val shareEnabled: StateFlow<Boolean> = meshManager.gatewaySettings.shareEnabled

    /** Returns false if [text] is blank or longer than [com.offgridmesh.vitconnect.mesh.gateway.GatewayLimits.MAX_QUERY_CHARS]. */
    fun submitQuery(kind: QueryKind, text: String): Boolean = meshManager.submitQuery(kind, text)

    /** True if this phone is online and holds the key to answer [kind] itself, with no gateway needed. */
    fun canAnswerLocally(kind: QueryKind): Boolean =
        hasInternet.value && kind in meshManager.gatewaySettings.capabilities()

    fun setShareEnabled(enabled: Boolean) = meshManager.gatewaySettings.setShareEnabled(enabled)

    val braveKey: String get() = meshManager.gatewaySettings.braveKey.orEmpty()
    val anthropicKey: String get() = meshManager.gatewaySettings.anthropicKey.orEmpty()

    fun saveGatewayKeys(brave: String, anthropic: String) =
        meshManager.gatewaySettings.setKeys(brave, anthropic)
}
