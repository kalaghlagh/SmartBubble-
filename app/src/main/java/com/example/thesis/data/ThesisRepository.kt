package com.example.thesis.data

import androidx.room.withTransaction
import com.example.ai.providers.Json
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ThesisAssetEntity
import com.example.data.model.ThesisElementEntity
import com.example.data.model.ThesisProjectEntity
import com.example.data.model.ThesisProposalEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.data.model.ThesisSectionEntity
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementType
import com.example.thesis.model.ProposalStatus
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisCodec
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Result of accepting a proposal. */
sealed class AcceptResult {
    data class Accepted(val revision: ThesisRevisionEntity) : AcceptResult()
    /** The sections it changes were edited after it was prepared: the user must review again. */
    data class Conflict(val sectionIds: List<String>) : AcceptResult()
}

class ThesisAssetException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { UNREADABLE, TOO_LARGE, NOT_AN_IMAGE }
}

/**
 * Local storage of thesis projects. The approved content lives in Room (sections and elements
 * with stable ids); every approved change also writes a compressed snapshot file and a revision
 * row in ONE transaction, so history is never lost and a cancelled or interrupted operation never
 * leaves a half-applied revision. AI proposals are stored separately and never touch the
 * approved content until the user accepts them.
 */
class ThesisRepository(
    private val db: SmartBubbleDatabase,
    /** Private folder of all thesis data (filesDir/thesis). */
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Reads image dimensions; null when the file is not a supported image. */
    private val imageInspector: (File) -> Pair<Int, Int>?
) {
    companion object {
        const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    }

    private val dao get() = db.thesisDao()

    private fun projectDir(projectId: String) = File(root, projectId)

    fun observeProjects(): Flow<List<ThesisProjectEntity>> = dao.observeProjects()
    fun observeProject(id: String): Flow<ThesisProjectEntity?> = dao.observeProject(id)
    fun observeRevisions(projectId: String): Flow<List<ThesisRevisionEntity>> = dao.observeRevisions(projectId)
    fun observeOpenProposals(projectId: String): Flow<List<ThesisProposalEntity>> = dao.observeOpenProposals(projectId)

    suspend fun project(id: String) = dao.project(id)
    suspend fun proposal(id: String) = dao.proposal(id)
    suspend fun revisions(projectId: String) = dao.revisions(projectId)
    suspend fun proposals(projectId: String) = dao.proposals(projectId)

    // ------------------------------------------------------------------ mapping

    private fun metaOf(p: ThesisProjectEntity) = ThesisMeta(
        title = p.title, level = AcademicLevel.values().firstOrNull { it.name == p.level } ?: AcademicLevel.MASTER,
        language = p.language, studentName = p.studentName, university = p.university, faculty = p.faculty,
        department = p.department, specialization = p.specialization, supervisor = p.supervisor, coSupervisor = p.coSupervisor,
        academicYear = p.academicYear, juryMembers = p.juryMembers, extraMetadata = p.extraMetadata,
        logoAssetId = p.logoAssetId, templateId = p.templateId,
        subtitle = p.subtitle, studentId = p.studentId, submissionDate = p.submissionDate
    )

    private fun ThesisProjectEntity.withMeta(m: ThesisMeta, now: Long) = copy(
        title = m.title, level = m.level.name, language = m.language, studentName = m.studentName, university = m.university,
        faculty = m.faculty, department = m.department, specialization = m.specialization, supervisor = m.supervisor,
        coSupervisor = m.coSupervisor, academicYear = m.academicYear, juryMembers = m.juryMembers, extraMetadata = m.extraMetadata,
        logoAssetId = m.logoAssetId, templateId = m.templateId, updatedAt = now,
        subtitle = m.subtitle, studentId = m.studentId, submissionDate = m.submissionDate
    )

    private fun sectionEntity(projectId: String, s: ThesisSection) =
        ThesisSectionEntity(s.id, projectId, s.parentId, s.kind.name, s.title, s.position, s.enabled, s.completed, s.description)

    private fun elementEntity(projectId: String, e: ThesisElement, now: Long) =
        ThesisElementEntity(e.id, projectId, e.sectionId, e.type.name, e.position, e.text, ThesisCodec.encodeData(e.data), now)

    private fun assetOf(a: ThesisAssetEntity) = ThesisAsset(a.id, a.originalName, a.path, a.width, a.height, a.mimeType)

    // ------------------------------------------------------------------ reading

    /** The current approved content. */
    suspend fun snapshot(projectId: String): ThesisSnapshot {
        val p = dao.project(projectId) ?: throw IllegalArgumentException("Unknown thesis project")
        return ThesisSnapshot(
            meta = metaOf(p),
            sections = dao.sections(projectId).map { ThesisSection(it.id, it.parentId, SectionKind.parse(it.kind), it.title, it.position, it.enabled, it.completed, it.description) },
            elements = dao.elements(projectId).map { ThesisElement(it.id, it.sectionId, ElementType.parse(it.type), it.position, it.text, ThesisCodec.decodeData(it.data)) }
        )
    }

    suspend fun assets(projectId: String): List<ThesisAsset> = dao.assets(projectId).map(::assetOf)

    /** Content of a past revision (read from its snapshot file). */
    suspend fun revisionSnapshot(revisionId: String): ThesisSnapshot = withContext(io) {
        val r = dao.revision(revisionId) ?: throw IllegalArgumentException("Unknown revision")
        val text = GZIPInputStream(File(root, r.snapshotPath).inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        if (ThesisSnapshot.sha1(text) != r.snapshotHash) throw IllegalStateException("The revision file is damaged")
        ThesisCodec.decodeSnapshot(text)
    }

    // ------------------------------------------------------------------ writing

    private suspend fun writeSnapshotFile(projectId: String, revisionId: String, snapshot: ThesisSnapshot): Pair<String, String> = withContext(io) {
        val json = ThesisCodec.encodeSnapshot(snapshot)
        val rel = "$projectId/revisions/$revisionId.json.gz"
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$revisionId.tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("Could not store the revision") }
        rel to ThesisSnapshot.sha1(json)
    }

    /** Writes [new] as the approved content and records a revision, atomically. */
    private suspend fun commit(projectId: String, old: ThesisSnapshot, new: ThesisSnapshot, source: ChangeSource, summary: String, restoredFrom: Int? = null): ThesisRevisionEntity {
        val revisionId = "rev-" + UUID.randomUUID()
        val (path, hash) = writeSnapshotFile(projectId, revisionId, new)
        val now = System.currentTimeMillis()
        try {
            return db.withTransaction {
                val project = dao.project(projectId) ?: throw IllegalStateException("The project was deleted")
                // Only changed rows are written (small edits stay cheap on large theses).
                val oldSections = old.sections.associateBy { it.id }
                val oldElements = old.elements.associateBy { it.id }
                dao.deleteSections(old.sections.map { it.id }.filter { new.section(it) == null })
                dao.deleteElements(old.elements.map { it.id }.filter { new.element(it) == null })
                dao.upsertSections(new.sections.filter { oldSections[it.id] != it }.map { sectionEntity(projectId, it) })
                dao.upsertElements(new.elements.filter { oldElements[it.id] != it }.map { elementEntity(projectId, it, now) })
                val number = project.currentRevisionNumber + 1
                val revision = ThesisRevisionEntity(revisionId, projectId, number, source.name, summary.take(500), path, hash, restoredFrom, now)
                dao.insertRevision(revision)
                dao.updateProject(project.withMeta(new.meta, now).copy(currentRevisionId = revisionId, currentRevisionNumber = number))
                revision
            }
        } catch (e: Throwable) {
            File(root, path).delete()
            throw e
        }
    }

    /** Creates a project with the suggested structure and its first revision. */
    suspend fun createProject(meta: ThesisMeta, aiAssist: Boolean = true): String {
        val id = "thesis-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val sections = ThesisStructure.defaultSections(meta.language, meta.level)
        db.withTransaction {
            dao.insertProject(
                ThesisProjectEntity(id, meta.title, meta.level.name, meta.language, meta.studentName, meta.university, meta.faculty,
                    meta.department, meta.specialization, meta.supervisor, meta.coSupervisor, meta.academicYear, meta.juryMembers,
                    meta.extraMetadata, meta.logoAssetId, meta.templateId, aiAssist, null, 0, now, now,
                    subtitle = meta.subtitle, studentId = meta.studentId, submissionDate = meta.submissionDate)
            )
        }
        val empty = ThesisSnapshot(meta, emptyList(), emptyList())
        commit(id, empty, ThesisSnapshot(meta, sections, emptyList()), ChangeSource.STRUCTURE, "created")
        return id
    }

    suspend fun setAiAssist(projectId: String, enabled: Boolean) {
        dao.project(projectId)?.let { dao.updateProject(it.copy(aiAssist = enabled, updatedAt = System.currentTimeMillis())) }
    }

    /** Applies the user's own change (edits made in the workspace) as a new revision. */
    suspend fun applyDirect(projectId: String, ops: List<ThesisOp>, source: ChangeSource, summary: String): ThesisRevisionEntity {
        val current = snapshot(projectId)
        val next = ThesisOperations.apply(current, ops)
        return commit(projectId, current, next, source, summary)
    }

    /**
     * Stores a proposal without touching the approved content. The hashes of the sections it
     * touches are kept to detect later edits (stale proposals).
     */
    suspend fun propose(
        projectId: String,
        ops: List<ThesisOp>,
        source: ChangeSource,
        kind: String,
        request: String,
        explanation: String,
        warnings: List<String>,
        needsDestination: Boolean = false
    ): ThesisProposalEntity {
        val current = snapshot(projectId)
        if (!needsDestination) ThesisOperations.apply(current, ops) // validates ids before anything is stored
        val hashes = ThesisOperations.touchedSections(ops, current).associateWith { current.sectionHash(it) }
        val project = dao.project(projectId)!!
        val p = ThesisProposalEntity(
            id = "prop-" + UUID.randomUUID(), projectId = projectId, baseRevisionId = project.currentRevisionId, source = source.name,
            kind = kind, status = if (needsDestination) ProposalStatus.NEEDS_DESTINATION.name else ProposalStatus.PENDING.name,
            request = request.take(2000), payload = ThesisOperations.encode(ops), targetHashes = Json.write(hashes),
            explanation = explanation.take(1000), warnings = warnings.joinToString("\n"), createdAt = System.currentTimeMillis(), decidedAt = null
        )
        dao.upsertProposal(p)
        return p
    }

    /** Replaces a pending proposal's operations (destination chosen, caption typed...). */
    suspend fun updateProposal(proposalId: String, ops: List<ThesisOp>): ThesisProposalEntity {
        val p = dao.proposal(proposalId) ?: throw IllegalArgumentException("Unknown proposal")
        val current = snapshot(p.projectId)
        ThesisOperations.apply(current, ops)
        val hashes = ThesisOperations.touchedSections(ops, current).associateWith { current.sectionHash(it) }
        val updated = p.copy(payload = ThesisOperations.encode(ops), targetHashes = Json.write(hashes), status = ProposalStatus.PENDING.name,
            baseRevisionId = dao.project(p.projectId)?.currentRevisionId)
        dao.upsertProposal(updated)
        return updated
    }

    /** Changes details of a proposal (caption, size...) without changing its status or destination. */
    suspend fun updateProposalKeepingStatus(proposalId: String, ops: List<ThesisOp>): ThesisProposalEntity {
        val p = dao.proposal(proposalId) ?: throw IllegalArgumentException("Unknown proposal")
        if (p.status == ProposalStatus.NEEDS_DESTINATION.name) {
            val updated = p.copy(payload = ThesisOperations.encode(ops))
            dao.upsertProposal(updated)
            return updated
        }
        return updateProposal(proposalId, ops)
    }

    /** The approved content and what it would become if [proposal] were accepted. */
    suspend fun preview(proposal: ThesisProposalEntity): Pair<ThesisSnapshot, ThesisSnapshot?> {
        val current = snapshot(proposal.projectId)
        val ops = ThesisOperations.decode(proposal.payload)
        val after = try { ThesisOperations.apply(current, ops) } catch (e: Exception) { null }
        return current to after
    }

    /** Sections changed since the proposal was prepared (empty = safe to apply). */
    suspend fun conflicts(proposal: ThesisProposalEntity, current: ThesisSnapshot? = null): List<String> {
        val snapshot = current ?: snapshot(proposal.projectId)
        val hashes = Json.obj(Json.parse(proposal.targetHashes)) ?: emptyMap()
        return hashes.filter { (id, h) -> snapshot.sectionHash(id) != h }.keys.toList()
    }

    /**
     * Accepts a proposal: re-checks it against the current content, applies it and records a new
     * revision in one transaction. A stale proposal is marked CONFLICT instead of overwriting newer work.
     */
    suspend fun accept(proposalId: String): AcceptResult {
        val p = dao.proposal(proposalId) ?: throw IllegalArgumentException("Unknown proposal")
        if (p.status != ProposalStatus.PENDING.name) throw IllegalStateException("The proposal is ${p.status}")
        val current = snapshot(p.projectId)
        val stale = conflicts(p, current)
        if (stale.isNotEmpty()) {
            dao.upsertProposal(p.copy(status = ProposalStatus.CONFLICT.name))
            return AcceptResult.Conflict(stale)
        }
        val next = ThesisOperations.apply(current, ThesisOperations.decode(p.payload))
        val revision = commit(p.projectId, current, next, ChangeSource.parse(p.source), p.explanation.ifBlank { p.request }.take(200))
        dao.upsertProposal(p.copy(status = ProposalStatus.ACCEPTED.name, decidedAt = System.currentTimeMillis()))
        return AcceptResult.Accepted(revision)
    }

    /** Rejecting never changes the approved content. */
    suspend fun reject(proposalId: String) {
        val p = dao.proposal(proposalId) ?: return
        dao.upsertProposal(p.copy(status = ProposalStatus.REJECTED.name, decidedAt = System.currentTimeMillis()))
    }

    /** Restores an earlier revision as a NEW revision; later history is kept. */
    suspend fun restore(projectId: String, revisionId: String): ThesisRevisionEntity {
        val target = dao.revision(revisionId) ?: throw IllegalArgumentException("Unknown revision")
        val old = revisionSnapshot(revisionId)
        val current = snapshot(projectId)
        return commit(projectId, current, old, ChangeSource.RESTORE, "restored ${target.number}", restoredFrom = target.number)
    }

    // ------------------------------------------------------------------ assets

    /** Copies an image into the project's private folder (the source is only read). */
    suspend fun importImage(projectId: String, open: () -> InputStream?, originalName: String, mimeType: String): ThesisAsset = withContext(io) {
        val dir = File(projectDir(projectId), "assets").apply { mkdirs() }
        val id = "img-" + UUID.randomUUID().toString().substring(0, 13)
        val ext = when {
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
        val file = File(dir, "$id.$ext")
        var ok = false
        try {
            val input = try { open() } catch (e: Exception) { null } ?: throw ThesisAssetException(ThesisAssetException.Reason.UNREADABLE)
            input.use { src ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) throw ThesisAssetException(ThesisAssetException.Reason.TOO_LARGE)
                        out.write(buffer, 0, n)
                    }
                }
            }
            val (w, h) = imageInspector(file) ?: throw ThesisAssetException(ThesisAssetException.Reason.NOT_AN_IMAGE)
            val entity = ThesisAssetEntity(id, projectId, originalName.take(200), file.absolutePath, w, h, mimeType, file.length(), System.currentTimeMillis())
            dao.insertAsset(entity)
            ok = true
            assetOf(entity)
        } finally {
            if (!ok) file.delete()
        }
    }

    // ------------------------------------------------------------------ deletion and recovery

    /** Deletes the project, its content, images, history and proposals. Templates and Library documents are kept. */
    suspend fun deleteProject(projectId: String) {
        db.withTransaction {
            dao.deleteElementsOf(projectId)
            dao.deleteSectionsOf(projectId)
            dao.deleteAssetsOf(projectId)
            dao.deleteRevisionsOf(projectId)
            dao.deleteProposalsOf(projectId)
            dao.deleteProject(projectId)
        }
        withContext(io) { projectDir(projectId).deleteRecursively() }
    }

    /** Removes snapshot files left by an interrupted commit (no revision row refers to them). */
    suspend fun cleanUpInterrupted() {
        val known = dao.allSnapshotPaths().toSet()
        withContext(io) {
            root.listFiles()?.forEach { project ->
                File(project, "revisions").listFiles()?.forEach { f ->
                    val rel = "${project.name}/revisions/${f.name}"
                    if (f.name.endsWith(".tmp") || (f.name.endsWith(".json.gz") && rel !in known)) f.delete()
                }
            }
        }
    }
}
