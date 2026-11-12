package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.ConversationCleaner
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

/** "/clear" deletions on a real (in-memory) Room database. */
@RunWith(RobolectricTestRunner::class)
class ClearConversationsTest {

    private lateinit var db: SmartBubbleDatabase
    private lateinit var cleaner: ConversationCleaner

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        cleaner = ConversationCleaner(db)
        for (id in listOf("a", "b")) {
            db.conversationDao().insertConversation(ConversationEntity(id = id, title = "Chat $id", lastMessagePreview = "last $id"))
            repeat(3) { i ->
                db.chatDao().insertMessage(ChatMessageEntity(id = "$id$i", sessionId = id, isUser = i % 2 == 0, content = "m$i", attachedDocId = if (i == 1) "doc1" else null))
            }
        }
        db.documentDao().insertDocument(
            DocumentEntity(id = "doc1", title = "Report.docx", fileType = "DOCX", filePath = "/x/report.docx", fileSize = 10, wordCount = 2, pageOrSlideCount = 1, providerUsed = "P")
        )
        db.templateDao().insertTemplate(TemplateEntity(id = "t1", name = "Letter", fileType = "DOCX", originalFilePath = "/x/t.docx", profileJson = "{}"))
        db.providerDao().insertProvider(ProviderEntity(id = "p1", name = "Mine", providerType = "OpenAI", baseUrl = "https://api.openai.com/v1", encryptedApiKey = "", modelName = "m", isDefault = true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun assertUnrelatedDataKept() {
        assertNotNull(db.documentDao().getDocumentById("doc1"))
        assertNotNull(db.templateDao().getTemplateById("t1"))
        val provider = db.providerDao().getProviderById("p1")!!
        assertTrue(provider.isDefault)
    }

    @Test
    fun clearCurrentChatEmptiesOnlyThatConversation() = runBlocking {
        val deleted = cleaner.clearConversation("a", "New Conversation")
        assertEquals(3, deleted)
        assertTrue(db.chatDao().getMessagesForSessionOnce("a").isEmpty())
        val a = db.conversationDao().getConversationById("a")!!
        assertEquals("New Conversation", a.title)
        assertEquals("", a.lastMessagePreview)
        // The other conversation is untouched.
        assertEquals(3, db.chatDao().getMessagesForSessionOnce("b").size)
        assertEquals("Chat b", db.conversationDao().getConversationById("b")!!.title)
        assertUnrelatedDataKept()
    }

    @Test
    fun deleteAllRemovesEveryConversationAndMessage() = runBlocking {
        assertEquals(2, cleaner.deleteAllConversations())
        assertEquals(0, db.conversationDao().countConversations())
        assertEquals(0, db.chatDao().countMessages())
        assertTrue(db.conversationDao().getAllConversations().first().isEmpty())
        assertUnrelatedDataKept()
    }

    @Test
    fun deletionsAreAtomic() = runBlocking {
        // A failure inside the same transaction rolls the whole deletion back.
        try {
            db.withTransaction {
                cleaner.deleteAllConversations()
                throw IllegalStateException("simulated failure")
            }
            @Suppress("UNREACHABLE_CODE")
            fail("expected failure")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(2, db.conversationDao().countConversations())
        assertEquals(6, db.chatDao().countMessages())
    }

    @Test
    fun clearingAMissingConversationChangesNothingElse() = runBlocking {
        assertEquals(0, cleaner.clearConversation("does-not-exist", "New Conversation"))
        assertEquals(6, db.chatDao().countMessages())
        assertEquals(2, db.conversationDao().countConversations())
    }
}
