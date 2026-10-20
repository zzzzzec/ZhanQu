package dev.zhanqu.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    private fun place(x: Int, y: Int, width: Int = 188, height: Int = 80, snap: Boolean = false) =
        OverlayPlacement.constrain(x, y, width, height, 0, 24, 360, 776, 8, snap)

    @Test fun `drag cannot escape safe viewport`() {
        assertEquals(OverlayPlacement.Position(8, 32), place(-100, -200))
        assertEquals(OverlayPlacement.Position(164, 688), place(900, 1000))
    }
    @Test fun `release docks to nearest edge while retaining height`() {
        assertEquals(OverlayPlacement.Position(8, 200), place(40, 200, snap = true))
        assertEquals(OverlayPlacement.Position(164, 200), place(130, 200, snap = true))
    }
    @Test fun `expanding panel repositions instead of clipping controls`() {
        assertEquals(OverlayPlacement.Position(52, 268), place(164, 688, width = 300, height = 500))
    }
    @Test fun `oversized panel stays at safe top left without invalid ranges`() {
        assertEquals(OverlayPlacement.Position(8, 32), place(100, 100, width = 600, height = 1000))
    }
    @Test fun `display cutout and side inset are respected`() {
        assertEquals(OverlayPlacement.Position(48, 28), OverlayPlacement.constrain(0, 0, 188, 80, 40, 20, 760, 350, 8))
    }
}
