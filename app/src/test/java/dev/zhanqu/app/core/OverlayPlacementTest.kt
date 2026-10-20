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

    private fun dock(edge: OverlayPlacement.Edge, width: Int, height: Int = 56,
                     x: Int = 136, y: Int = 200, right: Int = 360, bottom: Int = 776) =
        OverlayPlacement.constrain(x, y, width, height, 0, 24, right, bottom, 8, edge = edge)

    @Test fun `right dock survives panel collapse instead of retaining panel left coordinate`() {
        val panel = dock(OverlayPlacement.Edge.RIGHT, 216)
        assertEquals(136, panel.x)
        val entry = dock(OverlayPlacement.Edge.RIGHT, 56, x = panel.x)
        assertEquals(296, entry.x)
        assertEquals(352, entry.x + 56)
        assertEquals(panel.y, entry.y)
    }
    @Test fun `both docks survive repeated expand collapse and running height changes`() {
        for (edge in OverlayPlacement.Edge.entries) {
            var point = dock(edge, 56)
            repeat(4) {
                for ((width, height) in listOf(216 to 164, 56 to 104, 216 to 148, 56 to 56)) {
                    point = dock(edge, width, height, x = point.x, y = point.y)
                    assertEquals(if (edge == OverlayPlacement.Edge.LEFT) 8 else 352 - width, point.x)
                    assertEquals(200, point.y)
                }
            }
        }
    }
    @Test fun `remembered right dock survives narrow viewport and rotation`() {
        val narrow = dock(OverlayPlacement.Edge.RIGHT, 216, right = 232)
        assertEquals(8, narrow.x)
        val landscape = dock(OverlayPlacement.Edge.RIGHT, 56, x = narrow.x, right = 800, bottom = 360)
        assertEquals(736, landscape.x)
        assertEquals(200, landscape.y)
    }
    @Test fun `drag remains free until release selects a new edge`() {
        val free = OverlayPlacement.constrain(110, 300, 56, 56, 0, 24, 360, 776, 8)
        assertEquals(110, free.x)
        val edge = OverlayPlacement.nearestEdge(free.x, 56, 0, 360, 8)
        assertEquals(OverlayPlacement.Edge.LEFT, edge)
        assertEquals(8, dock(edge, 216, x = free.x).x)
        assertEquals(OverlayPlacement.Edge.RIGHT, OverlayPlacement.nearestEdge(250, 56, 0, 360, 8))
    }
    @Test fun `docked position respects insets and clamps height without changing edge`() {
        val p = OverlayPlacement.constrain(48, 600, 216, 164, 40, 20, 760, 350, 8,
            edge = OverlayPlacement.Edge.RIGHT)
        assertEquals(OverlayPlacement.Position(536, 178), p)
        assertEquals(48, OverlayPlacement.constrain(p.x, p.y, 216, 164, 40, 20, 760, 350, 8,
            edge = OverlayPlacement.Edge.LEFT).x)
    }
}
