package com.offgridmesh.vitconnect.ui.screens

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.offgridmesh.vitconnect.mesh.QrContact
import com.offgridmesh.vitconnect.ui.MeshViewModel

/**
 * Your own QR code (encodes deviceId + display name + public key) plus a way to scan someone
 * else's. Scanning adds them as a persistent, encryption-ready contact immediately — no need
 * for either of you to be reachable in the mesh at the moment you scan.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MyQrScreen(viewModel: MeshViewModel, onBack: () -> Unit, onContactAdded: (String) -> Unit) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) return@rememberLauncherForActivityResult
        val contact = QrContact.fromJson(contents)
        when {
            contact == null -> errorMessage = "That doesn't look like a VIT Connect contact code."
            !viewModel.addContactFromQr(contact) -> errorMessage = "Couldn't add that contact — try again."
            else -> {
                errorMessage = null
                onContactAdded(contact.deviceId)
            }
        }
    }

    val payload = viewModel.myQrPayload
    val qrBitmap = remember(payload) { generateQrBitmap(payload, 720) }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(title = "My QR Code", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Your contact QR code",
                    modifier = Modifier
                        .size(240.dp)
                        .padding(16.dp),
                )
            }
            Text(
                viewModel.displayName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Have someone scan this to add you as a contact — it works even before either of you is connected to the mesh, and their app will already be able to send you encrypted messages the moment you're both reachable.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            if (cameraPermission.status.isGranted) {
                Button(onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point your camera at a VIT Connect QR code")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                }) { Text("Scan a friend's code") }
            } else {
                OutlinedButton(onClick = { cameraPermission.launchPermissionRequest() }) {
                    Text("Allow camera to scan a code")
                }
            }

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap =
    BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
