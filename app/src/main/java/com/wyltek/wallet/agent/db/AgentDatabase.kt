package com.wyltek.wallet.agent.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS pending_intents (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, token TEXT NOT NULL, op TEXT NOT NULL,
            asset TEXT NOT NULL, `to` TEXT NOT NULL, amount INTEGER NOT NULL, nonce TEXT NOT NULL,
            action TEXT, daoRef TEXT, sourceIp TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
            resultTxHash TEXT, resultError TEXT)""")
    }
}

@Database(
    entities = [SpendRecordEntity::class, TokenRegistryEntity::class, PendingIntentEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}

object AgentDatabaseFactory {
    fun open(context: Context, passphrase: ByteArray): AgentDatabase {
        SQLiteDatabase.loadLibs(context)
        val factory = SupportFactory(passphrase.copyOf(), null, false)
        return Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent_gateway.db")
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
