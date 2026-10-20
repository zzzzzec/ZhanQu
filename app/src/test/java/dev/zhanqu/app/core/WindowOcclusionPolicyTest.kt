package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test
import dev.zhanqu.app.core.WindowOcclusionPolicy.Kind
import dev.zhanqu.app.core.WindowOcclusionPolicy.Window

class WindowOcclusionPolicyTest {
    private val area = UiBounds(0, 250, 1080, 2105)
    private val full = UiBounds(0, 0, 1080, 2340)
    private val target = Window(1, Kind.APPLICATION, 1, full)
    private fun blocked(w: Window) = WindowOcclusionPolicy.blocker(listOf(target,w),1,area,"dev.zhanqu.app") != null
    @Test fun disjointVerifiedBarsDoNotObscureInterior() {
        assertFalse(blocked(Window(2,Kind.SYSTEM,3,full,listOf(UiBounds(0,0,1080,80),UiBounds(0,2250,1080,2340)), verifiedPeripheralSystemBars=true)))
    }
    @Test fun shadeUsesConservativeBounds() {
        assertTrue(blocked(Window(2,Kind.SYSTEM,3,full,listOf(full))))
    }
    @Test fun foreignSystemOverlayCannotUseTouchableHoles() {
        assertTrue(blocked(Window(2,Kind.SYSTEM,3,full,listOf(UiBounds(0,0,1080,80)),rootPackage="other.app")))
    }
    @Test fun unknownRootSystemWindowStaysConservative() {
        assertTrue(blocked(Window(2,Kind.SYSTEM,3,full,listOf(UiBounds(0,0,1080,80)))))
    }
    @Test fun emptyRegionFallsBackToBounds() {
        assertTrue(blocked(Window(2,Kind.SYSTEM,3,full,verifiedPeripheralSystemBars=true)))
    }
    @Test fun knownOwnNullRootWindowIsAccepted() {
        assertFalse(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full,registeredId=true)))
    }
    @Test fun ownTokenSurvivesWindowRecreationBeforeIdRegistration() {
        assertFalse(blocked(Window(3,Kind.ACCESSIBILITY_OVERLAY,3,full,registeredTitle=true)))
    }
    @Test fun observedForeignPackageVetoesRegisteredIdentity() {
        assertTrue(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full,rootPackage="foreign.app",registeredId=true,registeredTitle=true)))
    }
    @Test fun reusedIdWithDifferentTitleIsBlocked() {
        assertTrue(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full,registeredId=true,idTitleMatches=false)))
    }
    @Test fun retiredIdWithNullRootAndTitleIsNotTrusted() {
        assertTrue(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full,registeredId=true,retiredRegistration=true)))
    }
    @Test fun delayedRetiredWindowRequiresItsExactToken() {
        assertFalse(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full,registeredId=true,registeredTitle=true,retiredRegistration=true)))
    }
    @Test fun samePackageWithoutRegistrationIsNotTrusted() {
        assertTrue(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full,rootPackage="dev.zhanqu.app")))
    }
    @Test fun foreignAccessibilityOverlayAndImeBlock() {
        assertTrue(blocked(Window(2,Kind.ACCESSIBILITY_OVERLAY,3,full)))
        assertTrue(blocked(Window(2,Kind.INPUT_METHOD,3,full)))
    }
    @Test fun lowerLayerDoesNotObscureTarget() {
        assertFalse(blocked(Window(2,Kind.SYSTEM,0,full)))
    }

    private val edge = Window(112,Kind.OTHER,3,UiBounds(1010,280,1080,2219))
    private fun isolated(w: Window) = WindowOcclusionPolicy.blocker(listOf(target,w),1,area,"dev.zhanqu.app",WindowOcclusionPolicy.CaptureMode.TARGET_WINDOW)
    @Test fun unknownEdgeStillBlocksDisplayScreenshot() { assertTrue(blocked(edge)) }
    @Test fun isolatedWindowScreenshotExcludesNonFocusedEdgeWithoutTrustingIt() {
        assertNull(isolated(edge)); assertFalse(WindowOcclusionPolicy.isOwned(edge,"dev.zhanqu.app"))
    }
    @Test fun focusedOrActiveUnknownEdgeStillBlocksIsolatedCapture() {
        assertNotNull(isolated(edge.copy(active=true))); assertNotNull(isolated(edge.copy(focused=true)))
    }
    @Test fun centralUnknownPopupStillBlocksIsolatedCapture() { assertNotNull(isolated(edge.copy(bounds=full))) }
    @Test fun foreignAccessibilityOverlayImeAndApplicationDialogStillBlock() {
        listOf(Kind.ACCESSIBILITY_OVERLAY,Kind.INPUT_METHOD,Kind.APPLICATION).forEach { assertNotNull(isolated(edge.copy(kind=it))) }
    }
    @Test fun foreignSystemEdgeNotWhitelistedButExcludedFromIsolatedPixels() {
        val foreign=edge.copy(kind=Kind.SYSTEM,rootPackage="foreign.app")
        assertTrue(blocked(foreign)); assertNull(isolated(foreign))
    }
    @Test fun swipePathAvoidsEdgeAndIncludesClearance() {
        val plan=SwipePlan.create(area,true,true,36)
        assertEquals(864f,plan.startX,0f); assertEquals(216f,plan.endX,0f)
        assertNull(WindowOcclusionPolicy.blocker(listOf(target,edge),1,plan.clearance,"dev.zhanqu.app"))
        assertNotNull(WindowOcclusionPolicy.blocker(listOf(target,edge.copy(bounds=UiBounds(850,1000,900,1400))),1,plan.clearance,"dev.zhanqu.app"))
    }
    @Test fun verticalSwipeUsesCenterNotFullWidthContainer() {
        val plan=SwipePlan.create(full,false,true,36)
        assertEquals(540f,plan.startX,0f); assertEquals(plan.startX,plan.endX,0f)
        assertFalse(plan.clearance.intersects(edge.bounds))
    }
    @Test fun competingFocusedWindowBlocksEvenOutsideCaptureAndLowerLayer() {
        val dialog=Window(55,Kind.APPLICATION,0,UiBounds(0,0,50,50),focused=true)
        assertTrue(blocked(dialog)); assertNotNull(isolated(dialog))
    }
}
