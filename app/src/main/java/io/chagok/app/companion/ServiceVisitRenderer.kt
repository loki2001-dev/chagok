package io.chagok.app.companion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import io.chagok.app.domain.PlaceKind
import kotlin.math.abs
import kotlin.math.sin

/** Small articulated pixel characters. Positions are anchored to the rendered car in either layout. */
internal class ServiceVisitRenderer {
    private val paint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hose = Path()
    private val ink = Color.rgb(29, 43, 52)
    private val skin = Color.rgb(245, 192, 143)
    private val teal = Color.rgb(53, 193, 163)
    private val orange = Color.rgb(255, 180, 74)

    private fun block(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color; c.drawRect(x, y, x + w, y + h, paint)
    }
    private fun line(c: Canvas, x: Float, y: Float, tx: Float, ty: Float, color: Int, width: Float) {
        linePaint.color = color; linePaint.strokeWidth = width; linePaint.style = Paint.Style.STROKE
        c.drawLine(x, y, tx, ty, linePaint)
    }

    fun draw(c: Canvas, layout: SceneLayout, carX: Float, carY: Float, carW: Float, carH: Float,
             groundY: Float, frame: ServiceVisitAnimation.Frame) {
        val fuel = frame.kind == PlaceKind.FUEL
        val working = frame.phase == ServiceVisitAnimation.Phase.WORK
        val accent = if (fuel) teal else orange
        val scale = if (layout.landscape) 1.55f else 1.12f
        // Fuel at the rear quarter; mechanic at the hood. Stand beside, never on the car.
        val targetX = if (layout.landscape) carX + carW * (if (fuel) -.32f else .53f)
            else carX + carW * .5f + 15f
        val targetY = if (layout.landscape) groundY + 12f
            else carY + carH * (if (fuel) .28f else -.25f)
        val stationX = if (layout.landscape) {
            if (fuel) (carX - carW * .5f - 88f).coerceAtLeast(36f)
            else (carX + carW * .5f + 112f).coerceAtMost(layout.width - 40f)
        } else layout.width - 32f
        val stationY = if (layout.landscape) groundY + 18f else targetY + 78f
        val startX = stationX + if (fuel && layout.landscape) 27f else -24f
        val startY = stationY + 7f
        val p = frame.approach
        val workerX = startX + (targetX - startX) * p
        val workerY = startY + (targetY - startY) * p
        val facing = if (layout.landscape && fuel) 1f else -1f

        // A compact pump or wheeled tool chest remains at the edge during the whole visit.
        c.save(); c.translate(stationX, stationY); c.scale(scale, scale)
        paint.color = Color.argb(75, 5, 12, 18); c.drawOval(-17f, -4f, 18f, 5f, paint)
        if (fuel) {
            block(c, -12f, -39f, 24f, 39f, ink)
            block(c, -10f, -37f, 20f, 33f, Color.rgb(235, 241, 228))
            block(c, -10f, -16f, 20f, 12f, accent)
            block(c, -7f, -32f, 14f, 10f, ink)
            block(c, -5f, -29f, 8f, 3f, teal)
            block(c, -15f, -3f, 30f, 4f, ink)
        } else {
            block(c, -15f, -20f, 30f, 18f, ink)
            block(c, -13f, -18f, 26f, 14f, Color.rgb(194, 82, 57))
            for (y in listOf(-15f, -10f)) block(c, -9f, y, 18f, 2f, orange)
            block(c, -12f, -2f, 5f, 5f, ink); block(c, 7f, -2f, 5f, 5f, ink)
            line(c, -6f, -22f, -3f, -33f, Color.LTGRAY, 3f)
            line(c, 7f, -22f, 10f, -31f, orange, 3f)
        }
        c.restore()

        val handX = workerX + facing * 12f * scale
        val handY = workerY - 19f * scale
        if (fuel) {
            hose.reset(); hose.moveTo(stationX + 10f * scale, stationY - 25f * scale)
            hose.cubicTo(stationX + 40f, stationY + 28f, workerX - 30f, workerY + 15f, handX, handY)
            linePaint.style = Paint.Style.STROKE; linePaint.strokeWidth = 3.5f; linePaint.color = ink
            c.drawPath(hose, linePaint)
        }

        paint.color = Color.argb(80, 5, 12, 18)
        c.drawOval(workerX - 12f * scale, workerY - 3f, workerX + 12f * scale, workerY + 4f, paint)
        val stride = if (working) 0f else sin(frame.seconds * 11f) * 3f
        val bob = if (working) { if (fuel) sin(frame.seconds * 3f) * .4f else abs(sin(frame.seconds * 7f)) * 1.6f }
            else abs(stride) * .35f
        c.save(); c.translate(workerX, workerY - bob * scale); c.scale(scale * facing, scale)
        block(c, -7f, -12f, 6f, 10f + stride, ink)
        block(c, 2f, -12f, 6f, 10f - stride, ink)
        block(c, -8f, -3f + stride, 8f, 3f, Color.rgb(14, 24, 31))
        block(c, 2f, -3f - stride, 9f, 3f, Color.rgb(14, 24, 31))
        block(c, -9f, -28f, 18f, 17f, ink)
        block(c, -7f, -27f, 14f, 15f, accent)
        block(c, -7f, -26f, 2f, 13f, if (fuel) Color.rgb(28, 137, 124) else Color.rgb(201, 119, 44))
        block(c, -6f, -13f, 13f, 2f, ink)
        block(c, -5f, -25f, 3f, 11f, Color.rgb(237, 239, 219))
        block(c, 4f, -24f, 3f, 5f, ink)
        block(c, 4f, -24f, 3f, 1f, Color.rgb(247, 233, 180))
        block(c, -5f, -37f, 12f, 10f, skin)
        block(c, -5f, -35f, 3f, 6f, Color.rgb(113, 73, 55))
        block(c, -3f, -31f, 2f, 3f, Color.rgb(221, 157, 109))
        block(c, 6f, -33f, 3f, 4f, skin)
        block(c, 4f, -35f, 2f, 2f, ink)
        block(c, -7f, -40f, 15f, 5f, accent)
        block(c, -5f, -40f, 9f, 1f, Color.rgb(239, 237, 203))
        block(c, -7f, -36f, 19f, 2f, ink)
        block(c, -10f, -25f, 4f, 9f, accent)
        block(c, -10f, -17f, 4f, 4f, skin)
        val lift = if (working && !fuel) ((sin(frame.seconds * 9f) + 1f) * 5f) else 0f
        line(c, 6f, -25f, 12f, -19f - lift, accent, 5f)
        block(c, 10f, -21f - lift, 5f, 4f, skin)
        if (!fuel) {
            line(c, 14f, -20f - lift, 17f, -30f - lift, Color.LTGRAY, 3f)
            line(c, 13f, -31f - lift, 21f, -29f - lift, orange, 4f)
        } else block(c, 13f, -23f, 5f, 7f, ink)
        c.restore()

        if (fuel && working) {
            val portX = if (layout.landscape) carX - carW * .34f else carX + carW * .43f
            val portY = if (layout.landscape) carY + carH * .18f else carY + carH * .25f
            line(c, handX, handY, portX, portY, ink, 6f)
            line(c, handX, handY, portX, portY, teal, 3f)
            line(c, portX, portY, portX - 5f, portY - 4f, Color.LTGRAY, 3f)
        }

        if (working && !fuel) {
            val hit = (frame.seconds * 3f) % 1f
            if (hit < .5f) {
                val x = if (layout.landscape) carX + carW * .43f else carX + carW * .38f
                val y = if (layout.landscape) carY - carH * .12f else carY - carH * .36f
                val spread = 5f + hit * 15f
                for (i in -1..1) line(c, x + i * 5, y - 4, x + i * spread, y - spread - 5, orange, 2f)
            }
        }
    }
}
