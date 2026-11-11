package id.khafidz.eqadaptif

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.abs
import kotlin.math.pow

/**
 * Service latar depan yang:
 *  1. memasang DynamicsProcessing (EQ 10 band + limiter) ke sesi audio aplikasi musik,
 *  2. menganalisis spektrum keluaran lewat Visualizer,
 *  3. menghitung koreksi Auto-seimbang setiap 250 ms.
 */
class EqService : Service() {

    companion object {
        var instance: EqService? = null
            private set
        private const val CHANNEL = "eq"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EqService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val effects = HashMap<Int, DynamicsProcessing>()
    private var viz: Visualizer? = null
    val adaptive = Adaptive()

    private var lastApplied: FloatArray? = null
    private var lastPre = Float.NaN
    private var legacyFailed = false

    private val receiver = SessionReceiver()
    private var msm: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var currentTitle: String? = null

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
        instance = this
        goForeground()
        registerSessionReceiver()
        Sessions.open.keys.toList().forEach { attach(it) }
        startVisualizer()
        refreshMediaWatch()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        effects.values.forEach { dp ->
            try { dp.setEnabled(false); dp.release() } catch (_: Exception) {}
        }
        effects.clear()
        try { viz?.setEnabled(false); viz?.release() } catch (_: Exception) {}
        viz = null
        clearMediaWatch()
        instance = null
        AppState.sessions = emptyList()
        AppState.frames = 0
        AppState.changed()
        super.onDestroy()
    }

