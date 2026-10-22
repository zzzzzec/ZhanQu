package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test

class ImageCapturePolicyTest {
    private val display = UiBounds(0, 0, 1080, 2340)
    private fun image(bounds: UiBounds, width: Int? = 1080, height: Int? = 1440) =
        UiNode("0", bounds = bounds, layoutWidth = width, layoutHeight = height)

    @Test fun fullPortraitAndLandscapeImagesAreAccepted() {
        val portrait = UiBounds(0, 250, 1080, 1690)
        assertEquals(portrait, ImageCapturePolicy.completeRegion(image(portrait), display))
        val landscape = UiBounds(0, 250, 1080, 790)
        assertEquals(landscape, ImageCapturePolicy.completeRegion(image(landscape, height = 540), display))
    }

    @Test fun parentClippedImageCannotBeCalledCompleteEvenInsideScreen() {
        assertNull(ImageCapturePolicy.completeRegion(image(UiBounds(0, 250, 1080, 605)), display))
    }

    @Test fun screenClippedImageIsRejected() {
        assertNull(ImageCapturePolicy.completeRegion(image(UiBounds(0, -800, 1080, 640)), display))
    }

    @Test fun unknownDimensionsDoNotClaimCompleteness() {
        assertNull(ImageCapturePolicy.completeRegion(image(UiBounds(0, 250, 1080, 1690), height = null), display))
    }

    @Test fun onlyRoundingToleranceIsAllowed() {
        assertNotNull(ImageCapturePolicy.completeRegion(image(UiBounds(0, 250, 1080, 1689)), display))
        assertNull(ImageCapturePolicy.completeRegion(image(UiBounds(0, 250, 1080, 1680)), display))
    }
}
