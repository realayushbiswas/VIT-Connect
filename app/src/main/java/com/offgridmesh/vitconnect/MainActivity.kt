package com.offgridmesh.vitconnect

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.offgridmesh.vitconnect.ui.MeshViewModel
import com.offgridmesh.vitconnect.ui.screens.ChatScreen
import com.offgridmesh.vitconnect.ui.theme.MeshPurpleLight
import com.offgridmesh.vitconnect.ui.theme.OffGridMeshTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OffGridMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OffGridMeshRoot(viewModel)
                }
            }
        }
    }
}

/** List of runtime ("dangerous") permissions Nearby Connections needs, by API level. */
private fun meshPermissions(): List<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms += Manifest.permission.BLUETOOTH_ADVERTISE
        perms += Manifest.permission.BLUETOOTH_CONNECT
        perms += Manifest.permission.BLUETOOTH_SCAN
    } else {
        perms += Manifest.permission.BLUETOOTH
        perms += Manifest.permission.BLUETOOTH_ADMIN
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= 33) {
        perms += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    return perms
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun OffGridMeshRoot(viewModel: MeshViewModel) {
    val permissionsState = rememberMultiplePermissionsState(permissions = meshPermissions())

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    if (permissionsState.allPermissionsGranted) {
        LaunchedEffect(Unit) { viewModel.start() }
        ChatScreen(viewModel)
    } else {
        PermissionGateScreen(onRequestClick = { permissionsState.launchMultiplePermissionRequest() })
    }
}

@Composable
private fun PermissionGateScreen(onRequestClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.BluetoothSearching,
            contentDescription = null,
            tint = MeshPurpleLight,
            modifier = Modifier.size(64.dp),
        )
        Text(
            "VIT Connect needs Bluetooth, Wi-Fi and Location permissions to discover nearby devices and build the mesh — no internet required.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 20.dp),
        )
        Button(onClick = onRequestClick) {
            Text("Grant permissions")
        }
    }
}
