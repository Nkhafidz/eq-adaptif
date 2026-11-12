package id.khafidz.eqadaptif

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Service latar depan EQ Adaptif (AI saja):
 *  1. mendengarkan musik yang diputar lewat Visualizer,
 *  2. menghitung karakter tiap lagu (AI Auto-seimbang),
 *  3. menambahkan koreksi kecil di atas EQ yang dipakai pengguna (Poweramp Equalizer, dll).
 */
class EqService : Service() {

    companion object {
        var instance: EqService? = null
            private set
        private const val CHANNEL = "eq"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "id.khafidz.eqadaptif.STOP"
        const val ACTION_TOGGLE_AI = "id.khafidz.eqadaptif.TOGGLE_AI"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EqService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var viz: Visualizer? = null
    private var fx: CompanionFx? = null
    val adaptive = Adaptive()
    private var lastAiOn: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Store.recoverDvc(this)
        instance = this
        goForeground()
        startVisualizer()
        startCorrection()
        handler.post(tick)
        EqWidget.updateAll(this)
        EqTile.refresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_AI -> {
                Store.aiOn = !Store.aiOn
                Store.save()
                updateNotification()
                EqWidget.updateAll(this)
                AppState.changed()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fx?.release()
        fx = null
        try { viz?.setEnabled(false); viz?.release() } catch (_: Exception) {}
        viz = null
        instance = null
        AppState.frames = 0
        AppState.bands = 0
        AppState.corr = FloatArray(10)
        AppState.spectrum.fill(0f)
        AppState.changed()
        EqWidget.updateAll(this)
        EqTile.refresh(this)
        super.onDestroy()
    }

    // ---------------- Notifikasi ----------------

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, EqService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("EQ Adaptif")
            .setContentText(if (Store.aiOn) "AI menyesuaikan suara tiap lagu" else "AI dijeda")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (Store.aiOn) "Jeda AI" else "Lanjutkan AI", svc(ACTION_TOGGLE_AI, 1)).build())
            .addAction(Notification.Action.Builder(null, "Matikan", svc(ACTION_STOP, 2)).build())
            .build()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "EQ Adaptif", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        val mic = hasMic()
        try {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    if (mic) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    startForeground(NOTIF_ID, n, t)
                }
                Build.VERSION.SDK_INT >= 30 && mic ->
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else -> startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    // ---------------- Koreksi ----------------

    private fun startCorrection() {
        if (fx != null) return
        try {
            val f = CompanionFx()
            fx = f
            AppState.bands = f.bandCount
            AppState.error = null
        } catch (e: Exception) {
            AppState.error = "Equalizer dasar Android tidak tersedia di HP ini: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------------- Analisis ----------------

    private fun startVisualizer() {
        if (viz != null) return
        if (!hasMic()) {
            AppState.vizError = "Izin \"Rekam audio\" belum diberikan, jadi AI tidak bisa mendengarkan musik."
            return
        }
        try {
            val v = Visualizer(0) // 0 = campuran keluaran audio
            v.setEnabled(false)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(2048))
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null && samplingRate > 0) onFft(fft, samplingRate / 1000f)
                }
            }, Visualizer.getMaxCaptureRate().coerceAtMost(20000), false, true)
            v.setEnabled(true)
            viz = v
            AppState.vizError = null
        } catch (e: Exception) {
            AppState.vizError = "Penganalisis audio tidak tersedia di HP ini (${e.message ?: e.javaClass.simpleName})."
        }
    }

    private fun onFft(fft: ByteArray, srHz: Float) {
        updateSpectrum(fft, srHz)
        val p = Adaptive.bandPowers(fft, srHz)
        // Yang terdengar sudah termasuk koreksi AI; kurangi koreksi itu agar didapat karakter asli.
        fx?.applied10?.let { a -> for (i in 0 until 10) p[i] = p[i] / 10.0.pow(a[i] / 10.0) }
        adaptive.addFrame(p)
    }

    private fun updateSpectrum(fft: ByteArray, srHz: Float) {
        val n = fft.size
        val bins = n / 2
        val binHz = srHz / n
        val s = AppState.spectrum
        val lnMin = ln(20.0)
        val lnMax = ln(20000.0)
        for (b in 0 until AppState.SPEC_BINS) {
            val f0 = kotlin.math.exp(lnMin + (lnMax - lnMin) * b / AppState.SPEC_BINS)
            val f1 = kotlin.math.exp(lnMin + (lnMax - lnMin) * (b + 1) / AppState.SPEC_BINS)
            val k0 = (f0 / binHz).toInt().coerceIn(1, bins - 1)
            val k1 = (f1 / binHz).toInt().coerceIn(k0, bins - 1)
            var m = 0.0
            for (k in k0..k1) {
                val re = fft[2 * k].toDouble()
                val im = fft[2 * k + 1].toDouble()
                m = maxOf(m, sqrt(re * re + im * im))
            }
            val v = (20 * log10(m + 1e-3) / 42.0).coerceIn(0.0, 1.0).toFloat()
            s[b] = if (v > s[b]) v else s[b] * 0.82f + v * 0.18f
        }
        AppState.spectrumListener?.invoke()
    }

    private fun step() {
        if (viz == null && hasMic()) startVisualizer()
        if (fx == null) startCorrection()
        adaptive.update(Store.target ?: Adaptive.DEFAULT_TARGET, Store.strength, Store.limit)
        val f = fx
        if (f != null) {
            f.apply(if (Store.aiOn) adaptive.C else FloatArray(10))
            AppState.error = if (!f.hasControl)
                "Koreksi AI sedang dikendalikan aplikasi lain (EQ lain memakai jenis efek yang sama)." else null
        }
        if (lastAiOn != Store.aiOn) { lastAiOn = Store.aiOn; updateNotification() }
        AppState.corr = if (Store.aiOn) adaptive.C.copyOf() else FloatArray(10)
        AppState.frames = adaptive.frames
        AppState.changed()
    }

    /** Kunci karakter suara lagu yang sedang diputar sebagai target AI. */
    fun lockTarget(): Boolean {
        if (adaptive.frames < Adaptive.FULL * 6 / 10) return false
        Store.target = adaptive.makeTarget(Store.aiOn)
        Store.targetName = "lagu referensi"
        Store.save()
        return true
    }
}
