package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import dev.zhanqu.app.core.SwipePlan
import dev.zhanqu.app.core.UiBounds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class GestureController(private val service: AccessibilityService, private val ownAction: () -> Unit, private val hideOverlay: (Boolean) -> Unit) {
    suspend fun swipe(region: Rect, horizontal: Boolean, forward: Boolean, targetWindowId: Int): Boolean {
        hideOverlay(true)
        return try {
            val safeRegion = WindowSafety.visibleArea(service, region)
            if (safeRegion.width() < 40 || safeRegion.height() < 40) return false
            val plan = SwipePlan.create(UiBounds(safeRegion.left,safeRegion.top,safeRegion.right,safeRegion.bottom), horizontal, forward,
                (12 * service.resources.displayMetrics.density).toInt().coerceAtLeast(12))
            WindowSafety.check(service, targetWindowId)
            withTimeoutOrNull(5000) { dispatch(plan) } ?: false
        } finally { hideOverlay(false) }
    }
    private suspend fun dispatch(plan: SwipePlan): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply { moveTo(plan.startX, plan.startY); lineTo(plan.endX, plan.endY) }
        ownAction()
        val accepted = service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 330)).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resume(false)
    }
}
