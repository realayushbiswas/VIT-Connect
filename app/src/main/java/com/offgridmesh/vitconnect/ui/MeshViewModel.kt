package com.offgridmesh.vitconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offgridmesh.vitconnect.OffGridApp
import com.offgridmesh.vitconnect.mesh.MeshMessage
import com.offgridmesh.vitconnect.mesh.MeshStatus
import com.offgridmesh.vitconnect.mesh.PeerInfo
import kotlinx.coroutines.flow.StateFlow

/** Thin Compose-friendly wrapper around the app-scoped [com.offgridmesh.vitconnect.mesh.MeshManager]. */
class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val meshManager get() = getApplication<OffGridApp>().meshManager

    val peers: StateFlow<Map<String, PeerInfo>> = meshManager.peers
    val messages: StateFlow<List<MeshMessage>> = meshManager.messages
    val status: StateFlow<MeshStatus> = meshManager.status

    val deviceId: String get() = meshManager.deviceId

    var displayName: String
        get() = meshManager.displayName
        set(value) {
            meshManager.displayName = value
        }

    fun start() = meshManager.start()

    fun sendText(body: String) {
        if (body.isNotBlank()) meshManager.sendText(body.trim())
    }
}
