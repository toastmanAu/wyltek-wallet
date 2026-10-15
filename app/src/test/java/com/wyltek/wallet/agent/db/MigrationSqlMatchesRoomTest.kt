package com.wyltek.wallet.agent.db

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Room validates the schema when the DB opens; a migration whose SQL differs from what Room generated crashes the app
 * on Phill's v5 phone (connected tests are banned there). Room's own CREATE statements are in the KSP output, so pin
 * MIGRATION_5_6 to them, statement for statement.
 */
class MigrationSqlMatchesRoomTest {
    private val impl = File("build/generated/ksp/debug/java/com/wyltek/wallet/agent/db/AgentDatabase_Impl.java").readText()
    private val roomSql = Regex("execSQL\\(\"(CREATE (?:TABLE|INDEX) IF NOT EXISTS `(?:index_)?(prepaid_vouchers|prepaid_staff_keys|prepaid_journal_seen)[^\"]*)\"\\)")
        .findAll(impl).map { it.groupValues[1].replace("\\\"", "\"") }.toSet()

    @Test fun migration_5_6_creates_exactly_what_room_expects() {
        assertEquals(5, roomSql.size)
        assertEquals(roomSql, MIGRATION_5_6_SQL.toSet())
    }
    @Test fun database_is_version_6_and_registers_the_migration() {
        assertEquals(6, MIGRATION_5_6.endVersion); assertEquals(5, MIGRATION_5_6.startVersion)
    }
}
