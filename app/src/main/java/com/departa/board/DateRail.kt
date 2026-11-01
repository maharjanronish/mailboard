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
 * The date rail — a scope dial for days.
 *
 * One self-drawn View: no child views, no RecyclerView, no adapter. Only the
 * ~12 cells visible through the viewport are painted, so 600 days of range
 * cost exactly as much as 12. Real fling physics via [OverScroller]; every
 * gesture — drag, fling or tap — ends snapped to the cell under the fixed
 * amber reticle at the center. The selected day is simply
 * `round(scrollPx / cellW)`; the strip is dumb pixel space, dates are an
 * index into parallel arrays of precomputed day keys.
 *
 * Cells show the weekday abbreviation above the day number; the first day of
 * a month shows the month abbreviation instead, so sliding across a month
 * boundary reads naturally. Today carries a marker dot. The animation is
 * self-driving from onDraw (advance one scroller step per painted frame),
 * which is why this view never stalls and needs no Choreographer callback.
 */
class DateRail(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Fires whenever the day under the reticle changes, mid-gesture included. */
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
    private var dayNums = IntArray(0)
    private var topLabels = arrayOf<String>()
    private var monthFirst = BooleanArray(0)
    private var todayIdx = -1
    private var selIdx = -1

    private var downX = 0f
    private var downScroll = 0f
    private var dragged = false

    // ----------------------------------------------------------------- paints

    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val pDow = textPaint(mono, sp(9f)).apply { color = resColor(R.color.text_dim) }
    private val pDowSel = textPaint(monoBold, sp(10f)).apply { color = resColor(R.color.amber) }
    private val pMonth = textPaint(monoBold, sp(9f)).apply { color = resColor(R.color.amber) }
    private val pMonthSel = textPaint(monoBold, sp(10f)).apply { color = resColor(R.color.amber) }
    private val pDay = textPaint(mono, sp(17f)).apply { color = resColor(R.color.text_primary); alpha = 170 }
    private val pDaySel = textPaint(monoBold, sp(23f)).apply { color = resColor(R.color.amber) }
    private val pReticle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = resColor(R.color.amber) }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = resColor(R.color.amber) }

    // Geometry resolved in onSizeChanged (all dp, height is fixed in XML).
    private var reticleHalfW = 0f
    private var reticleTop = 0f
    private var reticleBot = 0f
    private var labelY = 0f
    private var dayY = 0f
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
        val nums = IntArray(count)
        val labels = Array(count) { "" }
        val firsts = BooleanArray(count)
        val cal = Calendar.getInstance().apply { timeInMillis = fromDayKey }
        for (i in 0 until count) {
            keys[i] = cal.timeInMillis
            nums[i] = cal.get(Calendar.DAY_OF_MONTH)
            firsts[i] = nums[i] == 1
            labels[i] = if (firsts[i]) mon[cal.get(Calendar.MONTH)] else dow[cal.get(Calendar.DAY_OF_WEEK)]
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        dayKeys = keys
        dayNums = nums
        topLabels = labels
        monthFirst = firsts
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
                    // Tap: pick the cell under the finger, not the reticle.
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

    /** Settle to the cell nearest the reticle. */
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
        reticleHalfW = dp(1.25f)
        reticleTop = dp(9f)
        reticleBot = h - dp(9f)
        labelY = dp(21f)
        dayY = h - dp(19f)
        dotY = h - dp(7f)
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
                val sel = i == selIdx
                val firstOfMonth = monthFirst[i]
                val labelPaint = when {
                    sel && firstOfMonth -> pMonthSel
                    sel -> pDowSel
                    firstOfMonth -> pMonth
                    else -> pDow
                }
                canvas.drawText(topLabels[i], cx, labelY, labelPaint)
                canvas.drawText(dayNums[i].toString(), cx, dayY, if (sel) pDaySel else pDay)
                if (i == todayIdx && !sel) canvas.drawCircle(cx, dotY, dotR, pDot)
            }
        }

        // The fixed reticle: everything slides under it, it never moves.
        val cx = width / 2f
        canvas.drawRoundRect(
            cx - reticleHalfW, reticleTop, cx + reticleHalfW, reticleBot,
            reticleHalfW, reticleHalfW, pReticle
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun maxScroll(): Float = if (dayKeys.isEmpty()) 0f else (dayKeys.size - 1) * cellW

    private fun clampScroll(v: Float): Float = v.coerceIn(0f, maxScroll())

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
    }
}
