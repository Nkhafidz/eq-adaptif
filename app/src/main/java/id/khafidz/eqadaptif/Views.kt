package id.khafidz.eqadaptif

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.ln

/** Spektrum musik yang sedang diputar + garis koreksi AI (ungu). */
class SpectrumView(ctx: Context) : View(ctx) {
    var correction = FloatArray(10)

    private val d = resources.displayMetrics.density
    private val range = 10f
    private val padL = 30 * d
    private val padR = 10 * d
    private val padT = 10 * d
    private val padB = 20 * d
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2330.toInt(); strokeWidth = d }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B6275.toInt(); textSize = 10 * d }
    private val pSpec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FD1C5.toInt(); alpha = 80 }
    private val pCorr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB794F6.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB794F6.toInt() }

    private fun xf(f: Float): Float =
        padL + (width - padL - padR) * (ln(f / 20f) / ln(1000f))
    private fun y(db: Float): Float {
        val h = height - padT - padB
        return padT + h / 2f - db.coerceIn(-range, range) / (range * 2) * h
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (db in intArrayOf(-10, -5, 0, 5, 10)) {
            pGrid.alpha = if (db == 0) 255 else 120
            c.drawLine(padL, y(db.toFloat()), w - padR, y(db.toFloat()), pGrid)
            c.drawText((if (db > 0) "+" else "") + db, 2 * d, y(db.toFloat()) + 4 * d, pLabel)
        }
        pLabel.textAlign = Paint.Align.CENTER
        for (i in Bands.F10.indices) if (i % 2 == 0 || i == 9) c.drawText(Bands.L10[i], xf(Bands.F10[i]), h - 5 * d, pLabel)
        pLabel.textAlign = Paint.Align.LEFT

        val s = AppState.spectrum
        val bw = (w - padL - padR) / s.size
        val base = h - padB
        for (b in s.indices) {
            c.drawRect(padL + b * bw + d, base - s[b] * (h - padT - padB), padL + (b + 1) * bw - d, base, pSpec)
        }

        val p = Path()
        for (i in correction.indices) {
            val x = xf(Bands.F10[i]); val yy = y(correction[i])
            if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy)
        }
        c.drawPath(p, pCorr)
        for (i in correction.indices) c.drawCircle(xf(Bands.F10[i]), y(correction[i]), 4 * d, pDot)
    }
}
