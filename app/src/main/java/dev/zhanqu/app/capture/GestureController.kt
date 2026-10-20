package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import dev.zhanqu.app.core.PeripheralTapPolicy
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
            WindowSafety.check(service, plan.clearance.androidRect(), targetWindowId)
            withTimeoutOrNull(5000) { dispatch(plan) } ?: false
        } finally { hideOverlay(false) }
    }
    /** Only called after a whole-node semantic click was blocked, never after a possibly executed click. */
    suspend fun peripheralTap(action: Rect, anchor: Rect, targetWindowId: Int, validateTarget: () -> Boolean): Boolean {
        hideOverlay(true)
        return try {
            val viewport = WindowSafety.contentViewport(service)
            val plan = PeripheralTapPolicy.plan(UiBounds(action.left, action.top, action.right, action.bottom),
                UiBounds(anchor.left, anchor.top, anchor.right, anchor.bottom),
                UiBounds(viewport.left, viewport.top, viewport.right, viewport.bottom),
                (8 * service.resources.displayMetrics.density).toInt().coerceAtLeast(8)) ?: return false
            if (!WindowSafety.permitsPeripheralTap(service, action, targetWindowId, plan) || !validateTarget()) return false
            // Recheck all windows after node validation and immediately before dispatch, on the service thread.
            if (!WindowSafety.permitsPeripheralTap(service, action, targetWindowId, plan)) return false
            withTimeoutOrNull(5000) {
                suspendCancellableCoroutine { continuation ->
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val path = Path().apply { moveTo(plan.x, plan.y) }
                    ownAction()
                    val accepted = service.dispatchGesture(GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
                    }, null)
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
            } ?: false
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
