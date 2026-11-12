package id.khafidz.eqadaptif

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Jembatan ke Poweramp Equalizer lewat API publiknya (github.com/maxmpz/powerampapi).
 * Tahap uji: memeriksa apakah Poweramp Equalizer di HP pengguna mau menerima perintah band/nada
 * dan mengirim balik keadaannya. Jika ya, AI EQ Adaptif bisa mengatur Poweramp Equalizer langsung.
 */
object PeqBridge {
    const val PKG = "com.maxmpz.equalizer"
    private const val RECEIVER = "com.maxmpz.equalizer.PeqAPIReceiver"
    private const val ACTION_CMD = "com.maxmpz.equalizer.API_COMMAND"
    private const val CMD_SET_EQU_STRING = 51
    private const val CMD_SET_EQU_BAND = 52
    private const val CMD_SET_EQU_ENABLED = 53

    private val STATE_ACTIONS = listOf(
        "com.maxmpz.equalizer.EQU_CHANGED",
        "com.maxmpz.audioplayer.EQU_CHANGED"
    )

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: Exception) { false }

    private fun send(ctx: Context, cmd: Int, extras: (Intent) -> Unit = {}) {
        val i = Intent(ACTION_CMD)
            .setComponent(ComponentName(PKG, RECEIVER))
            .putExtra("cmd", cmd)
            .putExtra("pak", ctx.packageName)
        extras(i)
        ctx.sendBroadcast(i)
    }

    /**
     * Uji lengkap:
     *  1. dengarkan siaran keadaan EQ dari Poweramp Equalizer,
     *  2. minta keadaan saat ini,
     *  3. ubah kenop Bass sedikit,
     *  4. kembalikan ke keadaan semula (jika keadaan semula diterima).
     * log() dipanggil di main thread untuk setiap langkah; done(menerimaKeadaan, berhasilMengubah?) di akhir.
     */
    fun runTest(ctx: Context, log: (String) -> Unit, done: (Boolean) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val received = ArrayList<String>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val v = intent.getStringExtra("value") ?: "(tanpa nilai)"
                val name = intent.getStringExtra("name")
                received.add(v)
                log("← Diterima dari Poweramp EQ" + (name?.let { " (preset: $it)" } ?: "") + ":\n" + v.take(300))
            }
        }
        val f = IntentFilter().apply { STATE_ACTIONS.forEach { addAction(it) } }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(rx, f)

        log("1. Meminta keadaan EQ dari Poweramp Equalizer…")
        send(ctx, CMD_SET_EQU_ENABLED)

        h.postDelayed({
            val original = received.lastOrNull()
            log("2. Mengirim perintah: Bass → 60% …  (lihat kenop Bass di Poweramp Equalizer)")
            send(ctx, CMD_SET_EQU_BAND) { it.putExtra("name", "bass").putExtra("value", 0.6f) }
            h.postDelayed({
                send(ctx, CMD_SET_EQU_ENABLED) // minta keadaan lagi untuk melihat apakah berubah
                h.postDelayed({
                    val after = received.lastOrNull()
                    val changed = original != null && after != null && after != original
                    if (original != null) {
                        log("3. Mengembalikan pengaturan semula…")
                        send(ctx, CMD_SET_EQU_STRING) { it.putExtra("value", original) }
                    } else {
                        log("3. Keadaan semula tidak diterima. Jika kenop Bass berubah, kembalikan manual di Poweramp Equalizer.")
                    }
                    try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
                    log(
                        when {
                            changed -> "✅ HASIL: Poweramp Equalizer bisa dikendalikan. AI bisa mengatur EQ-nya langsung."
                            received.isNotEmpty() -> "⚠️ HASIL: keadaan EQ diterima, tapi perubahan belum terkonfirmasi. Apakah kenop Bass tadi bergerak?"
                            else -> "❓ HASIL: tidak ada balasan dari Poweramp Equalizer. Lihat apakah kenop Bass tadi bergerak."
                        }
                    )
                    done(received.isNotEmpty())
                }, 1200)
            }, 2500)
        }, 1500)
    }
}
