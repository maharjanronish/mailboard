package com.departa.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.departa.R
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The date rail — a magnifier dial for days.
 *
 * One self-drawn View: no child views, no RecyclerView, no adapter. Only the
 * ~12 cells visible through the viewport are painted, so 600 days of range
 * cost exactly as much as 12. Real fling physics via [OverScroller]; every
 * gesture — drag, fling or tap — ends snapped so one cell lands dead center.
 *
 * There is no marker line: the magnification itself is the pointer. A cell's
 * scale, color and alpha are pure functions of its distance from the center
 * of the viewport, so while the finger slides (or the scroller settles) the
 * upcoming day visibly swells into focus as the old one shrinks away — the
 * selected day is simply the biggest one, `round(scrollPx / cellW)`. Past
 * the magnified zone the strip fades into darkness, like glass over a dial.
 *
 * Cells show the weekday abbreviation above the day number; the first day of
 * a month shows the month abbreviation instead, so sliding across a month
 * boundary reads naturally. Today carries a marker dot that dissolves as it
 * reaches the center (the big amber number makes it redundant there).
 */
class DateRail(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Fires whenever the day under the magnifier changes, mid-gesture included. */
    var onSelected: ((dayKey: Long) -> Unit)? = null

    // ------------------------------------------------------------------ state

    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingMin = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 8

    private val cellW = dp(CELL_DP)
    private var scrollPx = 0f
    private var flinging = false

    private var dayKeys = LongArray(0)
    private var dayTexts = arrayOf<String>()
    private var topLabels = arrayOf<String>()
    private var todayIdx = -1
    private var selIdx = -1

    private var downX = 0f
    private var downScroll = 0f
    private var dragged = false

    // ----------------------------------------------------------------- paints

    // The whole strip is bold — a board is bold — so selection never flips
    // the typeface; only size, color and alpha speak.
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val pLbl = textPaint(monoBold, sp(LBL_SP)).apply { color = resColor(R.color.text_dim) }
    private val pDay = textPaint(monoBold, sp(DAY_SP)).apply { color = resColor(R.color.text_primary) }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = resColor(R.color.amber) }

    private val amber = resColor(R.color.amber)
    private val dayFar = resColor(R.color.text_primary)
    private val lblFar = resColor(R.color.text_dim)
    private val lblBase = sp(LBL_SP)     // px, resolved once
    private val dayBase = sp(DAY_SP)     // px, resolved once

    // Geometry resolved in onSizeChanged (all dp, height is fixed in XML).
    private var labelY = 0f
    private var dayCY = 0f
    private var dotY = 0f
    private val dotR = dp(2f)

    // -------------------------------------------------------------------- api

    /**
     * Rebuilds the strip. [fromDayKey] must be local midnight; [count] is
     * consecutive days (Calendar arithmetic, so DST-proof); [todayKey] gets
     * the marker dot; [initialKey] is centered without animation.
     */
    fun setDays(fromDayKey: Long, count: Int, todayKey: Long, initialKey: Long) {
        val syms = DateFormatSymbols(Locale.getDefault())
        val dow = Array(8) { i -> if (i == 0) "" else syms.shortWeekdays[i].uppercase(Locale.getDefault()).take(3) }
        val mon = Array(12) { syms.shortMonths[it].uppercase(Locale.getDefault()).take(3) }

        val keys = LongArray(count)
        val texts = Array(count) { "" }
        val labels = Array(count) { "" }
        val cal = Calendar.getInstance().apply { timeInMillis = fromDayKey }
        for (i in 0 until count) {
            val num = cal.get(Calendar.DAY_OF_MONTH)
            keys[i] = cal.timeInMillis
            texts[i] = num.toString()
            labels[i] = if (num == 1) mon[cal.get(Calendar.MONTH)]
                        else dow[cal.get(Calendar.DAY_OF_WEEK)]
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        dayKeys = keys
        dayTexts = texts
        topLabels = labels
        todayIdx = keys.indexOfFirst { it == todayKey }
        selIdx = keys.indexOfFirst { it == initialKey }.let { if (it < 0) todayIdx else it }
        scrollPx = selIdx.coerceAtLeast(0) * cellW
        flinging = false
        scroller.abortAnimation()
        invalidate()
    }

    /** Centers [dayKey] (animated when [smooth]). No-op when already there. */
    fun selectDay(dayKey: Long, smooth: Boolean = true) {
        val idx = dayKeys.indexOfFirst { it == dayKey }
        if (idx < 0) return
        if (idx == selIdx && abs(scrollPx - idx * cellW) < 0.5f) return
        scroller.abortAnimation()
        flinging = false
        if (smooth) {
            val dist = abs(idx * cellW - scrollPx)
            val factor = (dist / (cellW * 12f)).coerceIn(0.35f, 1.6f)
            scroller.startScroll(
                scrollPx.roundToInt(), 0,
                (idx * cellW - scrollPx).roundToInt(), 0,
                (SNAP_MS * factor).roundToInt()
            )
            postInvalidateOnAnimation()
        } else {
            scrollPx = idx * cellW
            selIdx = idx
            invalidate()
            onSelected?.invoke(dayKeys[idx])
        }
    }

    // ----------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                flinging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(e)
                downX = e.x
                downScroll = scrollPx
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragged && abs(e.x - downX) > touchSlop) dragged = true
                if (dragged) {
                    scrollPx = clampScroll(downScroll - (e.x - downX))
                    invalidate()
                    updateSelection()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val v = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                if (e.actionMasked == MotionEvent.ACTION_UP && !dragged) {
                    performClick()
                    // Tap: pick the cell under the finger, not the magnifier.
                    val idx = ((scrollPx + (e.x - width / 2f)) / cellW).roundToInt()
                        .coerceIn(0, dayKeys.size - 1)
                    if (idx != selIdx) selectDay(dayKeys[idx], smooth = true) else snap()
                } else if (e.actionMasked == MotionEvent.ACTION_UP && abs(v) > flingMin) {
                    flinging = true
                    scroller.fling(
                        scrollPx.roundToInt(), 0, v.roundToInt(), 0,
                        0, maxScroll().roundToInt(), 0, 0
                    )
                    postInvalidateOnAnimation()
                } else {
                    // Slow release or CANCEL: just settle.
                    snap()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ----------------------------------------------------------------- engine

    /** Settle to the cell nearest the center of the magnifier. */
    private fun snap() {
        if (dayKeys.isEmpty()) return
        val target = (scrollPx / cellW).roundToInt().coerceIn(0, dayKeys.size - 1) * cellW
        scroller.startScroll(
            scrollPx.roundToInt(), 0,
            (target - scrollPx).roundToInt(), 0, SNAP_MS
        )
        postInvalidateOnAnimation()
    }

    private fun updateSelection() {
        if (dayKeys.isEmpty()) return
        val idx = (scrollPx / cellW).roundToInt().coerceIn(0, dayKeys.size - 1)
        if (idx != selIdx) {
            selIdx = idx
            onSelected?.invoke(dayKeys[idx])
        }
    }

    // ------------------------------------------------------------------- draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        labelY = dp(22f)
        dayCY = dp(46f)
        dotY = dp(68f)
    }

    override fun onDraw(canvas: Canvas) {
        // Self-driving animation: advance the scroller one step per frame.
        if (!scroller.isFinished) {
            if (scroller.computeScrollOffset()) {
                scrollPx = clampScroll(scroller.currX.toFloat())
                updateSelection()
            } else if (flinging) {
                flinging = false
                snap()
            }
            if (!scroller.isFinished) postInvalidateOnAnimation()
        }

        if (dayKeys.isNotEmpty()) {
            val half = width / 2f
            val first = (((scrollPx - half) / cellW).toInt() - 1).coerceIn(0, dayKeys.size - 1)
            val last = (((scrollPx + half) / cellW).toInt() + 1).coerceIn(0, dayKeys.size - 1)
            for (i in first..last) {
                val cx = half + i * cellW - scrollPx
                // Closeness: 1 dead center, 0 one cell away and beyond.
                val t = (1f - abs(cx - half) / cellW).coerceIn(0f, 1f)
                val edge = edgeFade(cx, half)

                // Top label (weekday, or month on the 1st).
                pLbl.textSize = lblBase * lerp(0.85f, 1.25f, t)
                pLbl.color = blend(lblFar, amber, t)
                pLbl.alpha = ((200f + 55f * t) * edge).roundToInt()
                canvas.drawText(topLabels[i], cx, labelY, pLbl)

                // Day number: magnified, vertically centered so it grows
                // symmetrically out of the dial's axis.
                pDay.textSize = dayBase * lerp(0.62f, 1.42f, t)
                pDay.color = blend(dayFar, amber, t)
                pDay.alpha = ((160f + 95f * t) * edge).roundToInt()
                val baseline = dayCY - (pDay.ascent() + pDay.descent()) / 2f
                canvas.drawText(dayTexts[i], cx, baseline, pDay)

                // Today dot dissolves as it reaches the magnified center.
                if (i == todayIdx) {
                    pDot.alpha = ((1f - t) * 255f * edge).roundToInt()
                    canvas.drawCircle(cx, dotY, dotR, pDot)
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun maxScroll(): Float = if (dayKeys.isEmpty()) 0f else (dayKeys.size - 1) * cellW

    private fun clampScroll(v: Float): Float = v.coerceIn(0f, maxScroll())

    /** 1 inside the magnified zone, fading to 0 [EDGE_FAR_CELLS] cells out. */
    private fun edgeFade(cx: Float, half: Float): Float {
        val d = abs(cx - half) / cellW
        return if (d <= EDGE_NEAR_CELLS) 1f
        else if (d >= EDGE_FAR_CELLS) 0f
        else 1f - (d - EDGE_NEAR_CELLS) / (EDGE_FAR_CELLS - EDGE_NEAR_CELLS)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Channel-wise ARGB blend from [far] to [near] by [t]; no allocations. */
    private fun blend(far: Int, near: Int, t: Float): Int {
        val f = (255 - (t * 255f).roundToInt())          // 255 at t=0, 0 at t=1
        val n = (t * 255f).roundToInt()
        val r = (((far ushr 16) and 0xFF) * f + ((near ushr 16) and 0xFF) * n) / 255
        val g = (((far ushr 8) and 0xFF) * f + ((near ushr 8) and 0xFF) * n) / 255
        val b = ((far and 0xFF) * f + (near and 0xFF) * n) / 255
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    private fun textPaint(tf: Typeface, size: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = size
            textAlign = Paint.Align.CENTER
        }

    private fun resColor(res: Int): Int = getContext().getColor(res)

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val CELL_DP = 48f
        private const val SNAP_MS = 260
        private const val DAY_SP = 17f      // base day size: ×0.62 far, ×1.42 center
        private const val LBL_SP = 9f       // base label size: ×0.85 far, ×1.25 center
        private const val EDGE_NEAR_CELLS = 2.1f
        private const val EDGE_FAR_CELLS = 3.3f
    }
}
