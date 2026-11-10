package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity

@Database(
    entities = [
        DocumentEntity::class,
        TemplateEntity::class,
        ProviderEntity::class,
        ChatMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SmartBubbleDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun templateDao(): TemplateDao
    abstract fun providerDao(): ProviderDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: SmartBubbleDatabase? = null

        fun getDatabase(context: Context): SmartBubbleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SmartBubbleDatabase::class.java,
                    "smart_bubble_db"
                ).fallbackToDestructiveMigration(true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
