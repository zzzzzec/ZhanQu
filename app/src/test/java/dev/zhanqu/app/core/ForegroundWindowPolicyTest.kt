package dev.zhanqu.app.core

import dev.zhanqu.app.core.WindowOcclusionPolicy.Kind
import dev.zhanqu.app.core.WindowOcclusionPolicy.Window
import org.junit.Assert.*
import org.junit.Test

class ForegroundWindowPolicyTest {
    private val own = "dev.zhanqu.app"
    private val target = Window(1, Kind.APPLICATION, 1, UiBounds(0, 0, 1080, 2340), rootPackage = "target.app", active = true, focused = true)
    private val side = Window(112, Kind.OTHER, 9, UiBounds(1010, 280, 1080, 2219), rootPackage = "sidegesturepad", platformType = -1)
    private fun identity(vararg windows: Window) = ForegroundWindowPolicy.identity(windows.toList(), 1, own)
    @Test fun sideGesturePadDoesNotBlock() { assertEquals(identity(target), identity(target, side)) }
    @Test fun centralAndUnknownFloatingWindowsDoNotBlock() {
        for (kind in Kind.entries) assertEquals(identity(target), identity(target, side.copy(kind = kind, bounds = target.bounds, rootPackage = null)))
    }
    @Test fun foreignGeometryLayerDisplayAndWindowSetDoNotChangeTargetIdentity() {
        assertEquals(identity(target), identity(target, side.copy(bounds = UiBounds.EMPTY, displayId = 99, layer = 999)))
        assertEquals(identity(target, side), identity(target, side.copy(id = 99, bounds = target.bounds)))
    }
    @Test fun realForeignFocusAndActivityStillBlock() {
        assertNull(identity(target, side.copy(focused = true)))
        assertNull(identity(target, side.copy(active = true)))
        assertNull(identity(target, side.copy(kind = Kind.APPLICATION, focused = true, layer = 0)))
    }
    @Test fun missingInactiveAndNonApplicationTargetsBlock() {
        assertNull(identity(side))
        assertNull(identity(target.copy(active = false, focused = false)))
        assertNull(identity(target.copy(kind = Kind.SYSTEM)))
    }
    @Test fun targetPackageDisplayAndBoundsArePartOfIdentity() {
        assertNotEquals(identity(target), identity(target.copy(rootPackage = "another.app")))
        assertNotEquals(identity(target), identity(target.copy(displayId = 1)))
        assertNotEquals(identity(target), identity(target.copy(bounds = UiBounds(10, 0, 1090, 2340))))
        assertNull(identity(target.copy(id = 4)))
    }
    @Test fun registeredOwnOverlayDoesNotStealTargetFocus() {
        assertEquals(identity(target), identity(target, side.copy(kind = Kind.ACCESSIBILITY_OVERLAY, rootPackage = own, registeredTitle = true, focused = true)))
        assertNull(identity(target, side.copy(kind = Kind.ACCESSIBILITY_OVERLAY, rootPackage = own, focused = true)))
    }
    private fun changes(pkg: String?, id: Int = 112, front: String? = "target.app", frontId: Int? = 1, eventFocused: Boolean? = null) =
        ForegroundWindowPolicy.changesSnapshotTarget(pkg, id, "target.app", 1, front, frontId, own, eventFocused)
    @Test fun unrelatedFloatingWindowEventsDoNotInvalidateSnapshot() {
        assertFalse(changes("sidegesturepad")); assertFalse(changes(null)); assertFalse(changes(own))
    }
    @Test fun trueSwitchAwayAndBackRetainsInvalidation() {
        var epoch = 0
        if (changes("another.app", 2, "another.app")) epoch++
        if (changes("target.app", 1)) epoch++
        assertEquals(2, epoch)
        assertTrue(changes("keyboard", front = null))
    }
    @Test fun sameAppWindowSwitchAwayAndBackIsRecorded() {
        var epoch = 0
        if (changes("target.app", 2, frontId = 2)) epoch++
        if (changes("target.app", 1)) epoch++
        assertEquals(2, epoch)
    }
    @Test fun ownAppNavigationStillInvalidatesButOwnedOverlayDoesNot() {
        assertTrue(changes(own, 9, own, 9)); assertFalse(changes(own))
    }
    @Test fun delayedTargetApplicationEventStillInvalidates() {
        assertTrue(changes("target.app", 2, eventFocused = true))
        assertTrue(changes("target.app", 2, eventFocused = null))
    }
    @Test fun targetPageChangesCountButSeparateSamePackageFloatDoesNot() {
        assertTrue(changes("target.app", 1)); assertTrue(changes("target.app", -1))
        assertFalse(changes("target.app", 112, eventFocused = false))
    }
}