    // ---------------- Foreground ----------------

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Equalizer", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("EQ Adaptif aktif")
            .setContentText("Equalizer berjalan untuk aplikasi musik")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
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
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }
    }

    private fun registerSessionReceiver() {
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
    }

    // ---------------- Efek EQ ----------------

    fun attach(sid: Int) {
        if (effects.containsKey(sid)) return
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,          // stereo
                true, 10,   // pre-EQ 10 band
                false, 0,   // multiband compressor: tidak dipakai
                false, 0,   // post-EQ: tidak dipakai
                true        // limiter: mencegah suara pecah
            ).build()
            val dp = DynamicsProcessing(Int.MAX_VALUE, sid, cfg)
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
            )
            effects[sid] = dp
            applyGains(true)
            dp.setEnabled(true)
            AppState.dpError = null
        } catch (e: Exception) {
            if (sid == 0) legacyFailed = true
            AppState.dpError = "Gagal memasang EQ ke sesi ${if (sid == 0) "global" else sid}: ${e.message ?: e.javaClass.simpleName}"
        }
        publishSessions()
    }

    fun detach(sid: Int) {
        effects.remove(sid)?.let { dp ->
            try { dp.setEnabled(false); dp.release() } catch (_: Exception) {}
        }
        publishSessions()
    }

    private fun publishSessions() {
        AppState.sessions = effects.keys.sorted().map { sid ->
            if (sid == 0) "Global (mode legacy)" else Sessions.friendly(Sessions.open[sid])
        }
        AppState.changed()
    }

    private fun applyGains(force: Boolean = false) {
        val corrOn = Store.adaptiveOn
        val g = FloatArray(10) { Store.gains[it] + if (corrOn) adaptive.C[it] else 0f }
        val boost = if (corrOn) (adaptive.C.maxOrNull() ?: 0f).coerceAtLeast(0f) else 0f
        val pre = Store.preamp - boost * 0.5f // ruang ekstra saat koreksi menaikkan band

        val last = lastApplied
        val changed = force || last == null || g.indices.any { abs(g[it] - last[it]) > 0.05f }
        if (changed) {
            for (dp in effects.values) {
                for (i in 0 until 10) {
                    try {
                        dp.setPreEqBandAllChannelsTo(
                            i, DynamicsProcessing.EqBand(true, Bands.upperEdge(i), g[i])
                        )
                    } catch (_: Exception) {}
                }
            }
            lastApplied = g
        }
        if (force || lastPre.isNaN() || abs(pre - lastPre) > 0.05f) {
            for (dp in effects.values) {
                try { dp.setInputGainAllChannelsTo(pre) } catch (_: Exception) {}
            }
            lastPre = pre
        }
    }

    // ---------------- Analisis ----------------

    private fun startVisualizer() {
        if (viz != null) return
        if (!hasMic()) {
            AppState.vizError = "Izin \"Mikrofon/Rekam audio\" belum diberikan, jadi Auto-seimbang tidak bisa menganalisis musik. EQ manual tetap jalan."
            return
        }
        try {
            val v = Visualizer(0) // 0 = campuran keluaran audio
            v.setEnabled(false)
            val range = Visualizer.getCaptureSizeRange()
            v.setCaptureSize(range[1].coerceAtMost(2048))
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
            AppState.vizError = "Penganalisis audio tidak tersedia di HP ini (${e.message ?: e.javaClass.simpleName}). EQ manual tetap jalan."
        }
    }

    private fun onFft(fft: ByteArray, srHz: Float) {
        val p = Adaptive.bandPowers(fft, srHz)
        // Visualizer mendengar suara SETELAH EQ; kurangi EQ yang sedang dipasang agar didapat karakter asli lagu.
        val applied = if (effects.isEmpty()) null else lastApplied
        if (applied != null) {
            for (i in 0 until 10) p[i] = p[i] / 10.0.pow(applied[i] / 10.0)
        }
        adaptive.addFrame(p)
    }

    private fun step() {
        if (Store.legacy && !effects.containsKey(0) && !legacyFailed) attach(0)
        if (!Store.legacy) {
            legacyFailed = false
            if (effects.containsKey(0)) detach(0)
        }
        if (viz == null && hasMic()) startVisualizer()

        adaptive.update(Store.target ?: Adaptive.DEFAULT_TARGET, Store.strength, Store.limit)
        applyGains()

        AppState.corr = adaptive.C.copyOf()
        AppState.frames = adaptive.frames
        AppState.title = currentTitle
        AppState.changed()
    }

    /** Kunci karakter suara lagu yang sedang diputar sebagai target. */
    fun lockTarget(): Boolean {
        if (adaptive.frames < Adaptive.FULL * 6 / 10) return false
        Store.target = adaptive.makeTarget(Store.adaptiveOn)
        Store.targetName = currentTitle ?: "lagu yang sedang diputar"
        Store.save()
        return true
    }

    // ---------------- Deteksi ganti lagu (opsional) ----------------

    private fun hasNotifAccess(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return nm.isNotificationListenerAccessGranted(ComponentName(this, MediaListenerService::class.java))
    }

    fun refreshMediaWatch() {
        if (sessionsListener != null || !hasNotifAccess()) return
        try {
            val m = getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(this, MediaListenerService::class.java)
            val l = MediaSessionManager.OnActiveSessionsChangedListener { list -> bindControllers(list) }
            m.addOnActiveSessionsChangedListener(l, cn)
            msm = m
            sessionsListener = l
            bindControllers(m.getActiveSessions(cn))
        } catch (_: Exception) {}
    }

    private fun clearMediaWatch() {
        controllers.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        controllers.clear()
        sessionsListener?.let { l -> try { msm?.removeOnActiveSessionsChangedListener(l) } catch (_: Exception) {} }
        sessionsListener = null
    }

    private fun bindControllers(list: List<MediaController>?) {
        controllers.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        controllers.clear()
        list?.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    onTrack(metadata)
                }
            }
            c.registerCallback(cb, handler)
            controllers.add(c to cb)
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) onTrack(c.metadata)
        }
    }

    private fun onTrack(m: MediaMetadata?) {
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val a = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val full = if (a.isNullOrBlank()) t else "$t — $a"
        if (full != currentTitle) {
            currentTitle = full
            adaptive.reset() // lagu baru: analisis ulang dari nol
        }
    }
}
