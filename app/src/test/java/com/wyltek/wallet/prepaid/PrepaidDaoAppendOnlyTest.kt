package com.wyltek.wallet.prepaid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The audit table is append-only (D3 spec §3.2). Room annotations are not retained at runtime,
 *  so this test reads the DAO source. Gradle runs unit tests with the module dir as CWD. */
class PrepaidDaoAppendOnlyTest {
    private val src = File("src/main/java/com/wyltek/wallet/prepaid/db/PrepaidDao.kt").readText()

    @Test fun `dao source exists and names the events table`() {
        assertTrue(src.contains("prepaid_events"))
    }

    @Test fun `no update or delete annotation takes an event entity`() {
        val annotated = Regex("""@(Update|Delete)[^\n]*\n[^\n]*""").findAll(src).map { it.value }.toList()
        annotated.forEach { assertFalse("append-only violated: $it", it.contains("PrepaidEventEntity")) }
    }

    @Test fun `every query touching prepaid_events is a select`() {
        val queries = Regex("""@Query\(\"\"\"(.*?)\"\"\"\)|@Query\("(.*?)"\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(src).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        assertTrue(queries.isNotEmpty())
        queries.filter { it.contains("prepaid_events") }.forEach { q ->
            assertTrue("non-select on prepaid_events: $q", q.trim().uppercase().startsWith("SELECT"))
        }
    }
}
