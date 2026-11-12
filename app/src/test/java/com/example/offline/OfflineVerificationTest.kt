package com.example.offline

import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.offline.document.OfflineDocumentGenerator
import com.example.offline.document.OfflineDraft
import com.example.offline.document.OfflineFormat
import com.example.offline.document.OfflineFormattingController
import com.example.offline.document.OfflineLibrary
import com.example.offline.document.OfflinePhase
import com.example.offline.document.TemplateMatch
import com.example.offline.document.TemplateMatcher
import com.example.offline.document.TemplateRepository
import com.example.offline.document.TemplateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Checks that the Offline Formatting Agent cannot reach the network, an AI provider or stored
 * API keys.
 *
 * What this verifies (JVM, not a device):
 *  - static: no source file of the agent imports or references networking classes, the AI
 *    provider layer, the orchestrator, or credential storage;
 *  - runtime: the whole pipeline (analysis, every Office format, preview, controller flow)
 *    runs while every network connection attempt through the JVM's proxy selector is recorded,
 *    and none is attempted.
 * It does not replace a test on a device in airplane mode, which was not performed here.
 */
class OfflineVerificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun offlineSources(): List<File> {
        val dir = listOf("src/main/java/com/example/offline", "app/src/main/java/com/example/offline")
            .map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("Offline agent sources not found from ${File(".").absolutePath}")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Code without comments, so documentation that names what is NOT used does not count. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun sources_doNotUseNetworkAiProvidersOrCredentials() {
        val forbidden = listOf(
            "okhttp3", "java.net.", "javax.net.", "android.net.", "HttpURLConnection", "URLConnection",
            "com.example.ai.", "CentralAiOrchestrator", "ProviderManager", "AiProviderClient", "AiHttp",
            "CredentialStore", "KeystoreHelper", "encryptedApiKey", "ClipboardManager", "getPrimaryClip"
        )
        val sources = offlineSources()
        assertTrue("expected the agent's sources", sources.size >= 10)
        val violations = sources.flatMap { f -> forbidden.filter { code(f).contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    private class RecordingProxySelector : ProxySelector() {
        val requests = CopyOnWriteArrayList<URI>()
        override fun select(uri: URI): List<Proxy> {
            requests.add(uri)
            throw IOException("Network disabled for the offline test: $uri")
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    @Test
    fun wholePipeline_makesNoNetworkRequest() = runBlocking {
        val previous = ProxySelector.getDefault()
        val recorder = RecordingProxySelector()
        ProxySelector.setDefault(recorder)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = """
                MEETING MINUTES

                Attendees: Sara, Karim, Paul

                Decisions
                1. Budget approved: 12,500 EUR
                2. Launch on 01/07/2025

                | Action | Owner |
                |---|---|
                | Draft plan | Sara |
                | Review | Karim |

                Prochaine réunion le 15 juin. الاجتماع القادم في ١٥ يونيو.
            """.trimIndent() + "\n\n" + "Notes recorded during the meeting for the archive. ".repeat(10)

            val result = RuleBasedFormattingEngine().analyze(text)
            assertTrue(result.preservation.isLossless)
            val builtIn = TemplateMatch(TemplateSource.BUILT_IN, null, TemplateMatcher.builtInProfile(), null, emptyList())
            val generator = OfflineDocumentGenerator(tmp.newFolder("drafts"))
            for (format in listOf(OfflineFormat.DOCX, OfflineFormat.PPTX, OfflineFormat.XLSX)) {
                val draft = generator.generate(result, format, builtIn)
                assertTrue(draft.file.length() > 0)
            }
            PreviewBuilder.build(result)

            // Controller flow, with no AI provider, credential store or network client anywhere.
            val repo = object : TemplateRepository {
                override suspend fun findById(id: String): TemplateEntity? = null
                override suspend fun defaultFor(templateType: String): TemplateEntity? = null
                override suspend fun all(): List<TemplateEntity> = emptyList()
            }
            val saved = CopyOnWriteArrayList<OfflineDraft>()
            val library = OfflineLibrary { d ->
                saved.add(d)
                DocumentEntity(id = "x", title = d.title, fileType = d.format.name, filePath = d.file.path, fileSize = 1, wordCount = 1, pageOrSlideCount = 1, providerUsed = "Offline")
            }
            val c = OfflineFormattingController(scope, TemplateMatcher(repo), OfflineDocumentGenerator(tmp.newFolder("flow")), library)
            c.onComposerTextChanged("", text, inputEnabled = true)
            c.formatOffline(null)
            withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.PREVIEW } }
            c.applyFormatting()
            withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.READY } }
            c.saveToLibrary()
            withTimeout(10_000) { c.state.first { it.phase == OfflinePhase.IDLE } }
            assertEquals(1, saved.size)

            assertEquals("network requests were attempted: ${recorder.requests}", 0, recorder.requests.size)
        } finally {
            scope.cancel()
            ProxySelector.setDefault(previous)
        }
    }
}
