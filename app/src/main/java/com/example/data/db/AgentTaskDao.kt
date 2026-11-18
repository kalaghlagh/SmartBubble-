package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.AgentTaskEntity

@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun byId(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun forSession(sessionId: String): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE status NOT IN ('COMPLETED','COMPLETED_WITH_WARNINGS','INCOMPLETE','FAILED','CANCELLED') ORDER BY createdAt ASC")
    suspend fun unfinished(): List<AgentTaskEntity>

    @Query("DELETE FROM agent_tasks WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM agent_tasks")
    suspend fun deleteAll()
}
