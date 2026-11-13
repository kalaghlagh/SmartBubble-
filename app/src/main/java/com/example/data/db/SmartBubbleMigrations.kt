package com.example.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit, non-destructive schema migrations for [SmartBubbleDatabase].
 *
 * Schema history (reconstructed from the entity definitions in git history):
 *  - v1 (63883c4): documents, templates (without fileSize), knowledge_sources, chat_messages
 *  - v2 (150fc6a): templates.fileSize added, ai_providers added, KnowledgeSourceEntity removed
 *  - v3 (5651cb0): conversations added
 *  - v4: Thesis Builder tables added (current schema)
 *
 * Rules for future schema changes:
 *  1. Bump the @Database version.
 *  2. Add a Migration(N, N + 1) object below and append it to [ALL].
 *  3. Commit the exported schema JSON from app/schemas.
 *  4. Add a test in SmartBubbleMigrationTest that opens a database populated at version N.
 *
 * Migrations must never drop or overwrite user data. The database is built WITHOUT any
 * destructive fallback, so a missing migration fails loudly instead of wiping user data.
 */
object SmartBubbleMigrations {

    /**
     * v1 -> v2
     *  - templates: new NOT NULL column `fileSize`. Existing rows get 0 ("unknown"); the real
     *    size is backfilled from the stored template file at app start-up.
     *  - ai_providers: new table.
     *  - knowledge_sources: no longer used by the app, but intentionally NOT dropped so any data
     *    a user stored in it is preserved. Room ignores tables that are not entities.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `templates` ADD COLUMN `fileSize` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_providers` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `providerType` TEXT NOT NULL, " +
                    "`baseUrl` TEXT NOT NULL, `encryptedApiKey` TEXT NOT NULL, `modelName` TEXT NOT NULL, " +
                    "`isDefault` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
        }
    }

    /**
     * v2 -> v3
     *  - conversations: new table.
     *  - Existing chat history (chat_messages grouped by sessionId) gets a matching conversation
     *    row so earlier conversations stay visible in the conversation list instead of being
     *    orphaned. Title = first user message (32 chars), preview = latest message (60 chars).
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversations` (" +
                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `lastMessagePreview` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `conversations` (`id`, `title`, `lastMessagePreview`, `updatedAt`, `createdAt`) " +
                    "SELECT m.`sessionId`, " +
                    "COALESCE(NULLIF(TRIM((SELECT substr(f.`content`, 1, 32) FROM `chat_messages` f " +
                    "WHERE f.`sessionId` = m.`sessionId` AND f.`isUser` = 1 ORDER BY f.`timestamp` ASC LIMIT 1)), ''), " +
                    "'New Conversation'), " +
                    "COALESCE((SELECT substr(l.`content`, 1, 60) FROM `chat_messages` l " +
                    "WHERE l.`sessionId` = m.`sessionId` ORDER BY l.`timestamp` DESC LIMIT 1), ''), " +
                    "MAX(m.`timestamp`), MIN(m.`timestamp`) " +
                    "FROM `chat_messages` m GROUP BY m.`sessionId`"
            )
        }
    }

    /**
     * v3 -> v4
     *  - Thesis Builder: thesis_projects, thesis_sections, thesis_elements, thesis_assets,
     *    thesis_revisions, thesis_proposals (new tables with their indices). Existing tables and
     *    data are not touched.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `thesis_projects` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `level` TEXT NOT NULL, " +
                    "`language` TEXT NOT NULL, `studentName` TEXT NOT NULL, `university` TEXT NOT NULL, `faculty` TEXT NOT NULL, " +
                    "`department` TEXT NOT NULL, `specialization` TEXT NOT NULL, `supervisor` TEXT NOT NULL, `coSupervisor` TEXT NOT NULL, " +
                    "`academicYear` TEXT NOT NULL, `juryMembers` TEXT NOT NULL, `extraMetadata` TEXT NOT NULL, `logoAssetId` TEXT, " +
                    "`templateId` TEXT, `aiAssist` INTEGER NOT NULL, `currentRevisionId` TEXT, `currentRevisionNumber` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `thesis_sections` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `parentId` TEXT, " +
                    "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_thesis_sections_projectId` ON `thesis_sections` (`projectId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `thesis_elements` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `sectionId` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `position` INTEGER NOT NULL, `text` TEXT NOT NULL, `data` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_thesis_elements_projectId` ON `thesis_elements` (`projectId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_thesis_elements_sectionId` ON `thesis_elements` (`sectionId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `thesis_assets` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `originalName` TEXT NOT NULL, " +
                    "`path` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_thesis_assets_projectId` ON `thesis_assets` (`projectId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `thesis_revisions` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `number` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, `summary` TEXT NOT NULL, `snapshotPath` TEXT NOT NULL, `snapshotHash` TEXT NOT NULL, " +
                    "`restoredFrom` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_thesis_revisions_projectId` ON `thesis_revisions` (`projectId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `thesis_proposals` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `baseRevisionId` TEXT, " +
                    "`source` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `request` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                    "`targetHashes` TEXT NOT NULL, `explanation` TEXT NOT NULL, `warnings` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`decidedAt` INTEGER, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_thesis_proposals_projectId` ON `thesis_proposals` (`projectId`)")
        }
    }

    /** Every migration, in order. Always pass this to the database builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
