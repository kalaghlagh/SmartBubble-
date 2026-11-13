package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import com.example.data.model.ThesisAssetEntity
import com.example.data.model.ThesisElementEntity
import com.example.data.model.ThesisProjectEntity
import com.example.data.model.ThesisProposalEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.data.model.ThesisSectionEntity

@Database(
    entities = [
        DocumentEntity::class,
        TemplateEntity::class,
        ProviderEntity::class,
        ChatMessageEntity::class,
        ConversationEntity::class,
        ThesisProjectEntity::class,
        ThesisSectionEntity::class,
        ThesisElementEntity::class,
        ThesisAssetEntity::class,
        ThesisRevisionEntity::class,
        ThesisProposalEntity::class
    ],
    version = 4,
    // Schema JSON is exported to app/schemas (see `ksp { room.schemaLocation }` in
    // app/build.gradle.kts) and must be committed so future migrations can be verified.
    exportSchema = true
)
abstract class SmartBubbleDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun templateDao(): TemplateDao
    abstract fun providerDao(): ProviderDao
    abstract fun chatDao(): ChatDao
    abstract fun conversationDao(): ConversationDao
    abstract fun thesisDao(): ThesisDao

    companion object {
        const val DATABASE_NAME = "smart_bubble_db"

        @Volatile
        private var INSTANCE: SmartBubbleDatabase? = null

        fun getDatabase(context: Context): SmartBubbleDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, DATABASE_NAME).also { INSTANCE = it }
            }
        }

        /**
         * Builds the database with the production configuration.
         *
         * There is deliberately NO destructive-migration fallback: user templates, documents,
         * providers and conversations must never be wiped. Every schema change needs an explicit
         * migration in [SmartBubbleMigrations]; a missing one fails loudly instead of deleting data.
         */
        fun build(context: Context, name: String = DATABASE_NAME): SmartBubbleDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                SmartBubbleDatabase::class.java,
                name
            ).addMigrations(*SmartBubbleMigrations.ALL).build()
        }
    }
}
