package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.ai.orchestrator.CentralAiOrchestrator
import com.example.document.model.ScannedPageData
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.InputStream

class DocumentOcrEngine(
    private val context: Context,
    private val orchestrator: CentralAiOrchestrator? = null
) {
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun processPages(
        pageUris: List<Uri>,
        useAiEnhancement: Boolean = true,
        onProgress: (pageIndex: Int, total: Int, message: String) -> Unit = { _, _, _ -> },
        /** Called when AI clean-up of a page fails; the page then keeps its raw OCR text. */
        onAiEnhancementFailed: (Exception) -> Unit = {}
    ): List<ScannedPageData> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedPageData>()

        for ((index, uri) in pageUris.withIndex()) {
            onProgress(index + 1, pageUris.size, "Processing page ${index + 1} of ${pageUris.size}...")

            try {
                val inputImage = InputImage.fromFilePath(context, uri)
                val mlkitVisionText = latinRecognizer.process(inputImage).await()

                val lineInfos = mutableListOf<OcrLineInfo>()
                for (block in mlkitVisionText.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox
                        val isRtl = ScannedDocumentStructureReconstructor.isArabicText(line.text)
                        lineInfos.add(
                            OcrLineInfo(
                                text = line.text,
                                top = rect?.top ?: 0,
                                left = rect?.left ?: 0,
                                width = rect?.width() ?: 0,
                                height = rect?.height() ?: 0,
                                isRtl = isRtl
                            )
                        )
                    }
                }

                var rawText = mlkitVisionText.text
                val hasArabic = ScannedDocumentStructureReconstructor.isArabicText(rawText)

                // If user selected AI enhancement or if Arabic is detected, use the Central AI Orchestrator
                if (useAiEnhancement && orchestrator != null) {
                    onProgress(index + 1, pageUris.size, "")
                    try {
                        val aiEnhanced = orchestrator.enhanceScannedPageText(rawText)
                        if (aiEnhanced.isNotBlank()) {
                            rawText = aiEnhanced
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep the real OCR text (never a fabricated one) and report the failure.
                        onAiEnhancementFailed(e)
                    }
                }

                val structuredPage = ScannedDocumentStructureReconstructor.reconstructPage(
                    pageIndex = index,
                    imageUriString = uri.toString(),
                    rawText = rawText,
                    lines = lineInfos
                )
                results.add(structuredPage)

            } catch (e: Exception) {
                // Fallback: If ML Kit fails or image cannot be read, provide an editable fallback page
                results.add(
                    ScannedPageData(
                        pageIndex = index,
                        imageUriString = uri.toString(),
                        recognizedTitle = "Scanned Page ${index + 1}",
                        heading = "",
                        rawText = "",
                        paragraphs = listOf("Please review and enter page text here."),
                        bulletPoints = emptyList(),
                        table = null,
                        isArabicDetected = false,
                        confidenceScore = 0.1f
                    )
                )
            }
        }

        results
    }
}
