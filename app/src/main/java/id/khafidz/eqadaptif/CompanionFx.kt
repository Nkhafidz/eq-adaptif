package id.khafidz.eqadaptif

import android.media.audiofx.Equalizer

/**
 * Mode Pendamping: EQ Adaptif tidak memakai EQ sendiri. Hanya koreksi AI kecil yang dipasang,
 * memakai equalizer dasar Android (jenis efek berbeda dari Poweramp Equalizer / Wavelet),
 * sehingga bisa berjalan di atas EQ apa pun yang dipakai pengguna.
 */
class CompanionFx {
    private val eq: Equalizer = Equalizer(0, 0)   // prioritas rendah, sesi global
    private val bands: Int = eq.numberOfBands.toInt()
    private val centers: FloatArray = FloatArray(bands) { eq.getCenterFreq(it.toShort()) / 1000f }
    private val minMb: Int
    private val maxMb: Int
    private var last: IntArray? = null

    /** Koreksi yang benar-benar terpasang, dalam 10 band analisis (F10), untuk dikurangkan saat analisis. */
    var applied10 = FloatArray(10)
        private set

    init {
        val r = eq.bandLevelRange
        minMb = r[0].toInt()
        maxMb = r[1].toInt()
        eq.setEnabled(true)
    }

    val bandCount: Int get() = bands
    val hasControl: Boolean get() = try { eq.hasControl() } catch (_: Exception) { false }

    /** corr10: koreksi AI di 10 band (dB). */
    fun apply(corr10: FloatArray) {
        val g = resampleTo(corr10)
        val mb = IntArray(bands) { (g[it] * 100).toInt().coerceIn(minMb, maxMb) }
        val l = last
        for (i in 0 until bands) {
            if (l == null || kotlin.math.abs(mb[i] - l[i]) >= 10) {
                try { eq.setBandLevel(i.toShort(), mb[i].toShort()) } catch (_: Exception) {}
            }
        }
        last = mb
        // kembalikan ke 10 band untuk dikurangkan dari analisis
        val actual = FloatArray(bands) { mb[it] / 100f }
        applied10 = FloatArray(10) { j -> interp(actual, centers, Bands.F10[j]) }
    }

    private fun resampleTo(c10: FloatArray): FloatArray =
        FloatArray(bands) { interp(c10, Bands.F10, centers[it]) }

    private fun interp(v: FloatArray, fr: FloatArray, f: Float): Float {
        if (v.isEmpty()) return 0f
        if (f <= fr[0]) return v[0]
        if (f >= fr[fr.size - 1]) return v[v.size - 1]
        val lf = kotlin.math.ln(f.toDouble())
        for (i in 0 until fr.size - 1) {
            val a = kotlin.math.ln(fr[i].toDouble()); val b = kotlin.math.ln(fr[i + 1].toDouble())
            if (lf <= b) return (v[i] + (v[i + 1] - v[i]) * ((lf - a) / (b - a))).toFloat()
        }
        return v[v.size - 1]
    }

    fun release() {
        try { eq.setEnabled(false); eq.release() } catch (_: Exception) {}
    }
}
