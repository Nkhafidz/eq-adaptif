package id.khafidz.eqadaptif

import android.content.Context
import android.content.SharedPreferences

/** 10 band analisis AI (oktaf). */
object Bands {
    val F10 = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val L10 = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** Batas koreksi AI per band. Ujung lebih dijaga (sub-bass bervariasi, MP3 sering terpotong di atas 16 kHz). */
    val EDGE_LIMIT = floatArrayOf(0.5f, 0.8f, 1f, 1f, 1f, 1f, 1f, 1f, 0.85f, 0.5f)
}

/** Pengaturan AI, disimpan di SharedPreferences. */
object Store {
    private var sp: SharedPreferences? = null

    var aiOn = true
    var strength = 60          // %
    var limit = 6              // dB
    var target: FloatArray? = null
    var targetName: String? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        sp = p
        aiOn = p.getBoolean("adaptiveOn", true)
        strength = p.getInt("strength", 60)
        limit = p.getInt("limit", 6)
        target = arr(p.getString("target", null))
        targetName = p.getString("targetName", null)
    }

    fun save() {
        sp?.edit()
            ?.putBoolean("adaptiveOn", aiOn)
            ?.putInt("strength", strength)
            ?.putInt("limit", limit)
            ?.putString("target", target?.joinToString(",") ?: "")
            ?.putString("targetName", targetName)
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

    private fun arr(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        return try {
            val a = s.split(",").map { it.trim().toFloat() }.toFloatArray()
            if (a.size == 10) a else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Status yang dibagikan dari service ke layar. Semua diakses di main thread. */
object AppState {
    var corr = FloatArray(10)       // koreksi AI (dB) di 10 band
    var frames = 0                  // jumlah frame analisis lagu saat ini
    var bands = 0                   // jumlah band equalizer koreksi di HP ini
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
