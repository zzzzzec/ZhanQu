package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test

class OverlayPresentationPolicyTest {
    @Test fun `idle entry is quieter without dimming running or attention states`() {
        assertTrue(OverlayPresentationPolicy.entryOpacity(false, false) < OverlayPresentationPolicy.entryOpacity(true, false))
        assertEquals(230, OverlayPresentationPolicy.entryOpacity(false, true))
        assertEquals(230, OverlayPresentationPolicy.entryOpacity(true, true))
    }
    @Test fun `counts explain both units without a guessed total`() {
        assertEquals("2/5 页 · 8 条评论", OverlayPresentationPolicy.counts(2, 5, 8))
        assertEquals("2 页 · 0 条评论", OverlayPresentationPolicy.counts(2, null, 0))
        assertEquals("2 页 · 0 条评论", OverlayPresentationPolicy.counts(2, 0, 0))
    }
    @Test fun `stale totals never imply impossible completion`() {
        assertEquals("6 页 · 4 条评论", OverlayPresentationPolicy.counts(6, 5, 4))
        assertEquals("0 页 · 0 条评论", OverlayPresentationPolicy.counts(-1, -1, -1))
    }
}
