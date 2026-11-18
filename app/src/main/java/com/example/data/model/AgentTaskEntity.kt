package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent task checkpoint (v8). [snapshotJson] is AgentTaskCodec's JSON: the request, the plan,
 * step states and results, provider attempts. Never API keys or other credentials.
 */
@Entity(tableName = "agent_tasks", indices = [Index(value = ["sessionId"])])
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val status: String,
    val snapshotJson: String,
    val createdAt: Long,
    val updatedAt: Long
)
