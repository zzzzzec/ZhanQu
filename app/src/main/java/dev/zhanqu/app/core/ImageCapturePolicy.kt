package dev.zhanqu.app.core

/** Native screen bounds can already be clipped; compare with the original view layout dimensions. */
internal object ImageCapturePolicy {
    fun completeRegion(image: UiNode, display: UiBounds): UiBounds? {
        val width = image.layoutWidth?.takeIf { it > 0 } ?: return null
        val height = image.layoutHeight?.takeIf { it > 0 } ?: return null
        val visible = image.bounds.intersection(display)
        if (visible.width < 32 || visible.height < 32) return null
        // Permit only pixel-rounding differences, never an aspect-ratio guess.
        if (visible.width.toLong() + 2 < width || visible.height.toLong() + 2 < height) return null
        return visible
    }
}
