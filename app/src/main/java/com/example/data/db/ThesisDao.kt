package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ThesisAssetEntity
import com.example.data.model.ThesisElementEntity
import com.example.data.model.ThesisProjectEntity
import com.example.data.model.ThesisProposalEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.data.model.ThesisSectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThesisDao {
    @Query("SELECT * FROM thesis_projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ThesisProjectEntity>>

    @Query("SELECT * FROM thesis_projects ORDER BY updatedAt DESC")
    suspend fun projects(): List<ThesisProjectEntity>

    @Query("SELECT * FROM thesis_projects WHERE id = :id")
    suspend fun project(id: String): ThesisProjectEntity?

    @Query("SELECT * FROM thesis_projects WHERE id = :id")
    fun observeProject(id: String): Flow<ThesisProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ThesisProjectEntity)

    @Update
    suspend fun updateProject(project: ThesisProjectEntity)

    @Query("DELETE FROM thesis_projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    @Query("SELECT * FROM thesis_sections WHERE projectId = :projectId ORDER BY position")
    suspend fun sections(projectId: String): List<ThesisSectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSections(sections: List<ThesisSectionEntity>)

    @Query("DELETE FROM thesis_sections WHERE id IN (:ids)")
    suspend fun deleteSections(ids: List<String>)

    @Query("DELETE FROM thesis_sections WHERE projectId = :projectId")
    suspend fun deleteSectionsOf(projectId: String)

    @Query("SELECT * FROM thesis_elements WHERE projectId = :projectId ORDER BY position")
    suspend fun elements(projectId: String): List<ThesisElementEntity>

    @Query("SELECT COUNT(*) FROM thesis_elements WHERE projectId = :projectId")
    suspend fun countElements(projectId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertElements(elements: List<ThesisElementEntity>)

    @Query("DELETE FROM thesis_elements WHERE id IN (:ids)")
    suspend fun deleteElements(ids: List<String>)

    @Query("DELETE FROM thesis_elements WHERE projectId = :projectId")
    suspend fun deleteElementsOf(projectId: String)

    @Query("SELECT * FROM thesis_assets WHERE projectId = :projectId ORDER BY createdAt")
    suspend fun assets(projectId: String): List<ThesisAssetEntity>

    @Query("SELECT * FROM thesis_assets WHERE id = :id")
    suspend fun asset(id: String): ThesisAssetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(asset: ThesisAssetEntity)

    @Query("DELETE FROM thesis_assets WHERE projectId = :projectId")
    suspend fun deleteAssetsOf(projectId: String)

    @Query("SELECT * FROM thesis_revisions WHERE projectId = :projectId ORDER BY number DESC")
    fun observeRevisions(projectId: String): Flow<List<ThesisRevisionEntity>>

    @Query("SELECT * FROM thesis_revisions WHERE projectId = :projectId ORDER BY number DESC")
    suspend fun revisions(projectId: String): List<ThesisRevisionEntity>

    @Query("SELECT * FROM thesis_revisions WHERE id = :id")
    suspend fun revision(id: String): ThesisRevisionEntity?

    @Query("SELECT snapshotPath FROM thesis_revisions")
    suspend fun allSnapshotPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: ThesisRevisionEntity)

    @Query("DELETE FROM thesis_revisions WHERE projectId = :projectId")
    suspend fun deleteRevisionsOf(projectId: String)

    @Query("SELECT * FROM thesis_proposals WHERE projectId = :projectId AND status IN ('PENDING', 'NEEDS_DESTINATION', 'CONFLICT') ORDER BY createdAt DESC")
    fun observeOpenProposals(projectId: String): Flow<List<ThesisProposalEntity>>

    @Query("SELECT * FROM thesis_proposals WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun proposals(projectId: String): List<ThesisProposalEntity>

    @Query("SELECT * FROM thesis_proposals WHERE id = :id")
    suspend fun proposal(id: String): ThesisProposalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProposal(proposal: ThesisProposalEntity)

    @Query("DELETE FROM thesis_proposals WHERE projectId = :projectId")
    suspend fun deleteProposalsOf(projectId: String)
}
