package id.khafidz.eqadaptif

import android.content.Context
import android.content.SharedPreferences

/** Set band analisis & koreksi AI: 10 (oktaf), 15 (2/3 oktaf), 31 (1/3 oktaf). */
object Bands {
    val F10 = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val F15 = floatArrayOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f)
    val F31 = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
        800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )

    fun forCount(n: Int): FloatArray = when (n) { 31 -> F31; 15 -> F15; else -> F10 }

    val FREQS: FloatArray get() = forCount(Store.bands)

    fun label(f: Float): String = when {
        f >= 1000f -> { val k = f / 1000f; if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format("%.1fk", k) }
        f == f.toInt().toFloat() -> f.toInt().toString()
        else -> String.format("%.1f", f)
    }

    /** Interpolasi kurva (dB) dari satu set frekuensi ke set lain, pada skala log. */
    fun interp(v: FloatArray, from: FloatArray, to: FloatArray): FloatArray =
        FloatArray(to.size) { j -> at(v, from, to[j]) }

    fun at(v: FloatArray, fr: FloatArray, f: Float): Float {
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
}

/** Pengaturan AI, disimpan di SharedPreferences. */
object Store {
    private var sp: SharedPreferences? = null

    var aiOn = true
    /** Detail koreksi: 10, 15, atau 31 band. */
    var bands = 10
    var strength = 60          // %
    var limit = 6              // dB
    var target: FloatArray? = null
    var targetName: String? = null
    /** Penguat volume (dB) yang dipasang hanya saat koreksi AI aktif, untuk mengimbangi turunnya volume. */
    var comp = 3f

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        sp = p
        aiOn = p.getBoolean("adaptiveOn", true)
        bands = p.getInt("aiBands", 10).let { if (it == 15 || it == 31) it else 10 }
        strength = p.getInt("strength", 60)
        limit = p.getInt("limit", 6)
        target = arr(p.getString("target", null))?.let { t ->
            if (t.size == bands) t else Adaptive.normalize(Bands.interp(t, Bands.forCount(t.size), Bands.FREQS))
        }
        targetName = p.getString("targetName", null)
        comp = p.getFloat("aiComp", 3f)
    }

    fun save() {
        sp?.edit()
            ?.putBoolean("adaptiveOn", aiOn)
            ?.putInt("aiBands", bands)
            ?.putInt("strength", strength)
            ?.putInt("limit", limit)
            ?.putString("target", target?.joinToString(",") ?: "")
            ?.putString("targetName", targetName)
            ?.putFloat("aiComp", comp)
            ?.apply()
    }

    /**
     * Pengaman dari versi lama (fitur DVC sudah dihapus): jika volume sistem masih dinaikkan oleh DVC,
     * kembalikan ke posisi pengguna.
     */
    fun recoverDvc(ctx: Context) {
        val p = sp ?: return
        if (!p.getBoolean("dvcEngaged", false)) return
        try {
            val restore = p.getInt("dvcRestore", -1)
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            if (restore >= 0) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, restore, 0)
        } catch (_: Exception) {}
        p.edit().putBoolean("dvcEngaged", false).putBoolean("dvcOn", false).commit()
    }

    /** Ganti detail koreksi; target ikut dikonversi supaya tidak perlu dikunci ulang. */
    fun changeBands(n: Int) {
        val m = if (n == 15 || n == 31) n else 10
        if (m == bands) return
        val old = Bands.forCount(bands)
        bands = m
        target = target?.let { Adaptive.normalize(Bands.interp(it, old, Bands.FREQS)) }
        save()
    }

    private fun arr(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        return try {
            val a = s.split(",").map { it.trim().toFloat() }.toFloatArray()
            if (a.size == 10 || a.size == 15 || a.size == 31) a else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Status yang dibagikan dari service ke layar. Semua diakses di main thread. */
object AppState {
    var corr = FloatArray(10)       // koreksi AI (dB) per band analisis
    var corrFreqs = Bands.F10       // frekuensi untuk corr
    var engine = ""                 // mesin koreksi yang dipakai
    var frames = 0                  // jumlah frame analisis lagu saat ini
    var fxActive = false            // efek koreksi sedang memproses suara
    var error: String? = null
    var vizError: String? = null

    /** Spektrum tampilan: SPEC_BINS titik log 20 Hz–20 kHz, nilai 0..1. */
    const val SPEC_BINS = 48
    val spectrum = FloatArray(SPEC_BINS)

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.toList().forEach { it() } }

    var spectrumListener: (() -> Unit)? = null
}
