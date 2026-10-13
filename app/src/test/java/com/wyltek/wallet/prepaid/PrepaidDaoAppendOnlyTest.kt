package com.wyltek.wallet.prepaid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The audit table is append-only (D3 spec §3.2). Room annotations are not retained at runtime,
 *  so this test reads the DAO source. Gradle runs unit tests with the module dir as CWD. */
class PrepaidDaoAppendOnlyTest {
    private val src = File("src/main/java/com/wyltek/wallet/prepaid/db/PrepaidDao.kt").readText()

    private data class Fn(val annotation: String, val kind: String, val name: String, val params: String)

    /** Every Room-annotated function: annotation (with its argument list) through the closing
     *  paren of the parameter list, across lines. Annotation args never contain '@'. */
    private val fns: List<Fn> = Regex(
        """(@(Insert|Update|Upsert|Delete|Query|RawQuery)\b(?:\([^@]*?\))?)\s*(?:suspend\s+)?fun\s+(\w+)\s*\(([^)]*)\)""",
        RegexOption.DOT_MATCHES_ALL,
    ).findAll(src).map { Fn(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4]) }.toList()

    private fun Fn.takesEvent() = params.contains("PrepaidEventEntity")

    @Test fun `scan is not vacuous and the dao names the events table`() {
        assertTrue("no annotated functions found", fns.any { it.kind == "Update" && it.name == "updateCard" })
        assertTrue(fns.size >= 10)
        assertTrue(src.contains("prepaid_events", ignoreCase = true))
    }

    @Test fun `no update upsert or delete takes an event entity`() {
        fns.filter { it.kind in setOf("Update", "Upsert", "Delete") }.forEach {
            assertFalse("append-only violated: ${it.annotation} ${it.name}(${it.params})", it.takesEvent())
        }
    }

    @Test fun `event inserts never replace`() {
        fns.filter { it.kind == "Insert" && it.takesEvent() }.forEach {
            assertFalse("REPLACE on event insert: ${it.name}", it.annotation.contains("REPLACE", ignoreCase = true))
        }
    }

    @Test fun `no raw queries`() {
        assertTrue("@RawQuery is banned in the prepaid DAO", fns.none { it.kind == "RawQuery" } && !src.contains("@RawQuery"))
    }

    @Test fun `every query touching prepaid_events is a select`() {
        val queries = fns.filter { it.kind == "Query" }
        assertTrue(queries.isNotEmpty())
        queries.forEach { f ->
            val arg = f.annotation.substringAfter("(").trim()
            assertTrue("@Query must use a string literal: ${f.name}", arg.startsWith("\""))
            val q = arg.trim('"', ')', ' ', '\n')
            if (q.contains("prepaid_events", ignoreCase = true)) {
                assertTrue("non-select on prepaid_events: $q", q.trim().uppercase().startsWith("SELECT"))
            }
        }
    }
}
