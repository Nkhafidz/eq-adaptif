package id.khafidz.eqadaptif

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mesin koreksi AI yang berjalan di atas EQ pilihan pengguna (Poweramp Equalizer, dll).
 *
 * Mesin utama: DynamicsProcessing dengan jumlah band sesuai pilihan (10/15/31), prioritas rendah
 * supaya tidak merebut kendali dari EQ lain. Jika tidak tersedia atau kalah prioritas, otomatis
 * memakai equalizer dasar Android (jumlah band ditentukan HP, biasanya 5).
 *
 * Efek hanya dinyalakan jika koreksinya berarti, dan penguat volume ikut menyala bersamaan
 * untuk mengimbangi turunnya volume saat efek aktif di sebagian HP.
 */
class CompanionFx(private val freqs: FloatArray) {
    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private var eqCenters = FloatArray(0)
    private var eqMin = -1500
    private var eqMax = 1500
    private var loud: LoudnessEnhancer? = null
    private var loudFailed = false
    private var lastComp = -1f
    private var on = false
    private var last: FloatArray? = null

    /** Koreksi yang benar-benar terpasang, dalam band analisis, untuk dikurangkan saat analisis. */
    var applied = FloatArray(freqs.size)
        private set

    val active: Boolean get() = on

    val engineName: String
        get() = when {
            dp != null -> "${freqs.size} band (mesin detail)"
            eq != null -> "${eqCenters.size} band (equalizer dasar HP)"
            else -> "tidak tersedia"
        }

    init {
        if (!startDp()) startEq()
    }

    private fun startDp(): Boolean {
        return try {
            val n = freqs.size
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, n, false, 0, false, 0, true
            ).build()
            val d = DynamicsProcessing(0, 0, cfg) // prioritas rendah, sesi global
            if (!d.hasControl()) { d.release(); return false }
            for (i in 0 until n) {
                val edge = if (i == n - 1) 20000f else sqrt(freqs[i] * freqs[i + 1])
                d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edge, 0f))
            }
            // limiter menjaga puncak saat koreksi menaikkan band
            d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 0.5f, 80f, 50f, -0.3f, 0f))
            d.setEnabled(false)
            dp = d
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startEq() {
        try {
            val e = Equalizer(0, 0)
            val b = e.numberOfBands.toInt()
            eqCenters = FloatArray(b) { e.getCenterFreq(it.toShort()) / 1000f }
            val r = e.bandLevelRange
            eqMin = r[0].toInt(); eqMax = r[1].toInt()
            e.setEnabled(false)
            eq = e
        } catch (_: Exception) {}
    }

    /** true jika mesin masih memegang kendali (EQ lain bisa mengambil alih). */
    val hasControl: Boolean
        get() = try { dp?.hasControl() ?: eq?.hasControl() ?: false } catch (_: Exception) { false }

    /** Mesin detail kalah prioritas (mis. EQ lain memakai jenis efek yang sama): pindah ke equalizer dasar. */
    fun fallbackIfNeeded() {
        val d = dp ?: return
        if (try { d.hasControl() } catch (_: Exception) { false }) return
        try { d.release() } catch (_: Exception) {}
        dp = null
        on = false
        last = null
        startEq()
    }

    fun apply(corr: FloatArray, compDb: Float) {
        val needed = corr.any { abs(it) >= 0.5f }
        if (needed != on) {
            try { dp?.setEnabled(needed); eq?.setEnabled(needed) } catch (_: Exception) {}
            on = needed
        }
        setCompensation(if (on) compDb else 0f)

        val d = dp
        val e = eq
        if (d != null) {
            val l = last
            for (i in corr.indices) {
                if (l == null || abs(corr[i] - l[i]) >= 0.1f) {
                    val edge = if (i == corr.size - 1) 20000f else sqrt(freqs[i] * freqs[i + 1])
                    try { d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edge, corr[i])) } catch (_: Exception) {}
                }
            }
            last = corr.copyOf()
            applied = if (on) corr.copyOf() else FloatArray(corr.size)
        } else if (e != null) {
            val g = Bands.interp(corr, freqs, eqCenters)
            val mb = IntArray(g.size) { (g[it] * 100).toInt().coerceIn(eqMin, eqMax) }
            for (i in mb.indices) try { e.setBandLevel(i.toShort(), mb[i].toShort()) } catch (_: Exception) {}
            val actual = FloatArray(mb.size) { if (on) mb[it] / 100f else 0f }
            applied = Bands.interp(actual, eqCenters, freqs)
        }
    }

    private fun setCompensation(db: Float) {
        if (loudFailed || abs(db - lastComp) < 0.05f) return
        lastComp = db
        try {
            if (db <= 0.05f) {
                loud?.setEnabled(false)
                return
            }
            val le = loud ?: LoudnessEnhancer(0).also { loud = it }
            le.setTargetGain((db * 100).toInt())
            le.setEnabled(true)
        } catch (_: Exception) {
            loudFailed = true
        }
    }

    fun release() {
        try { dp?.setEnabled(false); dp?.release() } catch (_: Exception) {}
        try { eq?.setEnabled(false); eq?.release() } catch (_: Exception) {}
        try { loud?.release() } catch (_: Exception) {}
        dp = null; eq = null; loud = null
    }
}
