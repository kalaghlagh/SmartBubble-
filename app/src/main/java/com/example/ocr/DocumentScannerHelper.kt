package com.example.ocr

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

object DocumentScannerHelper {

    fun createScannerClient(context: Context): GmsDocumentScanner {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        return GmsDocumentScanning.getClient(options)
    }

    fun startDocumentScan(
        activity: Activity,
        onIntentSenderReady: (IntentSenderRequest) -> Unit,
        onErrorOrUnavailable: (Exception) -> Unit
    ) {
        try {
            val scanner = createScannerClient(activity)
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    onIntentSenderReady(request)
                }
                .addOnFailureListener { e ->
                    onErrorOrUnavailable(e)
                }
        } catch (e: Exception) {
            onErrorOrUnavailable(e)
        }
    }

    fun parseScanResult(resultCode: Int, data: android.content.Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || data == null) return emptyList()
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return emptyList()
        return scanResult.pages?.mapNotNull { it.imageUri } ?: emptyList()
    }
}
