package com.example

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that existing on-device databases are opened/upgraded by the PRODUCTION database
 * configuration ([SmartBubbleDatabase.build]) without losing any user data.
 *
 * Each historical schema below is a frozen copy of the DDL Room generated for that version
 * (reconstructed from the entity classes at the commit that introduced the version). After
 * opening/migrating, Room validates the resulting schema against the compiled entities, so a
 * fixture that drifts from the real schema makes these tests fail.
 */
@RunWith(RobolectricTestRunner::class)
class SmartBubbleMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private var opened: SmartBubbleDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(dbName)
    }

    // ---------------------------------------------------------------- frozen schemas

    private val documentsDdl =
        "CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`fileType` TEXT NOT NULL, `filePath` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, " +
            "`wordCount` INTEGER NOT NULL, `pageOrSlideCount` INTEGER NOT NULL, `templateId` TEXT, " +
            "`templateName` TEXT, `providerUsed` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`isFavorite` INTEGER NOT NULL, `projectId` TEXT, `summary` TEXT NOT NULL, " +
            "`textContent` TEXT NOT NULL, `isOrchestratorVerified` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private val templatesV1Ddl =
        "CREATE TABLE IF NOT EXISTS `templates` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`fileType` TEXT NOT NULL, `originalFilePath` TEXT NOT NULL, `profileJson` TEXT NOT NULL, " +
            "`isDefaultWord` INTEGER NOT NULL, `isDefaultPpt` INTEGER NOT NULL, " +
            "`isDefaultExcel` INTEGER NOT NULL, `isDefaultPdf` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
            "`compatibilityStatus` TEXT NOT NULL, `thumbnailColorHex` TEXT NOT NULL, PRIMARY KEY(`id`))"

    private val templatesV2Ddl =
        "CREATE TABLE IF NOT EXISTS `templates` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`fileType` TEXT NOT NULL, `originalFilePath` TEXT NOT NULL, `profileJson` TEXT NOT NULL, " +
            "`fileSize` INTEGER NOT NULL, `isDefaultWord` INTEGER NOT NULL, `isDefaultPpt` INTEGER NOT NULL, " +
            "`isDefaultExcel` INTEGER NOT NULL, `isDefaultPdf` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
            "`compatibilityStatus` TEXT NOT NULL, `thumbnailColorHex` TEXT NOT NULL, PRIMARY KEY(`id`))"

    private val knowledgeSourcesV1Ddl =
        "CREATE TABLE IF NOT EXISTS `knowledge_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`sourceType` TEXT NOT NULL, `uriOrUrl` TEXT NOT NULL, `extractedText` TEXT NOT NULL, " +
            "`notebookReference` TEXT, `processingStatus` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    private val providersDdl =
        "CREATE TABLE IF NOT EXISTS `ai_providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`providerType` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `encryptedApiKey` TEXT NOT NULL, " +
            "`modelName` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private val chatMessagesDdl =
        "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
            "`isUser` INTEGER NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
            "`attachedDocId` TEXT, `attachedDocTitle` TEXT, `attachedDocType` TEXT, " +
            "`orchestratorNote` TEXT, PRIMARY KEY(`id`))"

    private val conversationsDdl =
        "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`lastMessagePreview` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    // ---------------------------------------------------------------- helpers

    private fun createRawDatabase(version: Int, vararg ddl: String, populate: (SQLiteDatabase) -> Unit) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            ddl.forEach { raw.execSQL(it) }
            populate(raw)
            raw.version = version
        } finally {
            raw.close()
        }
    }

    private fun SQLiteDatabase.insertRow(table: String, vararg pairs: Pair<String, Any?>) {
        val values = ContentValues()
        pairs.forEach { (k, v) ->
            when (v) {
                null -> values.putNull(k)
                is String -> values.put(k, v)
                is Int -> values.put(k, v)
                is Long -> values.put(k, v)
                is Boolean -> values.put(k, if (v) 1 else 0)
                else -> error("Unsupported value type for $k")
            }
        }
        check(insertOrThrow(table, null, values) != -1L)
    }

    private fun insertDocument(db: SQLiteDatabase, id: String, title: String, path: String) =
        db.insertRow(
            "documents", "id" to id, "title" to title, "fileType" to "DOCX", "filePath" to path,
            "fileSize" to 18_432L, "wordCount" to 312, "pageOrSlideCount" to 4, "templateId" to "tmpl-company-report",
            "templateName" to "Company Report Template", "providerUsed" to "OpenAI", "createdAt" to 1_700_000_000_000L,
            "isFavorite" to true, "projectId" to null, "summary" to "Generated by OpenAI • 18 KB",
            "textContent" to "تقرير ربع سنوي\nQuarterly report body", "isOrchestratorVerified" to true
        )

    private fun insertTemplateV1(db: SQLiteDatabase, id: String, name: String, type: String, path: String, defaultWord: Boolean) =
        db.insertRow(
            "templates", "id" to id, "name" to name, "fileType" to type, "originalFilePath" to path,
            "profileJson" to """{"templateId":"$id","name":"$name"}""", "isDefaultWord" to defaultWord,
            "isDefaultPpt" to false, "isDefaultExcel" to false, "isDefaultPdf" to false,
            "createdAt" to 1_690_000_000_000L, "lastModified" to 1_695_000_000_000L, "version" to 2,
            "compatibilityStatus" to "Compatible", "thumbnailColorHex" to "#1D52D8"
        )

    private fun insertTemplateV2(db: SQLiteDatabase, id: String, name: String, type: String, size: Long, defaultPpt: Boolean) =
        db.insertRow(
            "templates", "id" to id, "name" to name, "fileType" to type, "originalFilePath" to "/data/t/$id.pptx",
            "profileJson" to "{}", "fileSize" to size, "isDefaultWord" to false, "isDefaultPpt" to defaultPpt,
            "isDefaultExcel" to false, "isDefaultPdf" to false, "createdAt" to 1_690_000_000_000L,
            "lastModified" to 1_695_000_000_000L, "version" to 1, "compatibilityStatus" to "Partial",
            "thumbnailColorHex" to "#E05D26"
        )

    private fun insertMessage(db: SQLiteDatabase, id: String, session: String, isUser: Boolean, content: String, ts: Long) =
        db.insertRow(
            "chat_messages", "id" to id, "sessionId" to session, "isUser" to isUser, "content" to content,
            "timestamp" to ts, "attachedDocId" to null, "attachedDocTitle" to null, "attachedDocType" to null,
            "orchestratorNote" to null
        )

    private fun insertProvider(db: SQLiteDatabase, id: String, name: String, isDefault: Boolean) =
        db.insertRow(
            "ai_providers", "id" to id, "name" to name, "providerType" to "OpenAI-compatible",
            "baseUrl" to "https://example.invalid/v1", "encryptedApiKey" to "ZW5jcnlwdGVk", "modelName" to "model-x",
            "isDefault" to isDefault, "status" to "Ready", "createdAt" to 1_690_000_000_000L
        )

    private fun openProduction(): SmartBubbleDatabase =
        SmartBubbleDatabase.build(context, dbName).also { opened = it }

    private fun countRows(db: SmartBubbleDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    // ---------------------------------------------------------------- tests

    /** A user's current (v3) database is opened as-is: nothing is dropped, rewritten or reset. */
    @Test
    fun existingVersion3Database_opensWithAllUserDataIntact() = runBlocking {
        createRawDatabase(
            3, documentsDdl, templatesV2Ddl, providersDdl, chatMessagesDdl, conversationsDdl
        ) { db ->
            insertDocument(db, "doc-1", "تقرير الربع الأول.docx", "/data/generated_docs/a.docx")
            insertDocument(db, "doc-2", "Board Minutes.docx", "/data/generated_docs/b.docx")
            insertTemplateV2(db, "user-tpl-1", "قالب العرض", "PPTX", 2_202_009L, defaultPpt = true)
            insertProvider(db, "p-user", "My Provider", isDefault = true)
            db.insertRow(
                "conversations", "id" to "conv-1", "title" to "خطة المشروع",
                "lastMessagePreview" to "Draft the plan", "updatedAt" to 1_700_000_100_000L,
                "createdAt" to 1_700_000_000_000L
            )
            insertMessage(db, "m-1", "conv-1", true, "Draft the plan", 1_700_000_000_000L)
            insertMessage(db, "m-2", "conv-1", false, "Here is the plan", 1_700_000_100_000L)
        }

        val db = openProduction()

        val docs = db.documentDao().getAllDocuments().first()
        assertEquals(setOf("doc-1", "doc-2"), docs.map { it.id }.toSet())
        assertEquals("تقرير الربع الأول.docx", db.documentDao().getDocumentById("doc-1")?.title)
        assertTrue(db.documentDao().getDocumentById("doc-1")!!.isFavorite)

        val template = db.templateDao().getTemplateById("user-tpl-1")
        assertNotNull(template)
        assertEquals("قالب العرض", template!!.name)
        assertEquals(2_202_009L, template.fileSize)
        assertTrue(template.isDefaultPpt)

        assertEquals("My Provider", db.providerDao().getDefaultProvider()?.name)
        assertEquals("خطة المشروع", db.conversationDao().getConversationById("conv-1")?.title)
        assertEquals(2, db.chatDao().getMessagesForSession("conv-1").first().size)
    }

    /** v3 -> v4: Thesis Builder tables are created; all existing data is kept and the new tables work. */
    @Test
    fun migrateFromVersion3_addsThesisTablesAndKeepsData() = runBlocking {
        createRawDatabase(3, documentsDdl, templatesV2Ddl, providersDdl, chatMessagesDdl, conversationsDdl) { db ->
            insertDocument(db, "doc-1", "Report.docx", "/data/generated_docs/a.docx")
            insertTemplateV2(db, "tpl-1", "Deck", "PPTX", 10L, defaultPpt = true)
            insertProvider(db, "p-1", "Mine", isDefault = true)
            insertMessage(db, "m-1", "conv-1", true, "hello", 1_000L)
        }
        val db = openProduction() // Room validates the migrated schema against the entities
        assertEquals("Report.docx", db.documentDao().getDocumentById("doc-1")?.title)
        assertTrue(db.templateDao().getTemplateById("tpl-1")!!.isDefaultPpt)
        assertEquals("Mine", db.providerDao().getDefaultProvider()?.name)
        assertEquals(1, countRows(db, "chat_messages"))
        assertEquals(0, countRows(db, "thesis_projects"))
        db.thesisDao().insertProject(
            com.example.data.model.ThesisProjectEntity(
                "t-1", "Thesis", "MASTER", "ar", "", "", "", "", "", "", "", "", "", "", null, null, true, null, 0, 1L, 1L
            )
        )
        assertEquals("Thesis", db.thesisDao().project("t-1")?.title)
    }

    /** Data written through Room survives closing and reopening with the production config. */
    @Test
    fun roomCreatedDatabase_survivesReopen() = runBlocking {
        SmartBubbleDatabase.build(context, dbName).apply {
            templateDao().insertTemplate(
                TemplateEntity(
                    id = "t-user", name = "Letterhead", fileType = "DOCX",
                    originalFilePath = "/data/t/letterhead.docx", profileJson = "{}",
                    fileSize = 4096L, isDefaultWord = true
                )
            )
            documentDao().insertDocument(
                DocumentEntity(
                    id = "d-user", title = "Notes.docx", fileType = "DOCX", filePath = "/data/d/notes.docx",
                    fileSize = 1024L, wordCount = 10, pageOrSlideCount = 1, providerUsed = "OpenAI"
                )
            )
            providerDao().insertProvider(
                ProviderEntity(
                    id = "p-1", name = "Custom", providerType = "OpenAI", baseUrl = "https://x.invalid",
                    encryptedApiKey = "k", modelName = "m", isDefault = true
                )
            )
            conversationDao().insertConversation(ConversationEntity(id = "c-1", title = "Chat"))
            chatDao().insertMessage(ChatMessageEntity(id = "m-1", sessionId = "c-1", isUser = true, content = "hi"))
            close()
        }

        val db = openProduction()
        assertEquals("Letterhead", db.templateDao().getTemplateById("t-user")?.name)
        assertEquals("Notes.docx", db.documentDao().getDocumentById("d-user")?.title)
        assertEquals("Custom", db.providerDao().getDefaultProvider()?.name)
        assertEquals("Chat", db.conversationDao().getConversationById("c-1")?.title)
        assertEquals(1, db.chatDao().getMessagesForSession("c-1").first().size)
    }

    /** v1 -> v3: templates gain fileSize, chat history gets conversations, legacy table is kept. */
    @Test
    fun migrateFromVersion1_preservesAllData() = runBlocking {
        createRawDatabase(1, documentsDdl, templatesV1Ddl, knowledgeSourcesV1Ddl, chatMessagesDdl) { db ->
            insertDocument(db, "doc-v1", "Legacy Report.docx", "/data/generated_docs/legacy.docx")
            insertTemplateV1(db, "tpl-v1", "Contract Template", "DOCX", "/data/t/contract.docx", defaultWord = true)
            db.insertRow(
                "knowledge_sources", "id" to "ks-1", "name" to "Policy PDF", "sourceType" to "PDF",
                "uriOrUrl" to "content://docs/policy.pdf", "extractedText" to "Policy text",
                "notebookReference" to null, "processingStatus" to "Synced", "createdAt" to 1_680_000_000_000L
            )
            insertMessage(db, "a1", "session-a", true, "اكتب لي تقريراً عن المبيعات", 1_000L)
            insertMessage(db, "a2", "session-a", false, "إليك التقرير المطلوب", 2_000L)
            insertMessage(db, "b1", "session-b", false, "Welcome!", 5_000L)
        }

        val db = openProduction()

        assertEquals("Legacy Report.docx", db.documentDao().getDocumentById("doc-v1")?.title)

        val tpl = db.templateDao().getTemplateById("tpl-v1")!!
        assertEquals("Contract Template", tpl.name)
        assertTrue(tpl.isDefaultWord)
        assertEquals(0L, tpl.fileSize) // unknown until backfilled from the file at start-up
        assertEquals(2, tpl.version)

        // Unused legacy table and its data are preserved, not dropped.
        assertEquals(1, countRows(db, "knowledge_sources"))

        assertEquals(0, countRows(db, "ai_providers"))

        val a = db.conversationDao().getConversationById("session-a")!!
        assertEquals("اكتب لي تقريراً عن المبيعات", a.title)
        assertEquals("إليك التقرير المطلوب", a.lastMessagePreview)
        assertEquals(1_000L, a.createdAt)
        assertEquals(2_000L, a.updatedAt)

        // A session with no user message gets the generic title (auto-renamed on next prompt).
        assertEquals("New Conversation", db.conversationDao().getConversationById("session-b")!!.title)
        assertEquals(3, countRows(db, "chat_messages"))
    }

    /** v2 -> v3: providers and templates untouched, conversations created from history. */
    @Test
    fun migrateFromVersion2_preservesAllData() = runBlocking {
        createRawDatabase(2, documentsDdl, templatesV2Ddl, providersDdl, chatMessagesDdl) { db ->
            insertDocument(db, "doc-v2", "Q3 Budget.docx", "/data/generated_docs/q3.docx")
            insertTemplateV2(db, "tpl-v2", "Pitch Deck", "PPTX", 2_202_009L, defaultPpt = true)
            insertProvider(db, "p-a", "Primary", isDefault = true)
            insertProvider(db, "p-b", "Secondary", isDefault = false)
            insertMessage(
                db, "x1", "session-x", true,
                "Please write a very long request that is definitely longer than thirty-two characters", 10L
            )
        }

        val db = openProduction()

        assertEquals("Q3 Budget.docx", db.documentDao().getDocumentById("doc-v2")?.title)
        val tpl = db.templateDao().getTemplateById("tpl-v2")!!
        assertEquals(2_202_009L, tpl.fileSize)
        assertTrue(tpl.isDefaultPpt)
        assertEquals(2, db.providerDao().getAllProviders().first().size)
        assertEquals("Primary", db.providerDao().getDefaultProvider()?.name)

        val conv = db.conversationDao().getConversationById("session-x")!!
        assertEquals(32, conv.title.length)
        assertEquals(1, db.chatDao().getMessagesForSession("session-x").first().size)
    }

    /**
     * A database the app cannot migrate (here: a newer version, i.e. a downgrade) must fail
     * loudly and leave the user's data on disk untouched instead of silently wiping it.
     */
    @Test
    fun unmigratableDatabase_failsWithoutDeletingData() {
        createRawDatabase(
            99, documentsDdl, templatesV2Ddl, providersDdl, chatMessagesDdl, conversationsDdl
        ) { db ->
            insertDocument(db, "doc-keep", "Keep me.docx", "/data/generated_docs/keep.docx")
        }

        val db = openProduction()
        try {
            db.openHelper.writableDatabase
            fail("Opening a database without a migration path must throw instead of wiping data")
        } catch (expected: IllegalStateException) {
            // expected: no destructive fallback
        } finally {
            db.close()
            opened = null
        }

        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        raw.use {
            it.rawQuery("SELECT title FROM documents WHERE id = 'doc-keep'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Keep me.docx", c.getString(0))
            }
            assertEquals(99, it.version)
        }
    }
}
