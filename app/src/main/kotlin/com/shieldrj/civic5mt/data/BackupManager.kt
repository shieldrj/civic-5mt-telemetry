package com.shieldrj.civic5mt.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.shieldrj.civic5mt.service.loadBackupDocUri
import com.shieldrj.civic5mt.service.markBackedUp
import com.shieldrj.civic5mt.service.restoreRecords
import com.shieldrj.civic5mt.service.saveBackupUris
import com.shieldrj.civic5mt.service.snapshotRecords

/**
 * Copies the irreplaceable records out of the app's private storage to a folder of the
 * driver's choosing, and back.
 *
 * The mechanism is the Storage Access Framework rather than a database export or a cloud:
 * one JSON file in a folder the driver picked once, written silently after every drive. It
 * lands in Google Drive or a synced folder if that is where they pointed it, it can be read
 * on a computer without this app installed, and restoring it needs no trust in anyone's
 * servers - which matters when the payload is a lifetime of real driving that cannot be
 * measured twice.
 */
object BackupManager {

    private const val FILE_NAME = "civic5mt-backup.json"
    private const val MIME_TYPE = "application/json"

    /**
     * Called once, with the folder the driver picked in the system picker.
     *
     * Persists the grant so future backups need no interaction, finds or creates the backup
     * document inside it, and writes the first backup immediately - so "set up backup" ends
     * with a backup existing, not with a promise of one.
     */
    fun onFolderPicked(context: Context, treeUri: Uri): Boolean {
        val resolver = context.contentResolver
        val writeFlags =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        runCatching {
            resolver.takePersistableUriPermission(treeUri, writeFlags)
        }.onFailure { return false }

        val docUri = findExistingDocument(context, treeUri)
            ?: runCatching {
                DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    ),
                    MIME_TYPE,
                    FILE_NAME,
                )
            }.getOrNull()
            ?: return false

        saveBackupUris(context, treeUri.toString(), docUri.toString())
        return runBackup(context)
    }

    /**
     * Writes the current records to the chosen document.
     *
     * Safe to call from anywhere at any time: with no location configured it does nothing,
     * and if the document has vanished (folder moved, file deleted by hand) it recreates it
     * once before giving up quietly. A failed backup must never become a crash at the end of
     * a drive.
     *
     * @return whether a backup was actually written.
     */
    fun runBackup(context: Context): Boolean {
        val docUri = loadBackupDocUri(context)?.let(Uri::parse) ?: return false

        val wrote = writeTo(context, docUri)
        if (wrote) {
            markBackedUp(context, System.currentTimeMillis())
            return true
        }

        // The document may have been deleted out from under its saved URI. Re-find or remake
        // it inside the still-granted tree, exactly once.
        val treeUri = com.shieldrj.civic5mt.service.loadBackupTreeUri(context)?.let(Uri::parse)
            ?: return false
        val fresh = findExistingDocument(context, treeUri) ?: runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                ),
                MIME_TYPE,
                FILE_NAME,
            )
        }.getOrNull() ?: return false

        saveBackupUris(context, treeUri.toString(), fresh.toString())
        val recovered = writeTo(context, fresh)
        if (recovered) markBackedUp(context, System.currentTimeMillis())
        return recovered
    }

    /**
     * Reads the backup document and fills in whatever this phone is missing.
     *
     * @return a human-readable summary of what was restored, or null when there was nothing
     *   to do - no location configured, unreadable file, or a phone already holding every
     *   record the backup has.
     */
    fun restore(context: Context): String? {
        val docUri = loadBackupDocUri(context)?.let(Uri::parse) ?: return null
        val text = runCatching {
            context.contentResolver.openInputStream(docUri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        }.getOrNull() ?: return null

        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        val restored = restoreRecords(context, json)
        if (restored.isEmpty()) return null
        return "Restored " + restored.joinToString(", ")
    }

    private fun writeTo(context: Context, docUri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(docUri, "wt")?.use { stream ->
            stream.write(snapshotRecords(context).toString().toByteArray(Charsets.UTF_8))
            true
        } ?: false
    }.getOrDefault(false)

    private fun findExistingDocument(context: Context, treeUri: Uri): Uri? = runCatching {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name == FILE_NAME) {
                    val id = cursor.getString(0)
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
            null
        }
    }.getOrNull()
}
