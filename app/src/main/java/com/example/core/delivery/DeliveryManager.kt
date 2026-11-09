package com.example.core.delivery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Manages the "BOTH Delivery System" (Real Office File + Clipboard Content).
 */
class DeliveryManager(private val context: Context) {

    fun deliverBoth(
        file: File,
        textContent: String,
        mimeType: String,
        title: String,
        openImmediately: Boolean = false
    ): DeliveryResult {
        // 1. Verify existence & size
        if (!file.exists() || file.length() == 0L) {
            return DeliveryResult(
                fileVerified = false,
                fileSize = 0L,
                clipboardCopied = false,
                message = "File generation failed: file is empty or does not exist."
            )
        }

        // 2. Copy textual content to Android Clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(title, textContent)
        clipboard.setPrimaryClip(clip)

        // 3. Optional open or prepare intent
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        if (openImmediately) {
            openFile(file, mimeType)
        }

        return DeliveryResult(
            fileVerified = true,
            fileSize = file.length(),
            clipboardCopied = true,
            contentUri = uri,
            message = "Document created (${file.length() / 1024} KB) and text copied to clipboard."
        )
    }

    fun openFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No compatible application found to open this file.", Toast.LENGTH_LONG).show()
        }
    }

    fun shareFile(file: File, mimeType: String, subject: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share Document").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to share file.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MIME_PDF = "application/pdf"
    }
}

data class DeliveryResult(
    val fileVerified: Boolean,
    val fileSize: Long,
    val clipboardCopied: Boolean,
    val contentUri: Uri? = null,
    val message: String
)
