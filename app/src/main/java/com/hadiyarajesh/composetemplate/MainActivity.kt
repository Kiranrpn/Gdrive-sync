package com.hadiyarajesh.composetemplate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.material3.SwitchDefaults
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SyncScreen() } }
    }
}

@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE) }

    val initialFolders: List<String> =
        prefs.getStringSet("folder_uris", emptySet())?.toList()?.sorted() ?: emptyList()

    var folderUris: List<String> by remember { mutableStateOf(initialFolders) }
    var driveConnected by remember { mutableStateOf(prefs.getBoolean("drive_connected", false)) }
    var showSyncDialog by remember { mutableStateOf(false) }

    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagLiveData("sync_job")
        .observeAsState()

    val activeWork = workInfos?.find { it.state == WorkInfo.State.RUNNING }
    val progressInt = activeWork?.progress?.getInt("progress", 0) ?: 0

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val authResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(result.data)

            if (!authResult.accessToken.isNullOrBlank()) {
                prefs.edit()
                    .putBoolean("drive_connected", true)
                    .putString("drive_access_token", authResult.accessToken)
                    .apply()
                driveConnected = true
            }
        } catch (e: Exception) {
                android.util.Log.e("AuthError", "Auth Exception: ${e.message}", e)
                Toast.makeText(context, "Sign-in failed. Check Logcat.", Toast.LENGTH_LONG).show()
                driveConnected = false
            }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            context.contentResolver.takePersistableUriPermission(
                pickedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val updated: MutableList<String> = folderUris.toMutableList()
            val pickedUriString = pickedUri.toString()

            if (!updated.contains(pickedUriString)) {
                updated.add(pickedUriString)
            }

            val sorted: List<String> = updated.sorted()
            folderUris = sorted
            prefs.edit().putStringSet("folder_uris", sorted.toSet()).apply()
        }
    }

    fun startAuth() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    authLauncher.launch(
                        IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()
                    )
                } else if (!result.accessToken.isNullOrBlank()) {
                    prefs.edit()
                        .putBoolean("drive_connected", true)
                        .putString("drive_access_token", result.accessToken)
                        .apply()
                    driveConnected = true
                }
            }
            .addOnFailureListener { driveConnected = false }
    }

    fun disconnectDrive() {
        Identity.getSignInClient(context).signOut()
        prefs.edit().putBoolean("drive_connected", false).remove("drive_access_token").apply()
        driveConnected = false
        WorkManager.getInstance(context).cancelAllWorkByTag("sync_job")
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("Sync Mode") },
            text = { Text("Replace existing files on GDrive or add them as new copies?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        triggerSync(context, "replace")
                        showSyncDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("REPLACE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        triggerSync(context, "add")
                        showSyncDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF2196F3)
                    )
                ) {
                    Text("ADD NEW")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "K's GDrive Syncer",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light
        )

        if (activeWork != null) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                LinearProgressIndicator(
                    progress = { progressInt / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF212121)
                )
                Text(
                    text = "Progress: $progressInt%",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        Button(
            onClick = { folderPickerLauncher.launch(null) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF212121))
        ) {
            Text("Add Local Folder")
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(folderUris) { uri: String ->
                val name: String = DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name ?: "Folder"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
Column(modifier = Modifier.weight(1f)) {
                            // Show the Folder Name
                            Text(
                                text = name,
                                fontWeight = FontWeight.Medium
                            )
                            // Decode the ugly URI into a readable path
                            val readablePath = android.net.Uri.decode(uri)
                                .substringAfter("tree/")
                                .replace("primary:", "Internal Storage/")
                            
                            // Show the Path below it
                            Text(
                                text = readablePath,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        IconButton(
                            onClick = {
                                val updated: MutableList<String> = folderUris.toMutableList()
                                updated.remove(uri)
                                val sorted: List<String> = updated.sorted()
                                folderUris = sorted
                                prefs.edit().putStringSet("folder_uris", sorted.toSet()).apply()
                            }
                        ) {
                            Text("✕", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (driveConnected && folderUris.isNotEmpty()) {
            Button(
                onClick = { showSyncDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)) 
            ) {
                Text("Sync Now", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Connect to Google Drive")
Switch(
                checked = driveConnected,
                onCheckedChange = { if (it) startAuth() else disconnectDrive() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4CAF50) // Material Green
                )
            )
        }
    }
}

fun triggerSync(context: Context, mode: String) {
    val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
        .addTag("sync_job")
        .setInputData(workDataOf("sync_mode" to mode))
        .build()

    WorkManager.getInstance(context).enqueue(request)
}