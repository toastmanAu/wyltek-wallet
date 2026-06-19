package com.wyltek.wallet.agent.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [SpendRecordEntity::class, TokenRegistryEntity::class], version = 1, exportSchema = false)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}

object AgentDatabaseFactory {
    fun open(context: Context, passphrase: ByteArray): AgentDatabase {
        SQLiteDatabase.loadLibs(context)
        val factory = SupportFactory(passphrase.copyOf(), null, false)
        return Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent_gateway.db")
            .openHelperFactory(factory)
            .build()
    }
}
