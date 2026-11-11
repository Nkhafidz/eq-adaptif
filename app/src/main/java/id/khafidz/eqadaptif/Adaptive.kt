package id.khafidz.eqadaptif

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Auto-seimbang: mengukur keseimbangan nada (tonal balance) lagu yang sedang diputar,
 * lalu menghitung koreksi per band supaya mendekati target. Preset pengguna tetap di atasnya.
 *
 * Semua perbandingan memakai "bentuk" spektrum (dinormalisasi ke rata-rata 0 dB),
 * jadi volume lagu tidak berpengaruh, hanya perbandingan bass : mid : treble.
 */
class Adaptive {
    companion object {
        /** ±10 detik pada ~20 frame/detik. */
        const val FULL = 200
        const val READY = 40

        fun normalize(a: FloatArray): FloatArray {
            val m = a.average().toFloat()
            return FloatArray(a.size) { a[it] - m }
        }

        /** Perkiraan keseimbangan rata-rata musik modern (per-bin, dB), kemiringan ±-4.5 dB/oktaf. */
        val DEFAULT_TARGET: FloatArray = normalize(FloatArray(10) { i ->
            (-4.5 * ln(Bands.FREQS[i] / 1000.0) / ln(2.0)).toFloat() - if (i == 9) 6f else 0f
        })

        /** Ubah FFT 8-bit dari Visualizer menjadi daya rata-rata per band. */
        fun bandPowers(fft: ByteArray, samplingRateHz: Float): DoubleArray {
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
            return DoubleArray(10) { i ->
                val f = Bands.FREQS[i]
                var lo = ceil(f / 1.41421356 / binHz).toInt().coerceAtLeast(1)
                var hi = floor(f * 1.41421356 / binHz).toInt().coerceAtMost(bins - 1)
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

    private val P = DoubleArray(10)
    var frames = 0
        private set
    val C = FloatArray(10)

    fun reset() {
        P.fill(0.0)
        frames = 0
    }

    /** Tambah satu frame (daya per band, SEBELUM EQ). Frame hening diabaikan. */
    fun addFrame(p: DoubleArray) {
        if (p.sum() < 1e-3) return
        frames++
        val a = if (frames < FULL) 1.0 / frames else 1.0 / FULL
        for (k in 0 until 10) P[k] = (1 - a) * P[k] + a * p[k]
    }

    fun rawDb(): DoubleArray = DoubleArray(10) { 10 * log10(P[it] + 1e-12) }

    fun shape(): FloatArray {
        val r = rawDb()
        return normalize(FloatArray(10) { r[it].toFloat() })
    }

    /** Band yang praktis kosong (mis. terpotong kompresi) tidak dikejar. */
    fun emptyBands(): BooleanArray {
        val r = rawDb()
        val mx = r.maxOrNull() ?: 0.0
        return BooleanArray(10) { r[it] < mx - 45 }
    }

    val ready: Boolean get() = frames >= READY

    fun update(target: FloatArray, strengthPct: Int, limitDb: Int) {
        if (!ready) return
        val sh = shape()
        val empty = emptyBands()
        val s = strengthPct / 100f
        var d = FloatArray(10) { k ->
            if (empty[k]) 0f else {
                val lim = limitDb * Bands.EDGE_LIMIT[k]
                ((target[k] - sh[k]) * s).coerceIn(-lim, lim)
            }
        }
        d = normalize(d) // jangan mengubah volume keseluruhan
        for (k in 0 until 10) {
            val lim = limitDb * Bands.EDGE_LIMIT[k]
            d[k] = d[k].coerceIn(-lim, lim)
        }
        val rate = if (frames < FULL) 0.25f else 0.08f // cepat menyesuaikan di awal lagu
        for (k in 0 until 10) C[k] += (d[k] - C[k]) * rate
    }

    /** Target baru = bentuk lagu ini + koreksi yang sedang berjalan (apa yang Anda dengar, tanpa preset). */
    fun makeTarget(includeCorrection: Boolean): FloatArray {
        val sh = shape()
        val empty = emptyBands()
        return normalize(FloatArray(10) { k ->
            if (empty[k]) DEFAULT_TARGET[k] else sh[k] + if (includeCorrection) C[k] else 0f
        })
    }
}
