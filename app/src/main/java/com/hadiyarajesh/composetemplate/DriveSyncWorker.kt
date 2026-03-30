package com.hadiyarajesh.composetemplate

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.FileOutputStream

class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var totalFiles = 0
    private var processedFiles = 0

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("drive_access_token", null) ?: return Result.failure()
        val folderUris = prefs.getStringSet("folder_uris", emptySet()) ?: emptySet()
        val syncMode = inputData.getString("sync_mode") ?: "replace"

        if (folderUris.isEmpty()) return Result.success()

        try {
            val credential = GoogleCredential().setAccessToken(token)
            val driveService = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("K's GDrive Syncer").build()

            // 1. Create/Get the master root folder
            val rootId = getOrCreateFolder(driveService, "K's Gdrive syncer files")

            // 2. Count total files for the progress bar
            folderUris.forEach { uri ->
                DocumentFile.fromTreeUri(applicationContext, Uri.parse(uri))?.let { countFiles(it) }
            }

            if (totalFiles == 0) return Result.success()

            // 3. Start Recursive Sync
            folderUris.forEach { uri ->
                val localFolder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(uri))
                if (localFolder != null) {
                    val driveSubFolderId = getOrCreateFolder(driveService, localFolder.name ?: "Folder", rootId)
                    syncDirectoryRecursive(driveService, localFolder, driveSubFolderId, syncMode)
                }
            }

            return Result.success()
            } catch (e: Exception) {
            Log.e("SyncWorker", "Critical Failure: ${e.message}", e)
            
            // If Google kicks back a 401, the 1-hour token is dead.
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                Log.e("SyncWorker", "Token expired. Forcing disconnect.")
                prefs.edit().putBoolean("drive_connected", false).remove("drive_access_token").apply()
            }
            
            return Result.failure()
        }
    }

    private fun countFiles(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { countFiles(it) }
        } else {
            totalFiles++
        }
    }

    private suspend fun syncDirectoryRecursive(service: Drive, localDir: DocumentFile, driveParentId: String, mode: String) {
        localDir.listFiles().forEach { item ->
            if (item.isDirectory) {
                // Mirror the subfolder structure
                val newDriveFolderId = getOrCreateFolder(service, item.name ?: "Subfolder", driveParentId)
                syncDirectoryRecursive(service, item, newDriveFolderId, mode)
            } else {
                // Upload the file
                syncFile(service, item, driveParentId, mode)
                processedFiles++
                setProgress(workDataOf("progress" to (processedFiles * 100 / totalFiles)))
            }
        }
    }

private fun getOrCreateFolder(service: Drive, name: String, parentId: String? = null): String {
        // 1. Escape the single quote so the Google Drive API doesn't crash
        val escapedName = name.replace("'", "\\'") 
        
        val query = "name = '$escapedName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false" +
                (if (parentId != null) " and '$parentId' in parents" else "")
        
        val existing = service.files().list().setQ(query).setFields("files(id)").execute().files
        if (!existing.isNullOrEmpty()) return existing[0].id

        val folderMetadata = DriveFile().apply {
            this.name = name
            this.mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) this.parents = listOf(parentId)
        }
        return service.files().create(folderMetadata).setFields("id").execute().id
    }

    private fun syncFile(service: Drive, docFile: DocumentFile, parentId: String, mode: String) {
        val name = docFile.name ?: return
        var existingFileId: String? = null

if (mode == "replace") {
            val escapedFileName = name.replace("'", "\\'")
            val query = "name = '$escapedFileName' and '$parentId' in parents and trashed = false"
            val existing = service.files().list().setQ(query).setFields("files(id)").execute().files
            if (!existing.isNullOrEmpty()) existingFileId = existing[0].id
        }

        val inputStream = applicationContext.contentResolver.openInputStream(docFile.uri)
        val tempFile = java.io.File(applicationContext.cacheDir, name).apply {
            inputStream?.use { input -> FileOutputStream(this).use { output -> input.copyTo(output) } }
        }
        val mediaContent = FileContent(docFile.type, tempFile)

        if (existingFileId != null) {
            service.files().update(existingFileId, null, mediaContent).execute()
        } else {
            val metadata = DriveFile().apply { this.name = name; this.parents = listOf(parentId) }
            service.files().create(metadata, mediaContent).execute()
        }
        tempFile.delete()
    }
}