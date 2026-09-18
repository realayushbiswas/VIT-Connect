package com.offgridmesh.vitconnect

import android.app.Application
import com.offgridmesh.vitconnect.mesh.MeshManager

/** Holds the single app-lifetime [MeshManager] instance. */
class OffGridApp : Application() {
    val meshManager: MeshManager by lazy { MeshManager(applicationContext) }
}
