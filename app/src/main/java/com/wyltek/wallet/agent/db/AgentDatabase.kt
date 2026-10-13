package com.wyltek.wallet.agent.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import com.wyltek.wallet.prepaid.db.PrepaidDao
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
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

/** v2 → v3: adds relay_intent_id column for tracking relay-originated pending intents. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_intents ADD COLUMN relay_intent_id TEXT")
    }
}

/** v3 → v4: prepaid card index + append-only audit log (D3). DDL must match Room's expectation
 *  exactly; copy it from build/generated/.../AgentDatabase_Impl.java `createAllTables` after the
 *  first ksp run if in doubt. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `prepaid_cards` (`reserveId` TEXT NOT NULL, `uid` TEXT NOT NULL, `label` TEXT,
            `firstOutpoint` TEXT NOT NULL, `currentOutpoint` TEXT NOT NULL, `balanceUnits` INTEGER NOT NULL, `counter` INTEGER NOT NULL,
            `settledCounter` INTEGER NOT NULL, `state` TEXT NOT NULL, `pendingUnits` INTEGER, `pendingTxHash` TEXT, `pendingActionId` TEXT,
            `pendingCommitted` INTEGER NOT NULL, `issuedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`reserveId`))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_prepaid_cards_uid` ON `prepaid_cards` (`uid`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `prepaid_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `atMs` INTEGER NOT NULL,
            `actionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `phase` TEXT NOT NULL, `reserveId` TEXT, `uid` TEXT NOT NULL,
            `amountUnits` INTEGER NOT NULL, `balanceBefore` INTEGER NOT NULL, `balanceAfter` INTEGER NOT NULL, `counterBefore` INTEGER NOT NULL,
            `counterAfter` INTEGER NOT NULL, `txHash` TEXT, `detail` TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prepaid_events_actionId` ON `prepaid_events` (`actionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prepaid_events_reserveId` ON `prepaid_events` (`reserveId`)")
    }
}

/** v4 → v5: pending-action intent + idempotent tag-write bookkeeping on prepaid_cards. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `prepaid_cards` ADD COLUMN `pendingKind` TEXT")
        db.execSQL("ALTER TABLE `prepaid_cards` ADD COLUMN `pendingTagCounter` INTEGER")
        db.execSQL("ALTER TABLE `prepaid_cards` ADD COLUMN `pendingTagBalance` INTEGER")
        db.execSQL("ALTER TABLE `prepaid_cards` ADD COLUMN `pendingSince` INTEGER")
    }
}

@Database(
    entities = [
        SpendRecordEntity::class, TokenRegistryEntity::class, PendingIntentEntity::class,
        PrepaidCardEntity::class, PrepaidEventEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun prepaidDao(): PrepaidDao
}

object AgentDatabaseFactory {
    fun open(context: Context, passphrase: ByteArray): AgentDatabase {
        SQLiteDatabase.loadLibs(context)
        val factory = SupportFactory(passphrase.copyOf(), null, false)
        return Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent_gateway.db")
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}
