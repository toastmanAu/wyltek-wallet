package com.wyltek.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The uniffi bindings (package `com.wyltek.wallet.core.native`) live only in `:wallet-core`.
 *
 * A second copy in `:app` compiles under the Compose compiler, which adds a `$stable` field to
 * every class and makes app classes holding them (e.g. `TapClass.Resume(slot: SlotResult)`)
 * read `SlotResult.$stable` in their static initialiser. At runtime the dex loader picks the
 * `:wallet-core` copy, which has no such field: `NoSuchFieldError` on the first prepaid re-tap
 * (device checkpoint 2026-10-14). JVM tests can't reproduce the classloading, so pin the cause.
 */
class BindingsSingleSourceTest {
    @Test fun `app module declares no classes in the bindings package`() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.useLines { lines -> lines.any { it.trim() == "package com.wyltek.wallet.core.native" } } }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
