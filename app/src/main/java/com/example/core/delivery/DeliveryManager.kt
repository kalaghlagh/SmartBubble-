package com.example.core.delivery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.R
import java.io.File

/**
 * Manages the "BOTH Delivery System" (Real Office File + Clipboard Content).
 */
class DeliveryManager(
    private val context: Context,
    /** Strings in the app language (the application context follows the system language only). */
    private val strings: (Int) -> String = { context.getString(it) }
) {

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
            null // never fall back to a file:// URI (it would expose a private path)
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
            val chooser = Intent.createChooser(intent, strings(R.string.delivery_open_with)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, strings(R.string.delivery_no_app), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // For example a file outside the folders the FileProvider exposes: not a missing app.
            Log.e("DeliveryManager", "openFile failed", e)
            Toast.makeText(context, strings(R.string.delivery_open_failed), Toast.LENGTH_LONG).show()
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
            val chooser = Intent.createChooser(intent, strings(R.string.delivery_share_with)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e("DeliveryManager", "shareFile failed", e)
            Toast.makeText(context, strings(R.string.delivery_share_failed), Toast.LENGTH_SHORT).show()
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
