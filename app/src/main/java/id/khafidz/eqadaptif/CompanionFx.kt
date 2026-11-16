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
class CompanionFx(private val freqs: FloatArray, val mode: Int = 0) {
    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private var eqCenters = FloatArray(0)
    private var eqSigma = FloatArray(0)      // lebar tiap band (oktaf, sigma Gaussian)
    private var resp: Array<FloatArray>? = null  // respons tiap band di frekuensi analisis
    private var eqMin = -1500
    private var eqMax = 1500
    private var loud: LoudnessEnhancer? = null
    private var loudFailed = false
    private var lastComp = -1f
    private var on = false
    private var last: FloatArray? = null
    private var quietSince = 0L
    private var lastEqMb: IntArray? = null
    private var lastSend = 0L

    /** Jeda minimum antar pengiriman nilai band: setiap pengiriman membuat mesin efek menghitung ulang filter. */
    private val minIntervalMs = 8_000L
    private val minStepDb = 0.5f

    /** Koreksi yang benar-benar terpasang, dalam band analisis, untuk dikurangkan saat analisis. */
    var applied = FloatArray(freqs.size)
        private set

    /** Ada mesin yang benar-benar terpasang dan memegang kendali. */
    val available: Boolean get() = (dp != null || eq != null) && hasControl

    val active: Boolean get() = on && available

    val engineName: String
        get() = when {
            dp != null -> "${freqs.size} band (mesin detail)"
            eq != null -> "${eqCenters.size} band (equalizer dasar HP)"
            mode == 1 -> "tidak tersedia: mesin detail sedang dipakai EQ lain (mis. Poweramp Equalizer). Pilih \"Dasar\""
            else -> "tidak tersedia"
        }

    init {
        when (mode) {
            1 -> startDp()            // detail saja
            2 -> startEq()            // dasar saja
            else -> if (!startDp()) startEq()
        }
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
            // lebar band dari HP: rentang frekuensi tiap band -> lebar dalam oktaf
            eqSigma = FloatArray(b) { i ->
                try {
                    val r = e.getBandFreqRange(i.toShort())
                    val lo = (r[0] / 1000f).coerceAtLeast(10f)
                    val hi = (r[1] / 1000f).coerceAtLeast(lo * 1.2f)
                    (log2(hi / lo) / 2.355f).coerceIn(0.35f, 1.6f)   // FWHM -> sigma
                } catch (_: Exception) { 0.9f }
            }
            resp = Array(b) { i -> FloatArray(freqs.size) { k -> bump(freqs[k], eqCenters[i], eqSigma[i]) } }
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
        if (mode != 0) return
        val d = dp ?: return
        if (try { d.hasControl() } catch (_: Exception) { false }) return
        try { d.release() } catch (_: Exception) {}
        dp = null
        on = false
        last = null
        lastEqMb = null
        startEq()
    }

