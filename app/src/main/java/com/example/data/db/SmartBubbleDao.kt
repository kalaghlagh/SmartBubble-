package com.example.data.db

import androidx.room.*
import com.example.data.model.ChatMessageEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.KnowledgeSourceEntity
import com.example.data.model.TemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoriteDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE fileType = :type ORDER BY createdAt DESC")
    fun getDocumentsByType(type: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query("SELECT COUNT(*) FROM documents")
    fun getDocumentCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity)

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Delete
    suspend fun deleteDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY lastModified DESC")
    fun getAllTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE fileType = :type ORDER BY lastModified DESC")
    fun getTemplatesByType(type: String): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun getTemplateById(id: String): TemplateEntity?

    @Query("SELECT * FROM templates WHERE isDefaultWord = 1 LIMIT 1")
    suspend fun getDefaultWordTemplate(): TemplateEntity?

    @Query("SELECT * FROM templates WHERE isDefaultPpt = 1 LIMIT 1")
    suspend fun getDefaultPptTemplate(): TemplateEntity?

    @Query("SELECT * FROM templates WHERE isDefaultExcel = 1 LIMIT 1")
    suspend fun getDefaultExcelTemplate(): TemplateEntity?

    @Query("SELECT * FROM templates WHERE isDefaultPdf = 1 LIMIT 1")
    suspend fun getDefaultPdfTemplate(): TemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: TemplateEntity)

    @Update
    suspend fun updateTemplate(template: TemplateEntity)

    @Delete
    suspend fun deleteTemplate(template: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplateById(id: String)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_sources ORDER BY createdAt DESC")
    fun getAllKnowledgeSources(): Flow<List<KnowledgeSourceEntity>>

    @Query("SELECT * FROM knowledge_sources WHERE id = :id")
    suspend fun getKnowledgeSourceById(id: String): KnowledgeSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeSource(source: KnowledgeSourceEntity)

    @Delete
    suspend fun deleteKnowledgeSource(source: KnowledgeSourceEntity)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
