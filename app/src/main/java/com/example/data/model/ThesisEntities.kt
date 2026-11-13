package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thesis Builder tables (schema v4, columns added in v5). Content is stored as structured sections and elements with
 * stable ids; pages are computed when a document is rendered. Large data stays out of Room:
 * images live in the project's private folder and each revision's full snapshot is a compressed
 * file referenced by [ThesisRevisionEntity.snapshotPath].
 */
@Entity(tableName = "thesis_projects")
data class ThesisProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** LICENCE, MASTER or DOCTORATE. */
    val level: String,
    /** Thesis content language: "ar", "fr" or "en". */
    val language: String,
    val studentName: String,
    val university: String,
    val faculty: String,
    val department: String,
    val specialization: String,
    val supervisor: String,
    val coSupervisor: String,
    val academicYear: String,
    val juryMembers: String,
    val extraMetadata: String,
    val logoAssetId: String?,
    /** Template bound from My Templates (null = default template, then built-in formatting). */
    val templateId: String?,
    /** Let the default AI provider suggest destinations when none is named. */
    val aiAssist: Boolean,
    val currentRevisionId: String?,
    val currentRevisionNumber: Int,
    val createdAt: Long,
    val updatedAt: Long,
    // Added in database version 5.
    @ColumnInfo(defaultValue = "") val subtitle: String = "",
    @ColumnInfo(defaultValue = "") val studentId: String = "",
    @ColumnInfo(defaultValue = "") val submissionDate: String = ""
)

@Entity(tableName = "thesis_sections", indices = [Index("projectId")])
data class ThesisSectionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentId: String?,
    val kind: String,
    val title: String,
    val position: Int,
    val enabled: Boolean,
    // Added in database version 5.
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    @ColumnInfo(defaultValue = "") val description: String = ""
)

@Entity(tableName = "thesis_elements", indices = [Index("projectId"), Index("sectionId")])
data class ThesisElementEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sectionId: String,
    /** HEADING, PARAGRAPH, LIST_ITEM, IMAGE, TABLE or REFERENCE. */
    val type: String,
    val position: Int,
    val text: String,
    /** JSON payload: heading level, list marker, image reference/caption/size, table cells, reference status. */
    val data: String,
    val updatedAt: Long
)

@Entity(tableName = "thesis_assets", indices = [Index("projectId")])
data class ThesisAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val originalName: String,
    /** The stored original (never modified). */
    val path: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long
)

@Entity(tableName = "thesis_revisions", indices = [Index("projectId")])
data class ThesisRevisionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val number: Int,
    /** USER, AI, FORMATTING, IMAGE, TABLE, STRUCTURE or RESTORE. */
    val source: String,
    val summary: String,
    /** Compressed snapshot file, relative to the thesis folder. */
    val snapshotPath: String,
    val snapshotHash: String,
    /** For a restoration: the revision number that was restored. */
    val restoredFrom: Int?,
    val createdAt: Long
)

@Entity(tableName = "thesis_proposals", indices = [Index("projectId")])
data class ThesisProposalEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    /** Revision the proposal was prepared against. */
    val baseRevisionId: String?,
    val source: String,
    /** CONTENT, EDIT, IMAGE, TABLE, STRUCTURE. */
    val kind: String,
    /** PENDING, NEEDS_DESTINATION, ACCEPTED, REJECTED or CONFLICT. */
    val status: String,
    /** The user's request (shown in the review). */
    val request: String,
    /** JSON list of operations. */
    val payload: String,
    /** JSON map section id -> content hash when the proposal was made (conflict detection). */
    val targetHashes: String,
    val explanation: String,
    /** Integrity and duplicate notes, one per line. */
    val warnings: String,
    val createdAt: Long,
    val decidedAt: Long?
)
