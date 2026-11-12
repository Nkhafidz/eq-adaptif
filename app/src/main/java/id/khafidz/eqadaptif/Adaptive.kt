package id.khafidz.eqadaptif

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AI Auto-seimbang: mengukur keseimbangan nada (tonal balance) lagu yang sedang diputar,
 * lalu menghitung koreksi per band supaya mendekati target.
 *
 * Bekerja di sejumlah band apa pun (10 / 15 / 31). Semua perbandingan memakai "bentuk" spektrum
 * (dinormalisasi ke rata-rata 0 dB), jadi volume lagu tidak berpengaruh.
 */
class Adaptive(val freqs: FloatArray) {
    companion object {
        /** ±10 detik pada ~20 frame/detik. */
        const val FULL = 200
        const val READY = 40

        fun normalize(a: FloatArray): FloatArray {
            val m = a.average().toFloat()
            return FloatArray(a.size) { a[it] - m }
        }

        /** Perkiraan keseimbangan rata-rata musik modern (per-bin, dB), kemiringan ±-4.5 dB/oktaf. */
        fun defaultTarget(freqs: FloatArray): FloatArray = normalize(FloatArray(freqs.size) { i ->
            (-4.5 * ln(freqs[i] / 1000.0) / ln(2.0)).toFloat() - if (freqs[i] >= 14000f) 6f else 0f
        })

        /** Batas koreksi per band: ujung frekuensi lebih dijaga. */
        fun edgeLimit(f: Float): Float = when {
            f < 40f -> 0.5f
            f < 90f -> 0.8f
            f > 12000f -> 0.5f
            f > 7000f -> 0.85f
            else -> 1f
        }

        /** Ubah FFT 8-bit dari Visualizer menjadi daya rata-rata per band. */
        fun bandPowers(fft: ByteArray, samplingRateHz: Float, freqs: FloatArray): DoubleArray {
            val n = fft.size
            val bins = n / 2
            val binHz = samplingRateHz / n
            val mag = DoubleArray(bins)
            mag[0] = fft[0].toDouble() * fft[0].toDouble()
            for (k in 1 until bins) {
                val re = fft[2 * k].toDouble()
                val im = fft[2 * k + 1].toDouble()
                mag[k] = re * re + im * im
            }
            return DoubleArray(freqs.size) { i ->
                val f = freqs[i]
                // batas band: titik tengah geometris dengan band tetangga
                val lowEdge = if (i == 0) f / 1.41421356f else sqrt(f * freqs[i - 1])
                val highEdge = if (i == freqs.size - 1) f * 1.41421356f else sqrt(f * freqs[i + 1])
                var lo = ceil(lowEdge / binHz).toInt().coerceAtLeast(1)
                var hi = floor(highEdge / binHz).toInt().coerceAtMost(bins - 1)
                if (hi < lo) {
                    val k = (f / binHz).roundToInt().coerceIn(1, bins - 1)
                    lo = k; hi = k
                }
                var s = 0.0
                for (k in lo..hi) s += mag[k]
                s / (hi - lo + 1)
            }
        }
    }

    val n = freqs.size
    private val P = DoubleArray(n)
    var frames = 0
        private set
    val C = FloatArray(n)
    val defaultTarget = defaultTarget(freqs)

    fun reset() {
        P.fill(0.0)
        frames = 0
    }

    /** Tambah satu frame (daya per band, SEBELUM koreksi). Frame hening diabaikan. */
    fun addFrame(p: DoubleArray) {
        if (p.size != n || p.sum() < 1e-3) return
        frames++
        val a = if (frames < FULL) 1.0 / frames else 1.0 / FULL
        for (k in 0 until n) P[k] = (1 - a) * P[k] + a * p[k]
    }

    fun rawDb(): DoubleArray = DoubleArray(n) { 10 * log10(P[it] + 1e-12) }

    fun shape(): FloatArray {
        val r = rawDb()
        return normalize(FloatArray(n) { r[it].toFloat() })
    }

    /** Band yang praktis kosong (mis. terpotong kompresi) tidak dikejar. */
    fun emptyBands(): BooleanArray {
        val r = rawDb()
        val mx = r.maxOrNull() ?: 0.0
        return BooleanArray(n) { r[it] < mx - 45 }
    }

    val ready: Boolean get() = frames >= READY

    /** Penghalusan antar band tetangga supaya AI tidak "mengejar" satu instrumen saja. */
    private fun smooth(d: FloatArray): FloatArray {
        if (n <= 10) return d
        val passes = if (n >= 31) 2 else 1
        var x = d
        repeat(passes) {
            val y = FloatArray(n)
            for (k in 0 until n) {
                val a = x[maxOf(k - 1, 0)]; val b = x[k]; val c = x[minOf(k + 1, n - 1)]
                y[k] = 0.25f * a + 0.5f * b + 0.25f * c
            }
            x = y
        }
        return x
    }

    fun update(target: FloatArray, strengthPct: Int, limitDb: Int) {
        if (!ready || target.size != n) return
        val sh = shape()
        val empty = emptyBands()
        val s = strengthPct / 100f
        var d = FloatArray(n) { k ->
            if (empty[k]) 0f else {
                val lim = limitDb * edgeLimit(freqs[k])
                ((target[k] - sh[k]) * s).coerceIn(-lim, lim)
            }
        }
        d = normalize(smooth(d)) // halus, dan tidak mengubah volume keseluruhan
        for (k in 0 until n) {
            val lim = limitDb * edgeLimit(freqs[k])
            d[k] = d[k].coerceIn(-lim, lim)
        }
        val rate = if (frames < FULL) 0.25f else 0.08f // cepat menyesuaikan di awal lagu
        for (k in 0 until n) C[k] += (d[k] - C[k]) * rate
    }

    /** Target baru = bentuk lagu ini + koreksi yang sedang berjalan (apa yang Anda dengar). */
    fun makeTarget(includeCorrection: Boolean): FloatArray {
        val sh = shape()
        val empty = emptyBands()
        return normalize(FloatArray(n) { k ->
            if (empty[k]) defaultTarget[k] else sh[k] + if (includeCorrection) C[k] else 0f
        })
    }
}
