package com.example.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit, non-destructive schema migrations for [SmartBubbleDatabase].
 *
 * Schema history (reconstructed from the entity definitions in git history):
 *  - v1 (63883c4): documents, templates (without fileSize), knowledge_sources, chat_messages
 *  - v2 (150fc6a): templates.fileSize added, ai_providers added, KnowledgeSourceEntity removed
 *  - v3 (5651cb0): conversations added (current schema)
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

    /** Every migration, in order. Always pass this to the database builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
