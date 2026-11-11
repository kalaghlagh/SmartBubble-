package com.example.core.storage

import androidx.room.withTransaction
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.TemplateEntity
import com.example.document.docx.DocxEngine
import com.example.document.model.PresentationModel
import com.example.document.model.SheetModel
import com.example.document.model.SlideModel
import com.example.document.model.SpreadsheetModel
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.pptx.PptxEngine
import com.example.document.xlsx.XlsxEngine
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Persistent "sample data has been handled" marker. */
interface SeedFlagStore {
    suspend fun isSeeded(): Boolean
    suspend fun markSeeded()
}

/** Production [SeedFlagStore] backed by DataStore preferences. */
class PreferencesSeedFlagStore(private val preferences: PreferencesManager) : SeedFlagStore {
    override suspend fun isSeeded(): Boolean = preferences.isSampleDataSeeded()
    override suspend fun markSeeded() = preferences.markSampleDataSeeded()
}

/**
 * One-time sample data seeding plus start-up consistency maintenance.
 *
 * Guarantees:
 *  - Samples are inserted at most once per install (persistent [SeedFlagStore] flag).
 *  - Only sample TEMPLATES (real reference files) are created; no AI providers or chat content.
 *  - A sample is never inserted over an existing record (insert-if-absent).
 *  - A sample template is only marked default if no template already holds that slot, so
 *    seeding can never create a second default.
 *  - Existing duplicate defaults (left by older versions that re-seeded on every launch) are
 *    collapsed to a single default per type, preferring the user's own template.
 */
