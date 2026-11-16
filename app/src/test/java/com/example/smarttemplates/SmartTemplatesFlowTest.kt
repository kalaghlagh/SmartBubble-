package com.example.smarttemplates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.orchestrator.CentralAiOrchestrator
import com.example.ai.orchestrator.DocumentFormat
import com.example.ai.orchestrator.SmartTemplateOptions
import com.example.ai.providers.AiProviderType
import com.example.ai.providers.Json
import com.example.ai.providers.OpenAiClient
import com.example.ai.providers.ProviderManager
import com.example.core.delivery.DeliveryManager
import com.example.core.security.CredentialStore
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/**
 * Smart Templates end to end in Chat: detection, template selection (defaults, overrides),
 * the request to the provider, the document engines and the Library, with real files checked.
 * The provider is the app's real OpenAI-compatible client talking HTTP to a local server that
 * plays the AI (CI has no API key); everything after the HTTP response is the production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class SmartTemplatesFlowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase
    private lateinit var service: SmartTemplateService
    private lateinit var orchestrator: CentralAiOrchestrator
    private lateinit var server: HttpServer
    /** System prompt of every request the "AI" received. */
    private val requests = CopyOnWriteArrayList<String>()

    private val letterJson = """{"title":"Recommendation letter","letter":{"sender":["Dr. Karim Haddad","Department of Geology"],"recipient":["Admissions Committee"],"place":"Algiers","date":"16 November 2026","subject":"Subject: Recommendation for Ms. Amina Belkacem","salutation":"Dear members of the committee,","closing":"Yours sincerely,","signature_name":"Dr. Karim Haddad","signature_title":"Professor of Geology"},"sections":[{"heading":"","paragraphs":["I am pleased to recommend Ms. Amina Belkacem, a Master's student in Geology.","Her field work on groundwater was remarkable."]}]}"""
    private val leaveJson = """{"title":"طلب إجازة","letter":{"sender":["أمينة بلقاسم"],"recipient":["السيد المدير"],"place":"الجزائر","date":"16 نوفمبر 2026","subject":"الموضوع: طلب إجازة","salutation":"تحية طيبة وبعد،","closing":"وتقبلوا فائق الاحترام","signature_name":"أمينة بلقاسم","signature_title":"","show_title":true},"sections":[{"heading":"","paragraphs":["أتقدم إليكم بطلب إجازة لمدة أسبوع ابتداء من الأحد القادم."]}]}"""
    private val inventoryJson = """{"title":"Inventory","sheets":[{"name":"Stock","headers":["Item","Quantity","Unit price","Value"],"rows":[["Drill bits",40,2.5,100],["Core boxes",12,18,216],["Gloves",60,1.2,72]],"total_label":"Total","total_formula":"SUM(D2:D4)"}]}"""
    private val slidesJson = """{"title":"Renewable energy","subtitle":"An overview","slides":[{"title":"Why renewables","bullets":["Lower emissions","Energy security"]},{"title":"Solar","bullets":["Photovoltaics","Thermal"],"metric":"45%","metric_label":"Cost drop since 2015"},{"title":"Wind","bullets":["Onshore","Offshore"]}]}"""

    private fun answerFor(system: String, conversation: String): String = when {
        system.contains("an Excel workbook") -> inventoryJson
        system.contains("a PowerPoint presentation") -> slidesJson
        conversation.contains("إجازة") -> leaveJson
        else -> letterJson
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        service = SmartTemplateService(db)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = Json.obj(Json.parse(exchange.requestBody.readBytes().toString(Charsets.UTF_8))).orEmpty()
            val messages = (Json.arr(body["messages"]) ?: emptyList()).mapNotNull { Json.obj(it) }
            val system = messages.firstOrNull { it["role"] == "system" }?.get("content") as? String ?: ""
            val conversation = messages.filter { it["role"] != "system" }.joinToString("\n") { it["content"] as? String ?: "" }
            requests.add(system)
            val content = answerFor(system, conversation)
            val chunk = Json.write(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to content), "finish_reason" to "stop"))))
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("data: $chunk\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)) }
        }
        server.start()
        val provider = ProviderEntity("p1", "Local test AI", "OpenAI-compatible", "http://127.0.0.1:${server.address.port}/v1", "", "test-model", isDefault = true)
        val client = OpenAiClient(AiProviderType.OPENAI_COMPATIBLE, provider.baseUrl, "test-key", "test-model")
        orchestrator = CentralAiOrchestrator(context, db, DeliveryManager(context) { context.getString(it) }, ProviderManager(db, CredentialStore(context))) { provider to client }
    }

    @After
    fun tearDown() {
        server.stop(0)
        db.close()
    }

    private fun chat(text: String, autoFormat: Boolean = true, override: String? = null, forced: String? = null) = runBlocking {
        orchestrator.handleChatTurn(emptyList(), text, "en", null, null, smart = SmartTemplateOptions(service, autoFormat, forcedSubcategory = forced, overrideTemplateId = override))
    }

    private fun part(file: String, name: String) = ZipFile(file).use { z -> z.getInputStream(z.getEntry(name)).bufferedReader().readText() }

    @Test
    fun recommendationLetter_detected_usesTheUsersDefault_andIsSavedWithItsContent() = runBlocking {
        service.setDefault("letters.recommendation", "builtin.letters.formal")
        val result = chat("I need a recommendation letter for a Master's student in Geology.")
        // Detected locally: one request to the provider (the document writer), no planner request.
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("recommendation letter"))
        assertTrue(requests.single().contains("\"letter\""))
        assertEquals("letters.recommendation", result.intent?.subcategoryId)
        assertEquals(SelectionSource.SUBCATEGORY_DEFAULT, result.template?.source)
        val doc = result.document!!
        assertEquals("DOCX", doc.fileType)
        assertEquals("builtin.letters.formal", doc.templateId)
        assertEquals("letters.recommendation", doc.documentKind)
        assertEquals("en", doc.documentLanguage)
        assertNotNull(doc.contentJson)
        assertEquals("I need a recommendation letter for a Master's student in Geology.", doc.sourceRequest)
        // In the Library.
        assertEquals(doc, db.documentDao().getDocumentById(doc.id))
        // A real Word file with the letter laid out and the template's formatting.
        val xml = part(doc.filePath, "word/document.xml")
        for (t in listOf("Dr. Karim Haddad", "Admissions Committee", "Algiers, 16 November 2026", "Subject: Recommendation for Ms. Amina Belkacem",
            "Dear members of the committee,", "I am pleased to recommend Ms. Amina Belkacem", "Yours sincerely,", "Professor of Geology")) assertTrue("missing $t", xml.contains(t))
        assertTrue("A4", xml.contains("""<w:pgSz w:w="11900" w:h="16840"/>"""))
        assertTrue("page border of the formal style", xml.contains("<w:pgBorders"))
        val styles = part(doc.filePath, "word/styles.xml")
        assertTrue("Cambria body font", styles.contains("w:ascii=\"Cambria\""))
        assertTrue("1.5 line spacing", styles.contains("w:line=\"360\""))
    }

    @Test
    fun changeTemplate_rendersAgainWithoutAI_andKeepsTheDefault() = runBlocking {
        service.setDefault("letters.recommendation", "builtin.letters.formal")
        val doc = chat("Write a recommendation letter for my student").document!!
        val before = requests.size
        val modern = orchestrator.rerenderDocument(doc, DocumentFormat.DOCX, "builtin.letters.modern", service)
        assertEquals(before, requests.size) // no AI request
        assertEquals("builtin.letters.modern", modern.templateId)
        assertTrue(part(modern.filePath, "word/styles.xml").contains("w:ascii=\"Calibri\""))
        assertTrue(part(modern.filePath, "word/document.xml").contains("Dr. Karim Haddad"))
        // The saved default did not change.
        assertEquals("builtin.letters.formal", service.defaults()["letters.recommendation"])
        // The original is still in the Library; the new file is a new entry.
        assertNotNull(db.documentDao().getDocumentById(doc.id))
        assertTrue(modern.id != doc.id && File(modern.filePath).isFile)
    }

    @Test
    fun excelInventory_usesTheBestBuiltInSheet() = runBlocking {
        val result = chat("Create an Excel inventory sheet.")
        assertEquals("spreadsheets.inventory", result.intent?.subcategoryId)
        val doc = result.document!!
        assertEquals("XLSX", doc.fileType)
        assertEquals("builtin.spreadsheets.clean", doc.templateId)
        assertTrue(requests.single().contains("an Excel workbook"))
        val sheet = part(doc.filePath, "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("Drill bits"))
        assertTrue("formula", sheet.contains("<f>SUM(D2:D4)</f>"))
        assertTrue("frozen header", sheet.contains("state=\"frozen\""))
        assertTrue("filters", sheet.contains("<autoFilter ref=\"A1:D4\"/>"))
        val styles = part(doc.filePath, "xl/styles.xml")
        assertTrue("teal header of the clean style", styles.contains("FF0F766E"))
        assertTrue("banded rows", styles.contains("FFF0FDFA"))
    }

    @Test
    fun renewableEnergyPresentation_isScientific_withTheAcademicTheme() = runBlocking {
        val result = chat("Prepare a PowerPoint presentation about renewable energy.")
        assertEquals("presentations.scientific", result.intent?.subcategoryId)
        val doc = result.document!!
        assertEquals("PPTX", doc.fileType)
        assertEquals("builtin.presentations.academic", doc.templateId)
        ZipFile(doc.filePath).use { z ->
            val slides = z.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            assertEquals(4, slides.size) // title slide + 3
            assertTrue(z.getInputStream(z.getEntry("ppt/theme/theme1.xml")).bufferedReader().readText().contains("<a:accent1><a:srgbClr val=\"1F3A5F\"/>"))
            val slide2 = z.getInputStream(z.getEntry("ppt/slides/slide2.xml")).bufferedReader().readText()
            assertTrue(slide2.contains("Why renewables"))
            assertTrue("academic side bar", slide2.contains("name=\"Side bar\""))
        }
    }

    @Test
    fun arabicLeaveRequest_isRightToLeft_withATitle() = runBlocking {
        val result = chat("اكتب طلب إجازة")
        assertEquals("requests.leave", result.intent?.subcategoryId)
        assertEquals("ar", result.intent?.language)
        val doc = result.document!!
        assertEquals("builtin.requests.formal", doc.templateId)
        val xml = part(doc.filePath, "word/document.xml")
        assertTrue(xml.contains("أتقدم إليكم بطلب إجازة"))
        assertTrue("right-to-left section", xml.contains("<w:bidi/></w:sectPr>"))
        assertTrue("centered title", xml.contains("طلب إجازة"))
    }

    @Test
    fun unclearRequest_isAskedAbout_withoutAnyAIRequest() {
        val result = chat("Write my thesis on groundwater")
        assertNotNull(result.clarification)
        assertNull(result.document)
        assertEquals(0, requests.size)
        assertTrue(result.clarification!!.choices.contains("academic.master"))
        // The answer continues the original request.
        val answered = chat("Write my thesis on groundwater", forced = "academic.master")
        assertEquals("academic.master", answered.document?.documentKind)
        assertEquals("builtin.academic.formal", answered.document?.templateId)
    }

    @Test
    fun autoFormatOff_givesNeutralFormatting_andAsksForATemplate() {
        val result = chat("Write a business letter to our supplier", autoFormat = false)
        assertTrue(result.needsTemplateChoice)
        assertNull(result.document!!.templateId)
        assertEquals("letters.business", result.document!!.documentKind)
        // Picking a template later: only that document changes.
        val formatted = runBlocking { orchestrator.rerenderDocument(result.document!!, DocumentFormat.DOCX, "builtin.letters.modern", service) }
        assertEquals("builtin.letters.modern", formatted.templateId)
        assertTrue(runBlocking { service.defaults() }.isEmpty())
    }

    @Test
    fun manualOverride_isUsedOnce_andAnUploadedDefaultWins() = runBlocking {
        // An uploaded Word template classified as a recommendation letter and made its default.
        val file = File(context.filesDir, "tpl.docx")
        com.example.document.docx.DocxEngine().generateDocx(com.example.document.model.WordDocumentModel("Letterhead"), file)
        db.templateDao().insertTemplate(TemplateEntity("u1", "Letterhead", "DOCX", file.path, com.example.template.domain.TemplateProfile.defaultProfile().toJson()))
        service.classify("u1", "letters", "letters.recommendation")
        service.setDefault("letters.recommendation", "u1")
        assertEquals("u1", chat("Write a recommendation letter").document!!.templateId)
        // Override for one document.
        assertEquals("builtin.letters.classic", chat("Write a recommendation letter", override = "builtin.letters.classic").document!!.templateId)
        assertEquals("u1", service.defaults()["letters.recommendation"])
        // Deleting the uploaded template removes its default; the built-in best match applies again.
        service.forget("u1")
        db.templateDao().deleteTemplateById("u1")
        assertEquals("builtin.letters.classic", chat("Write a recommendation letter").document!!.templateId)
    }

    @Test
    fun library_listsBuiltInAndUploaded_withFavoritesAndDefaults_andRejectsMismatches() = runBlocking {
        db.templateDao().insertTemplate(TemplateEntity("u2", "Deck", "PPTX", "/none.pptx", "{}"))
        service.setFavorite("builtin.reports.modern", true)
        service.setDefault("reports", "builtin.reports.minimal")
        val lib = service.libraryOnce()
        assertEquals(BuiltInTemplates.all.size + 1, lib.size)
        assertTrue(lib.single { it.id == "builtin.reports.modern" }.favorite)
        assertEquals(listOf("reports"), lib.single { it.id == "builtin.reports.minimal" }.defaultFor)
        assertTrue(lib.single { it.id == "u2" }.appliesTo("presentations.business"))
        assertFalse(lib.single { it.id == "u2" }.appliesTo("letters.business"))
        // A PowerPoint template cannot become the default of letters; built-ins are never classified.
        assertTrue(runCatching { service.setDefault("letters", "u2") }.isFailure)
        assertTrue(runCatching { service.classify("builtin.letters.classic", "reports", null) }.isFailure)
        // Category default applies to a subcategory without its own default.
        assertEquals(SelectionSource.CATEGORY_DEFAULT, service.resolve("reports.financial", "DOCX").source)
        assertEquals("builtin.reports.minimal", service.resolve("reports.financial", "DOCX").templateId)
    }
}
