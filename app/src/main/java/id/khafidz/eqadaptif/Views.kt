package id.khafidz.eqadaptif

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

private const val C_GRID = 0xFF1F2330.toInt()
private const val C_LABEL = 0xFF5B6275.toInt()
private const val C_ACCENT = 0xFFFFB547.toInt()
private const val C_TEAL = 0xFF4FD1C5.toInt()
private const val C_VIOLET = 0xFFB794F6.toInt()
private const val C_TEXT = 0xFFE9ECF3.toInt()

/**
 * Grafik EQ ala Poweramp: 10 titik yang bisa digeser ke atas/bawah, kurva hasil akhir,
 * garis koreksi AI (ungu putus-putus), dan spektrum musik di belakangnya.
 */
class EqCurveView(ctx: Context) : View(ctx) {
    var userGains = FloatArray(10)               // posisi titik (preset)
    var totalGains = FloatArray(10)              // preset + nada + koreksi AI
    var correction: FloatArray? = null           // koreksi AI (null = tidak tampil)
    var onBandChange: ((Int, Float) -> Unit)? = null
    var onBandRelease: (() -> Unit)? = null

    private val range = 12f
    private val d = resources.displayMetrics.density
    private val padL = 34 * d
    private val padR = 14 * d
    private val padT = 14 * d
    private val padB = 22 * d
    private var dragging = -1

    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_GRID; strokeWidth = d }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_LABEL; textSize = 10 * d }
    private val pSpec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_TEAL; alpha = 70 }
    private val pCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_ACCENT; style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCorr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_VIOLET; style = Paint.Style.STROKE; strokeWidth = 2 * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_ACCENT }
    private val pDotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0E0F13.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }
    private val pVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT; textSize = 12 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pBubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2E3A.toInt() }

    private val n: Int get() = userGains.size
    private val labelSet = setOf(31.5f, 31f, 62f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private fun x(i: Int): Float = padL + (width - padL - padR) * i / (n - 1f).coerceAtLeast(1f)
    private fun y(db: Float): Float {
        val h = height - padT - padB
        return padT + h / 2f - db.coerceIn(-range - 3, range + 3) / (range * 2) * h
    }
    private fun dbAt(yy: Float): Float {
        val h = height - padT - padB
        val v = (padT + h / 2f - yy) / h * range * 2
        return ((v * 2).roundToInt() / 2f).coerceIn(-range, range)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // grid dB
        for (db in intArrayOf(-12, -6, 0, 6, 12)) {
            val yy = y(db.toFloat())
            pGrid.alpha = if (db == 0) 255 else 140
            c.drawLine(padL, yy, w - padR, yy, pGrid)
            c.drawText((if (db > 0) "+" else "") + db, 4 * d, yy + 4 * d, pLabel)
        }
        // label frekuensi
        pLabel.textAlign = Paint.Align.CENTER
        val fr = Bands.freqsFor(n)
        for (i in 0 until n) if (fr[i] in labelSet) c.drawText(Bands.label(fr[i]), x(i), h - 6 * d, pLabel)
        pLabel.textAlign = Paint.Align.LEFT

        // spektrum
        val s = AppState.spectrum
        val bw = (w - padL - padR) / s.size
        val base = h - padB
        for (b in s.indices) {
            val top = base - s[b] * (h - padT - padB)
            c.drawRect(padL + b * bw + d, top, padL + (b + 1) * bw - d, base, pSpec)
        }

        // kurva total (spline halus)
        val path = smooth(totalGains)
        val fill = Path(path)
        fill.lineTo(x(n - 1), y(0f))
        fill.lineTo(x(0), y(0f))
        fill.close()
        pFill.shader = LinearGradient(0f, padT, 0f, h - padB, 0x55FFB547, 0x05FFB547, Shader.TileMode.CLAMP)
        c.drawPath(fill, pFill)
        c.drawPath(path, pCurve)

        // koreksi AI
        correction?.let { c.drawPath(smooth(it), pCorr) }

        // titik preset
        val dot = if (n > 10) 5 * d else 8 * d
        for (i in 0 until n) {
            val r = if (i == dragging) dot + 3 * d else dot
            c.drawCircle(x(i), y(userGains[i]), r, pDot)
            c.drawCircle(x(i), y(userGains[i]), r, pDotRing)
        }
        // nilai saat digeser
        if (dragging >= 0) {
            val v = userGains[dragging]
            val txt = Bands.label(Bands.freqsFor(n)[dragging]) + " " + (if (v > 0) "+" else "") + String.format("%.1f", v)
            val bx = x(dragging).coerceIn(padL + 46 * d, w - padR - 46 * d)
            val by = (y(v) - 26 * d).coerceAtLeast(padT + 10 * d)
            c.drawRoundRect(RectF(bx - 46 * d, by - 14 * d, bx + 46 * d, by + 6 * d), 8 * d, 8 * d, pBubble)
            c.drawText(txt, bx, by, pVal)
        }
    }

    private fun smooth(g: FloatArray): Path {
        val p = Path()
        if (g.size != n || n < 2) return p
        p.moveTo(x(0), y(g[0]))
        for (i in 0 until n - 1) {
            val x0 = x(i); val y0 = y(g[i])
            val x1 = x(i + 1); val y1 = y(g[i + 1])
            val yPrev = y(g[maxOf(i - 1, 0)])
            val yNext = y(g[minOf(i + 2, n - 1)])
            val cx1 = x0 + (x1 - x0) / 3f
            val cy1 = y0 + (y1 - yPrev) / 6f
            val cx2 = x1 - (x1 - x0) / 3f
            val cy2 = y1 - (yNext - y0) / 6f
            p.cubicTo(cx1, cy1, cx2, cy2, x1, y1)
        }
        return p
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = 0
                var bestD = Float.MAX_VALUE
                for (i in 0 until n) {
                    val dd = abs(e.x - x(i))
                    if (dd < bestD) { bestD = dd; best = i }
                }
                dragging = best
                parent?.requestDisallowInterceptTouchEvent(true)
                move(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging >= 0) move(e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                onBandRelease?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(yy: Float) {
        val v = dbAt(yy)
        if (v != userGains[dragging]) {
            userGains[dragging] = v
            onBandChange?.invoke(dragging, v)
        }
        invalidate()
    }
}

/** Kenop putar: geser ke atas/bawah (atau ke kanan/kiri) untuk mengubah nilai, ketuk dua kali untuk 0. */
class KnobView(ctx: Context, private val label: String, private val min: Float, private val max: Float,
               private val color: Int, private val unit: String = " dB") : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0xFF2A2E3A.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND
    }
    private val pBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D2029.toInt() }
    private val pTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val pVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT; textSize = 14 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pLab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A91A3.toInt(); textSize = 11 * d; textAlign = Paint.Align.CENTER
    }
    private var startY = 0f
    private var startX = 0f
    private var startV = 0f
    private var lastTap = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 1.15f).toInt().coerceAtMost((150 * d).toInt()))
    }

    override fun onDraw(c: Canvas) {
        val size = minOf(width.toFloat(), height - 22 * d)
        val cx = width / 2f
        val cy = size / 2f + 4 * d
        val r = size / 2f - 10 * d
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        pArc.color = color
        pTick.color = color
        c.drawArc(oval, 135f, 270f, false, pTrack)
        val frac = (value - min) / (max - min)
        val zeroFrac = ((0f - min) / (max - min)).coerceIn(0f, 1f)
        val a0 = 135f + 270f * zeroFrac
        val a1 = 135f + 270f * frac
        c.drawArc(oval, minOf(a0, a1), abs(a1 - a0), false, pArc)
        c.drawCircle(cx, cy, r - 10 * d, pBody)
        val ang = Math.toRadians(a1.toDouble())
        val ri = r - 22 * d
        c.drawLine(cx, cy, cx + (ri * kotlin.math.cos(ang)).toFloat(), cy + (ri * kotlin.math.sin(ang)).toFloat(), pTick)
        val txt = (if (value > 0 && min < 0) "+" else "") +
            (if (unit == "%") value.roundToInt().toString() else String.format("%.1f", value)) + unit
        c.drawText(txt, cx, cy + r + 6 * d, pVal)
        c.drawText(label, cx, height - 4 * d, pLab)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTap < 300) {
                    value = if (min < 0) 0f else min
                    onChange?.invoke(value)
                }
                lastTap = now
                startY = e.y; startX = e.x; startV = value
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = ((startY - e.y) + (e.x - startX)) / (160 * d) * (max - min)
                val step = if (max - min > 50) 1f else 0.5f
                val nv = (((startV + delta) / step).roundToInt() * step).coerceIn(min, max)
                if (nv != value) { value = nv; onChange?.invoke(nv) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
