package com.wyltek.wallet.agent.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room validates the schema when the DB opens; a migration whose SQL differs from what Room generated crashes the app
 * on Phill's v5 phone (connected tests are banned there). Room's own CREATE statements are in the KSP output, so pin
 * MIGRATION_5_6 to them, statement for statement.
 */
class MigrationSqlMatchesRoomTest {
    private val impl = File("build/generated/ksp/debug/java/com/wyltek/wallet/agent/db/AgentDatabase_Impl.java").readText()
    private val roomSql = Regex("execSQL\\(\"(CREATE (?:UNIQUE )?(?:TABLE|INDEX) IF NOT EXISTS `(?:index_)?(prepaid_vouchers|prepaid_staff_keys|prepaid_journal_seen)[^\"]*)\"\\)")
        .findAll(impl).map { it.groupValues[1].replace("\\\"", "\"") }.toSet()

    private val v6StaffCreate = MIGRATION_5_6_SQL.single { it.startsWith("CREATE TABLE IF NOT EXISTS `prepaid_staff_keys`") }
    private val addedColumns = MIGRATION_6_7_SQL.map { it.substringAfter("ADD COLUMN ") }
    private val v7StaffCreate = v6StaffCreate.replace(", PRIMARY KEY", ", " + addedColumns.joinToString(", ") + ", PRIMARY KEY")

    @Test fun migration_5_6_then_6_7_creates_exactly_what_room_expects() {
        assertEquals(5, roomSql.size)
        assertEquals(roomSql, MIGRATION_5_6_SQL.map { if (it == v6StaffCreate) v7StaffCreate else it }.toSet())
    }
    @Test fun migration_6_7_is_three_add_columns_on_staff_keys_in_room_order() {
        assertEquals(3, MIGRATION_6_7_SQL.size)
        MIGRATION_6_7_SQL.forEach { assertTrue(it, it.startsWith("ALTER TABLE `prepaid_staff_keys` ADD COLUMN `")) }
        assertEquals(listOf("`pinVer` INTEGER NOT NULL DEFAULT 0", "`pinCheckHex` TEXT", "`pinSetAt` INTEGER"), addedColumns)
        assertTrue(roomSql.single { it.contains("`prepaid_staff_keys`") }.contains(addedColumns.joinToString(", ")))
    }
    @Test fun database_is_version_7_and_registers_both_migrations() {
        assertTrue(impl.contains("RoomOpenHelper.Delegate(7)"))
        assertEquals(5 to 6, MIGRATION_5_6.startVersion to MIGRATION_5_6.endVersion)
        assertEquals(6 to 7, MIGRATION_6_7.startVersion to MIGRATION_6_7.endVersion)
    }
}
