package com.wyltek.wallet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pure-CKB input filter that keeps ML-DSA (PQ) spend digests in
 * sync with the on-chain lock. A regression here resurrects lock error 46.
 */
class CellSelectionTest {

    @Test fun pure_cell_with_0x_data_is_selectable() {
        // Live empty cells report data as "0x", not null/"".
        assertTrue(CellSelection.isPureCkb(hasType = false, data = "0x"))
    }

    @Test fun pure_cell_with_null_data_is_selectable() {
        assertTrue(CellSelection.isPureCkb(hasType = false, data = null))
    }

    @Test fun pure_cell_with_empty_data_is_selectable() {
        assertTrue(CellSelection.isPureCkb(hasType = false, data = ""))
    }

    @Test fun sudt_cell_is_rejected() {
        // Has a type script → consuming it in a plain send burns tokens AND
        // diverges the PQ digest.
        assertFalse(CellSelection.isPureCkb(hasType = true, data = "0x"))
    }

    @Test fun type_cell_with_data_is_rejected() {
        assertFalse(CellSelection.isPureCkb(hasType = true, data = "0xa1b2"))
    }

    @Test fun cemp_notification_cell_is_rejected() {
        // No type script but 36-byte MessagePointer data → the exact cell that
        // triggered the harness code-46 bug when picked as a fee input.
        val pointer = "0x" + "ab".repeat(36)
        assertFalse(CellSelection.isPureCkb(hasType = false, data = pointer))
    }

    @Test fun data_without_0x_prefix_is_still_treated_as_data() {
        assertFalse(CellSelection.isPureCkb(hasType = false, data = "deadbeef"))
    }
}
