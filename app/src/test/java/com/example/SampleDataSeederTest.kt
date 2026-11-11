package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.storage.SampleDataPolicy
import com.example.core.storage.SampleDataSeeder
import com.example.core.storage.SeedFlagStore
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ConversationEntity
import com.example.data.model.TemplateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Sample data seeding against a real Room database. "Restart" is simulated by creating a new
 * [SampleDataSeeder] over the same database, files directory and persisted flag.
 */
@RunWith(RobolectricTestRunner::class)
class SampleDataSeederTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: SmartBubbleDatabase
    private lateinit var filesDir: File
    private val flag = InMemoryFlag()

    private class InMemoryFlag : SeedFlagStore {
        var seeded = false
        override suspend fun isSeeded() = seeded
        override suspend fun markSeeded() { seeded = true }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = tmp.newFolder("files")
    }

    @After
    fun tearDown() = db.close()

    private fun newSeeder() = SampleDataSeeder(filesDir, db, flag)

    private suspend fun defaultsPerSlot(): Triple<Int, Int, Int> {
        val all = db.templateDao().getAllTemplatesOnce()
        return Triple(all.count { it.isDefaultWord }, all.count { it.isDefaultPpt }, all.count { it.isDefaultExcel })
    }

    @Test
    fun freshInstall_seedsSamplesOnceWithSingleDefaults() = runBlocking {
        newSeeder().runStartupTasks()

        assertTrue(flag.seeded)
        assertEquals(3, db.templateDao().countTemplates())
        // No fake AI providers (keys/statuses) and no pre-written AI conversation are seeded.
        assertEquals(0, db.providerDao().countProviders())
        assertNull(db.conversationDao().getConversationById(SampleDataPolicy.SAMPLE_CONVERSATION_ID))
        assertEquals(0, db.chatDao().countMessages())
        assertEquals(Triple(1, 1, 1), defaultsPerSlot())
        assertTrue(File(filesDir, "templates/Company_Report_Template.docx").length() > 0)

        // Second run in the same session changes nothing.
        newSeeder().runStartupTasks()
        assertEquals(3, db.templateDao().countTemplates())
    }

    @Test
    fun deletedSamples_areNotRecreatedAfterRestart() = runBlocking {
        newSeeder().runStartupTasks()

        db.templateDao().deleteTemplateById(SampleDataPolicy.SAMPLE_WORD_TEMPLATE_ID)

        newSeeder().runStartupTasks() // simulated app restart
        newSeeder().runStartupTasks() // and another one

        assertNull(db.templateDao().getTemplateById(SampleDataPolicy.SAMPLE_WORD_TEMPLATE_ID))
        assertEquals(2, db.templateDao().countTemplates())
    }

    @Test
    fun deletingEverything_doesNotBringSamplesBack() = runBlocking {
        newSeeder().runStartupTasks()
        db.templateDao().getAllTemplatesOnce().forEach { db.templateDao().deleteTemplate(it) }

        newSeeder().runStartupTasks()

        assertEquals(0, db.templateDao().countTemplates())
        assertEquals(0, db.providerDao().countProviders())
    }

    @Test
    fun upgradeFromOlderVersion_withoutFlag_neverReseeds() = runBlocking {
        // Older versions created this directory on every launch.
        File(filesDir, "templates").mkdirs()
        db.conversationDao().insertConversation(ConversationEntity(id = "user-conv", title = "Mine"))

        newSeeder().runStartupTasks()

        assertTrue(flag.seeded)
        assertEquals(0, db.templateDao().countTemplates())
        assertEquals(0, db.providerDao().countProviders())
        assertNull(db.conversationDao().getConversationById(SampleDataPolicy.SAMPLE_CONVERSATION_ID))
        assertEquals("Mine", db.conversationDao().getConversationById("user-conv")?.title)
    }

    @Test
    fun legacyDuplicateDefaults_areCollapsedPreferringUserTemplate() = runBlocking {
        flag.seeded = true
        val dao = db.templateDao()
        // State produced by the old "re-seed on every launch" bug: sample and user both default.
        dao.insertTemplate(
            TemplateEntity(
                id = SampleDataPolicy.SAMPLE_PPT_TEMPLATE_ID, name = "Presentation Theme", fileType = "PPTX",
                originalFilePath = "", profileJson = "{}", isDefaultPpt = true, lastModified = 9_000
            )
        )
        dao.insertTemplate(
            TemplateEntity(
                id = "user-deck", name = "عرض الشركة", fileType = "PPTX",
                originalFilePath = "", profileJson = "{}", isDefaultPpt = true, lastModified = 1_000
            )
        )
        dao.insertTemplate(
            TemplateEntity(
                id = "user-doc", name = "Letter", fileType = "DOCX",
                originalFilePath = "", profileJson = "{}", isDefaultWord = true
            )
        )

        newSeeder().runStartupTasks()

        assertTrue(dao.getTemplateById("user-deck")!!.isDefaultPpt)
        assertFalse(dao.getTemplateById(SampleDataPolicy.SAMPLE_PPT_TEMPLATE_ID)!!.isDefaultPpt)
        assertTrue(dao.getTemplateById("user-doc")!!.isDefaultWord)
        assertEquals("عرض الشركة", dao.getTemplateById("user-deck")!!.name)
        assertEquals(Triple(1, 1, 0), defaultsPerSlot())
    }

    @Test
    fun migratedTemplatesWithUnknownSize_getRealFileSize() = runBlocking {
        flag.seeded = true
        val file = File(filesDir, "legacy.docx").apply { writeBytes(ByteArray(1234)) }
        db.templateDao().insertTemplate(
            TemplateEntity(
                id = "legacy", name = "Legacy", fileType = "DOCX",
                originalFilePath = file.absolutePath, profileJson = "{}", fileSize = 0L
            )
        )

        newSeeder().runStartupTasks()

        assertEquals(1234L, db.templateDao().getTemplateById("legacy")!!.fileSize)
    }
}
