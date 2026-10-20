package dev.zhanqu.app.capture

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small vector glyphs; no font glyph/emoji dependencies or bitmap assets. */
internal class OverlayIcon(private val name: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // A square intrinsic viewport lets ImageView FIT_CENTER preserve the glyph aspect ratio.
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, a: Float, b: Float) = canvas.drawLine(x, y, a, b, paint)
        fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); canvas.drawPath(p, paint) }
        when (name) {
            "entry" -> {
                path(8f, 4f, 4f, 4f, 4f, 8f); path(16f, 4f, 20f, 4f, 20f, 8f)
                path(4f, 16f, 4f, 20f, 8f, 20f); path(20f, 16f, 20f, 20f, 16f, 20f)
                line(9f, 9f, 15f, 9f); line(9f, 12f, 15f, 12f); line(9f, 15f, 13f, 15f)
            }
            "play" -> path(9f, 5f, 19f, 12f, 9f, 19f, 9f, 5f)
            "pause" -> { line(9f, 6f, 9f, 18f); line(15f, 6f, 15f, 18f) }
            "stop" -> canvas.drawRoundRect(6f, 6f, 18f, 18f, 2f, 2f, paint)
            "collapse" -> path(7f, 14f, 12f, 9f, 17f, 14f)
            "close" -> { line(7f, 7f, 17f, 17f); line(17f, 7f, 7f, 17f) }
            "image" -> { canvas.drawRoundRect(4f, 4f, 20f, 20f, 3f, 3f, paint); canvas.drawCircle(9f, 9f, 1.4f, paint); path(5f, 18f, 11f, 12f, 14f, 15f, 17f, 12f, 20f, 15f) }
            "comment" -> path(5f, 5f, 19f, 5f, 19f, 16f, 11f, 16f, 6f, 20f, 6f, 16f, 5f, 16f, 5f, 5f)
            "reply" -> { path(10f, 5f, 5f, 10f, 10f, 15f); path(5f, 10f, 14f, 10f, 18f, 14f, 18f, 19f) }
            "library" -> { canvas.drawRoundRect(5f, 4f, 19f, 20f, 2f, 2f, paint); line(9f, 4f, 9f, 20f); line(12f, 9f, 16f, 9f); line(12f, 13f, 16f, 13f) }
            "info" -> { canvas.drawCircle(12f, 12f, 8f, paint); line(12f, 11f, 12f, 16f); line(12f, 7.5f, 12f, 7.6f) }
            "grip" -> for (x in listOf(9f, 15f)) for (y in listOf(7f, 12f, 17f)) canvas.drawCircle(x, y, .7f, paint)
            else -> { canvas.drawCircle(12f, 12f, 8f, paint); canvas.drawCircle(12f, 12f, 4.8f, paint) }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