    /**
     * Menyalakan/mematikan efek membuat Android menyusun ulang jalur audio sesaat (suara terputus sepersekian detik).
     * Karena itu efek dinyalakan sekali saat koreksi pertama kali dibutuhkan, lalu dibiarkan menyala.
     * Efek hanya dimatikan jika AI dijeda, atau koreksi sudah hampir nol selama 30 detik berturut-turut.
     */
    fun apply(corr: FloatArray, compDb: Float, aiOn: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        val big = corr.any { abs(it) >= 0.7f }
        val tiny = corr.all { abs(it) < 0.2f }
        quietSince = if (tiny) (if (quietSince == 0L) now else quietSince) else 0L
        val want = when {
            !aiOn || !available -> false
            on -> !(tiny && now - quietSince > 30_000)
            else -> big
        }
        if (want != on) {
            try { dp?.setEnabled(want); eq?.setEnabled(want) } catch (_: Exception) {}
            on = want
        }
        setCompensation(if (on) compDb else 0f)

        val d = dp
        val e = eq
        if (d != null) {
            val first = last == null
            val l = last ?: FloatArray(corr.size) { Float.NaN }.also { last = it }
            val due = first || now - lastSend >= minIntervalMs
            if (due && (first || corr.indices.any { abs(corr[it] - l[it]) >= minStepDb })) {
                // kirim semua band sekaligus, jarang-jarang, supaya tidak ada jeda berkala
                for (i in corr.indices) {
                    val edge = if (i == corr.size - 1) 20000f else sqrt(freqs[i] * freqs[i + 1])
                    try { d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edge, corr[i])) } catch (_: Exception) {}
                    l[i] = corr[i]
                }
                lastSend = now
            }
            applied = if (on) l.copyOf() else FloatArray(corr.size)
        } else if (e != null) {
            val g = fit(corr)
            val mb = IntArray(g.size) { (g[it] * 100).toInt().coerceIn(eqMin, eqMax) }
            val first = lastEqMb == null
            val lm = lastEqMb ?: IntArray(mb.size) { Int.MIN_VALUE }.also { lastEqMb = it }
            val due = first || now - lastSend >= minIntervalMs
            if (due && (first || mb.indices.any { abs(mb[it] - lm[it]) >= (minStepDb * 100).toInt() })) {
                for (i in mb.indices) {
                    try { e.setBandLevel(i.toShort(), mb[i].toShort()) } catch (_: Exception) {}
                    lm[i] = mb[i]
                }
                lastSend = now
            }
            applied = if (on) modelCurve(FloatArray(lm.size) { lm[it] / 100f }) else FloatArray(freqs.size)
        }
    }

    private fun log2(x: Float): Float = (kotlin.math.ln(x.toDouble()) / kotlin.math.ln(2.0)).toFloat()

    /** Perkiraan respons satu band equalizer (dB per dB gain) di frekuensi f: "bukit" pada skala oktaf. */
    private fun bump(f: Float, fc: Float, sigma: Float): Float {
        val x = log2(f / fc) / sigma
        return kotlin.math.exp(-0.5f * x * x)
    }

    /** Kurva hasil gabungan band (model), di frekuensi analisis. */
    private fun modelCurve(gains: FloatArray): FloatArray {
        val r = resp ?: return FloatArray(freqs.size)
        return FloatArray(freqs.size) { k -> var s = 0f; for (i in gains.indices) s += gains[i] * r[i][k]; s }
    }

    /**
     * Cari nilai band equalizer yang gabungannya paling mendekati kurva koreksi (least-squares berbobot,
     * dengan sedikit regularisasi supaya nilai band tidak ekstrem). Jauh lebih tepat daripada
     * mengambil nilai di frekuensi tengah saja.
     */
    private fun fit(target: FloatArray): FloatArray {
        val r = resp ?: return Bands.interp(target, freqs, eqCenters)
        val b = r.size
        val m = freqs.size
        // bobot: daerah redaman (nilai negatif) dijaga lebih ketat supaya tidak meleset ke tetangga
        val w = FloatArray(m) { k -> if (target[k] < -0.5f) 1.6f else 1f }
        val lambda = 0.05f * m / b
        val a = Array(b) { DoubleArray(b + 1) }
        for (i in 0 until b) {
            for (j in 0 until b) {
                var s = 0.0
                for (k in 0 until m) s += w[k] * r[i][k] * r[j][k]
                a[i][j] = s + if (i == j) lambda else 0f
            }
            var y = 0.0
            for (k in 0 until m) y += w[k] * r[i][k] * target[k]
            a[i][b] = y
        }
        // eliminasi Gauss
        for (c in 0 until b) {
            var piv = c
            for (rr in c + 1 until b) if (abs(a[rr][c]) > abs(a[piv][c])) piv = rr
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            if (abs(a[c][c]) < 1e-9) continue
            for (rr in 0 until b) if (rr != c) {
                val fct = a[rr][c] / a[c][c]
                for (cc in c..b) a[rr][cc] -= fct * a[c][cc]
            }
        }
        val lim = (eqMax / 100f).coerceAtMost(12f)
        return FloatArray(b) { i -> if (abs(a[i][i]) < 1e-9) 0f else (a[i][b] / a[i][i]).toFloat().coerceIn(-lim, lim) }
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
