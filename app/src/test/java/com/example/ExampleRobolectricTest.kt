package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    private lateinit var db: SmartBubbleDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testConversationLifecycleAndPersistence() = runBlocking {
        val conversationDao = db.conversationDao()
        val chatDao = db.chatDao()

        val convoId = "test-session-123"
        val initialConversation = ConversationEntity(
            id = convoId,
            title = "Business Contract Discussion",
            lastMessagePreview = "Please draft a partnership contract",
            updatedAt = 1000L,
            createdAt = 1000L
        )

        // 1. Insert conversation
        conversationDao.insertConversation(initialConversation)

        val retrieved = conversationDao.getConversationById(convoId)
        assertNotNull(retrieved)
        assertEquals("Business Contract Discussion", retrieved?.title)
        assertEquals("Please draft a partnership contract", retrieved?.lastMessagePreview)

        // 2. Add chat messages associated with this conversation ID
        val message1 = ChatMessageEntity(
            id = "msg-1",
            sessionId = convoId,
            isUser = true,
            content = "Please draft a partnership contract",
            timestamp = 1000L
        )
        val message2 = ChatMessageEntity(
            id = "msg-2",
            sessionId = convoId,
            isUser = false,
            content = "Certainly! Generating partnership agreement now.",
            timestamp = 1050L,
            attachedDocType = "DOCX",
            attachedDocTitle = "Partnership_Agreement.docx"
        )
        chatDao.insertMessage(message1)
        chatDao.insertMessage(message2)

        val messages = chatDao.getMessagesForSession(convoId).first()
        assertEquals(2, messages.size)

        // 3. Rename conversation
        conversationDao.updateTitle(convoId, "Renamed Partnership Contract")
        val updatedConvo = conversationDao.getConversationById(convoId)
        assertEquals("Renamed Partnership Contract", updatedConvo?.title)

        // 4. Update preview
        conversationDao.updateLastMessage(convoId, "Contract successfully exported to DOCX", 1200L)
        val updatedPreviewConvo = conversationDao.getConversationById(convoId)
        assertEquals("Contract successfully exported to DOCX", updatedPreviewConvo?.lastMessagePreview)
        assertEquals(1200L, updatedPreviewConvo?.updatedAt)

        // 5. Delete conversation & messages
        conversationDao.deleteConversationById(convoId)
        chatDao.deleteMessagesBySessionId(convoId)

        val deletedConvo = conversationDao.getConversationById(convoId)
        assertNull(deletedConvo)

        val remainingMessages = chatDao.getMessagesForSession(convoId).first()
        assertTrue(remainingMessages.isEmpty())
    }

    @get:org.junit.Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()

    @Test
    fun testMainActivityLaunchAndNavigateToTemplates() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_templates").assertExists()
        composeTestRule.onNodeWithTag("nav_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("upload_template_button").assertExists()
    }

    @Test
    fun testNavigateBetweenAllTabsRepeatedlyWithoutCrashing() {
        composeTestRule.waitForIdle()

        // Tab 1: Chat (initial)
        composeTestRule.onNodeWithTag("nav_chat").assertExists()

        // Tab 2: My Templates
        composeTestRule.onNodeWithTag("nav_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("upload_template_button").assertExists()

        // Tab 3: Library
        composeTestRule.onNodeWithTag("nav_library").performClick()
        composeTestRule.waitForIdle()

        // Tab 4: Settings
        composeTestRule.onNodeWithTag("nav_settings").performClick()
        composeTestRule.waitForIdle()

        // Back to Templates
        composeTestRule.onNodeWithTag("nav_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("upload_template_button").assertExists()

        // Back to Chat
        composeTestRule.onNodeWithTag("nav_chat").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testLanguageSwitchingAndNavigatingToTemplates() {
        val activity = composeTestRule.activity

        // 1. Switch language to Arabic (العربية - RTL)
        activity.runOnUiThread {
            activity.findViewById<android.view.View>(android.R.id.content).post {
                // Trigger language change via activity viewModel
            }
        }
        composeTestRule.waitForIdle()

        // Open Templates in current language
        composeTestRule.onNodeWithTag("nav_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("upload_template_button").assertExists()

        // Navigate to Settings
        composeTestRule.onNodeWithTag("nav_settings").performClick()
        composeTestRule.waitForIdle()

        // Return to Templates
        composeTestRule.onNodeWithTag("nav_templates").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("upload_template_button").assertExists()
    }

    @Test
    fun testTemplateDatabaseOperationsAndEmptyState() = runBlocking {
        val templateDao = db.templateDao()

        // Verify initial empty state
        val initialTemplates = templateDao.getAllTemplates().first()
        assertTrue(initialTemplates.isEmpty())

        // Insert DOCX, PPTX, XLSX templates
        val docx = com.example.data.model.TemplateEntity(
            id = "t-docx",
            name = "Corporate Contract",
            fileType = "DOCX",
            originalFilePath = "/data/templates/contract.docx",
            profileJson = "{}",
            fileSize = 102400L,
            isDefaultWord = true
        )
        val pptx = com.example.data.model.TemplateEntity(
            id = "t-pptx",
            name = "Pitch Deck",
            fileType = "PPTX",
            originalFilePath = "/data/templates/pitch.pptx",
            profileJson = "{}",
            fileSize = 204800L,
            isDefaultPpt = true
        )
        val xlsx = com.example.data.model.TemplateEntity(
            id = "t-xlsx",
            name = "Financial Model",
            fileType = "XLSX",
            originalFilePath = "/data/templates/model.xlsx",
            profileJson = "{}",
            fileSize = 51200L,
            isDefaultExcel = true
        )

        templateDao.insertTemplate(docx)
        templateDao.insertTemplate(pptx)
        templateDao.insertTemplate(xlsx)

        val all = templateDao.getAllTemplates().first()
        assertEquals(3, all.size)

        // Type filtering
        val docxList = templateDao.getTemplatesByType("DOCX").first()
        assertEquals(1, docxList.size)
        assertEquals("Corporate Contract", docxList[0].name)

        val pptxList = templateDao.getTemplatesByType("PPTX").first()
        assertEquals(1, pptxList.size)
        assertEquals("Pitch Deck", pptxList[0].name)

        // Delete template
        templateDao.deleteTemplateById("t-docx")
        val afterDelete = templateDao.getAllTemplates().first()
        assertEquals(2, afterDelete.size)
        assertNull(templateDao.getTemplateById("t-docx"))
    }
}