class SampleDataSeeder(
    private val filesDir: File,
    private val database: SmartBubbleDatabase,
    private val flagStore: SeedFlagStore
) {

    /** Runs seeding and maintenance. Must be called off the main thread. */
    suspend fun runStartupTasks() = startupMutex.withLock {
        seedIfNeeded()
        repairDuplicateDefaults()
        backfillTemplateFileSizes()
    }

    internal suspend fun seedIfNeeded() {
        if (flagStore.isSeeded()) return

        val templateDir = File(filesDir, "templates")
        val existingRows = database.templateDao().countTemplates() +
            database.providerDao().countProviders() +
            database.conversationDao().countConversations() +
            database.documentDao().countDocuments() +
            database.chatDao().countMessages()

        if (SampleDataPolicy.shouldInsertSamples(false, templateDir.exists(), existingRows)) {
            templateDir.mkdirs()
            val sampleFiles = writeSampleTemplateFiles(templateDir)
            database.withTransaction { insertSamplesIfAbsent(sampleFiles) }
        }
        // Mark only after the samples are committed; an interrupted first launch retries.
        flagStore.markSeeded()
    }

    internal suspend fun repairDuplicateDefaults() {
        val dao = database.templateDao()
        database.withTransaction {
            val all = dao.getAllTemplatesOnce()
            for (slot in DefaultSlot.values()) {
                val holders = all.filter { SampleDataPolicy.isDefault(it, slot) }
                if (holders.size <= 1) continue
                val keep = SampleDataPolicy.chooseDefaultToKeep(holders) ?: continue
                holders.filter { it.id != keep.id }.forEach { setFlag(it.id, slot, false) }
            }
        }
    }

    /** Rows migrated from schema v1 have fileSize = 0; fill in the real size from the file. */
    internal suspend fun backfillTemplateFileSizes() {
        val dao = database.templateDao()
        dao.getAllTemplatesOnce()
            .filter { it.fileSize <= 0L && it.originalFilePath.isNotBlank() }
            .forEach { t ->
                val f = File(t.originalFilePath)
                if (f.isFile && f.length() > 0L) dao.updateFileSize(t.id, f.length())
            }
    }

    private suspend fun setFlag(id: String, slot: DefaultSlot, value: Boolean) {
        val dao = database.templateDao()
        when (slot) {
            DefaultSlot.WORD -> dao.setDefaultWordFlag(id, value)
            DefaultSlot.PPT -> dao.setDefaultPptFlag(id, value)
            DefaultSlot.EXCEL -> dao.setDefaultExcelFlag(id, value)
        }
    }

    private data class SampleFiles(val docx: File, val pptx: File, val xlsx: File)

    private fun writeSampleTemplateFiles(templateDir: File): SampleFiles {
        val docx = File(templateDir, "Company_Report_Template.docx")
        if (!docx.exists()) {
            DocxEngine().generateDocx(
                WordDocumentModel(
                    title = "Corporate Executive Report",
                    subtitle = "Standard Enterprise Template Format",
                    sections = listOf(
                        WordSection(
                            heading = "Executive Summary",
                            paragraphs = listOf("This reference document defines standard margins, header/footer branding, and typography for enterprise reports.")
                        )
                    )
                ),
                docx,
                wordProfile()
            )
        }
        val pptx = File(templateDir, "Presentation_Theme.pptx")
        if (!pptx.exists()) {
            PptxEngine().generatePptx(
                PresentationModel(
                    title = "Presentation Theme Deck",
                    subtitle = "16:9 Standard Corporate Slide Master",
                    slides = listOf(
                        SlideModel(
                            title = "Theme Architecture",
                            bulletPoints = listOf("Master layout placeholders", "Brand color accents")
                        )
                    )
                ),
                pptx,
                pptProfile()
            )
        }
        val xlsx = File(templateDir, "Business_Data_Template.xlsx")
        if (!xlsx.exists()) {
            XlsxEngine().generateXlsx(
                SpreadsheetModel(
                    workbookTitle = "Business Data Workbook",
                    sheets = listOf(
                        SheetModel(
                            name = "Data Summary",
                            headers = listOf("Category", "Target", "Actual", "Performance"),
                            rows = listOf(
                                listOf("Revenue", 100000, 105000, "+5%"),
                                listOf("Efficiency", 95, 98, "+3%")
                            )
                        )
                    )
                ),
                xlsx,
                excelProfile()
            )
        }
        return SampleFiles(docx, pptx, xlsx)
    }

    private fun wordProfile() = TemplateProfile.defaultProfile().copy(
        templateId = SampleDataPolicy.SAMPLE_WORD_TEMPLATE_ID,
        name = "Company Report Template",
        originalFileType = "DOCX",
        primaryColorHex = "#1D52D8",
        headingFont = "Segoe UI Semibold"
    )

    private fun pptProfile() = TemplateProfile.defaultProfile().copy(
        templateId = SampleDataPolicy.SAMPLE_PPT_TEMPLATE_ID,
        name = "Presentation Theme",
        originalFileType = "PPTX",
        primaryColorHex = "#E05D26"
    )

    private fun excelProfile() = TemplateProfile.defaultProfile().copy(
        templateId = SampleDataPolicy.SAMPLE_EXCEL_TEMPLATE_ID,
        name = "Business Data Template",
        originalFileType = "XLSX",
        primaryColorHex = "#107C41"
    )

    private suspend fun insertSamplesIfAbsent(files: SampleFiles) {
        val templateDao = database.templateDao()

        // No sample AI providers are created: the user adds real providers (with their own API
        // keys) in Settings. Earlier versions seeded providers with fake keys and statuses.

        // Sample templates: insert-if-absent; default only when the slot is free.
        val word = wordProfile()
        if (templateDao.getTemplateById(word.templateId) == null) {
            templateDao.insertTemplate(
                TemplateEntity(
                    id = word.templateId,
                    name = word.name,
                    fileType = "DOCX",
                    originalFilePath = files.docx.absolutePath,
                    fileSize = if (files.docx.exists()) files.docx.length() else 250880L,
                    profileJson = word.toJson(),
                    isDefaultWord = templateDao.getDefaultWordTemplate() == null,
                    compatibilityStatus = "Compatible",
                    thumbnailColorHex = "#1D52D8"
                )
            )
        }
        val ppt = pptProfile()
        if (templateDao.getTemplateById(ppt.templateId) == null) {
            templateDao.insertTemplate(
                TemplateEntity(
                    id = ppt.templateId,
                    name = ppt.name,
                    fileType = "PPTX",
                    originalFilePath = files.pptx.absolutePath,
                    fileSize = if (files.pptx.exists()) files.pptx.length() else 2202009L,
                    profileJson = ppt.toJson(),
                    isDefaultPpt = templateDao.getDefaultPptTemplate() == null,
                    compatibilityStatus = "Compatible",
                    thumbnailColorHex = "#E05D26"
                )
            )
        }
        val excel = excelProfile()
        if (templateDao.getTemplateById(excel.templateId) == null) {
            templateDao.insertTemplate(
                TemplateEntity(
                    id = excel.templateId,
                    name = excel.name,
                    fileType = "XLSX",
                    originalFilePath = files.xlsx.absolutePath,
                    fileSize = if (files.xlsx.exists()) files.xlsx.length() else 184320L,
                    profileJson = excel.toJson(),
                    isDefaultExcel = templateDao.getDefaultExcelTemplate() == null,
                    compatibilityStatus = "Compatible",
                    thumbnailColorHex = "#107C41"
                )
            )
        }
        // No sample conversation is created: it contained a pre-written "AI" answer, and the
        // chat must only ever show real provider responses.
    }

    private companion object {
        /** Serializes start-up tasks within the process (e.g. a ViewModel recreated quickly). */
        val startupMutex = Mutex()
    }
}
