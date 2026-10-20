package dev.zhanqu.app.core

import dev.zhanqu.app.core.WindowOcclusionPolicy.Kind
import dev.zhanqu.app.core.WindowOcclusionPolicy.Window
import org.junit.Assert.*
import org.junit.Test

class PeripheralTapPolicyTest {
    private val viewport = UiBounds(0, 80, 1080, 2250)
    private val action = UiBounds(0, 800, 1080, 920)
    private val label = UiBounds(400, 830, 680, 890)
    private val target = Window(1, Kind.APPLICATION, 1, UiBounds(0, 0, 1080, 2340), active = true, focused = true)
    private val edge = Window(974, Kind.OTHER, 3, UiBounds(1010, 280, 1080, 2219), rootPackage = "com.samsung.android.sidegesturepad")
    private val plan = PeripheralTapPolicy.plan(action, label, viewport, 24)!!
    private fun allowed(vararg others: Window) = PeripheralTapPolicy.permits(listOf(target) + others, 1, action, viewport, plan, "dev.zhanqu.app")

    @Test fun fullWidthReplyActionIsBlockedButActualVisibleLabelTapIsClear() {
        assertNotNull(WindowOcclusionPolicy.blocker(listOf(target, edge), 1, action, "dev.zhanqu.app"))
        assertTrue(allowed(edge))
        assertEquals(540f, plan.x, 0f)
        assertEquals(860f, plan.y, 0f)
        assertFalse(plan.clearance.intersects(edge.bounds))
        assertFalse(WindowOcclusionPolicy.isOwned(edge, "dev.zhanqu.app"))
    }
    @Test fun neverTrustsSamsungOrAnyOtherPackageName() {
        assertTrue(allowed(edge.copy(rootPackage = "another.vendor.sidebar")))
        assertFalse(allowed(edge.copy(bounds = UiBounds(400, 800, 700, 920))))
        assertFalse(allowed(edge.copy(rootPackage = null)))
        assertFalse(allowed(edge.copy(rootPackage = "")))
    }
    @Test fun everyWindowIsCheckedNotOnlyFirstEdge() {
        assertFalse(allowed(edge, edge.copy(id = 975, bounds = label)))
        assertFalse(allowed(edge.copy(id = 975, bounds = label), edge))
        assertTrue(allowed(edge, edge.copy(id = 975, bounds = UiBounds(0, 200, 60, 2000))))
        assertFalse(allowed(edge, edge.copy(id = 975, bounds = UiBounds.EMPTY)))
        assertFalse(allowed(edge, edge.copy(id = 975, displayId = 3)))
    }
    @Test fun focusedWindowsBlockEvenWhenLowerOrOutsideAction() {
        assertFalse(allowed(edge.copy(active = true)))
        assertFalse(allowed(edge.copy(focused = true)))
        assertFalse(allowed(edge, edge.copy(id = 975, layer = 0, bounds = UiBounds(0, 0, 20, 20), focused = true)))
    }
    @Test fun otherWindowTypesCannotUseEdgeFallback() {
        listOf(Kind.APPLICATION, Kind.INPUT_METHOD, Kind.ACCESSIBILITY_OVERLAY).forEach { assertFalse(allowed(edge.copy(kind = it))) }
        assertTrue(allowed(edge.copy(kind = Kind.SYSTEM)))
    }
    @Test fun wideOrInteriorOverlayIsNotASideStrip() {
        assertFalse(allowed(edge.copy(bounds = UiBounds(900, 280, 1080, 2219))))
        assertFalse(allowed(edge.copy(bounds = UiBounds(700, 280, 770, 2219))))
        assertFalse(allowed(edge.copy(bounds = UiBounds(1010, 280, 2000, 2219))))
    }
    @Test fun touchableRegionHolesNeverClearCoveredTap() {
        assertFalse(allowed(edge.copy(bounds = label, systemRegions = listOf(UiBounds(0, 0, 1, 1)))))
    }
    @Test fun anchorMustBeWhollyVisibleContainedAndLargeEnoughForClearance() {
        assertNull(PeripheralTapPolicy.plan(action, UiBounds(1000, 830, 1100, 890), viewport, 24))
        assertNull(PeripheralTapPolicy.plan(action, UiBounds(400, 700, 680, 760), viewport, 24))
        assertNull(PeripheralTapPolicy.plan(action, UiBounds(400, 830, 410, 840), viewport, 24))
        assertNull(PeripheralTapPolicy.plan(action, label, viewport, 0))
    }
    @Test fun anchorUnderSideStripIsStillBlocked() {
        val coveredLabel = UiBounds(1012, 830, 1078, 890)
        val coveredPlan = PeripheralTapPolicy.plan(action, coveredLabel, viewport, 8)!!
        assertFalse(PeripheralTapPolicy.permits(listOf(target, edge), 1, action, viewport, coveredPlan, "dev.zhanqu.app"))
    }
    @Test fun missingOrInactiveTargetAndNoOriginalBlockerRejectFallback() {
        assertFalse(PeripheralTapPolicy.permits(listOf(edge), 1, action, viewport, plan, "dev.zhanqu.app"))
        assertFalse(PeripheralTapPolicy.permits(listOf(target.copy(active = false, focused = false), edge), 1, action, viewport, plan, "dev.zhanqu.app"))
        assertFalse(allowed())
        assertFalse(PeripheralTapPolicy.permits(listOf(target.copy(displayId = 2), edge.copy(displayId = 2)), 1, action, viewport, plan, "dev.zhanqu.app"))
    }
    @Test fun forgedPlanCannotDispatchOutsideValidatedEnvelope() {
        assertFalse(PeripheralTapPolicy.permits(listOf(target, edge), 1, action, viewport, plan.copy(x = 1050f), "dev.zhanqu.app"))
        assertFalse(PeripheralTapPolicy.permits(listOf(target, edge), 1, action, viewport, plan.copy(x = Float.NaN), "dev.zhanqu.app"))
    }
}
