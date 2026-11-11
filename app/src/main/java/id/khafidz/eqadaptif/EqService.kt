package id.khafidz.eqadaptif

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
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
 * Service latar depan yang:
 *  1. memasang efek (EQ, preamp, balance, limiter, virtualizer, reverb) ke sesi audio aplikasi musik,
 *  2. menganalisis spektrum keluaran lewat Visualizer (untuk tampilan & AI Auto-seimbang),
 *  3. mengganti preset otomatis sesuai perangkat keluaran,
 *  4. menghitung koreksi AI Auto-seimbang setiap 250 ms.
 */
class EqService : Service() {

    companion object {
        var instance: EqService? = null
            private set
        private const val CHANNEL = "eq"
        private const val NOTIF_ID = 1
        const val ACTION_NEXT = "id.khafidz.eqadaptif.NEXT_PRESET"
        const val ACTION_PREV = "id.khafidz.eqadaptif.PREV_PRESET"
        const val ACTION_STOP = "id.khafidz.eqadaptif.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EqService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fx = HashMap<Int, SessionFx>()
    private var viz: Visualizer? = null
    val adaptive = Adaptive()

    private var lastApplied: FloatArray? = null
    private var legacyFailed = false
    private var lastNotifPreset: String? = null

    private val receiver = SessionReceiver()
    private lateinit var am: AudioManager

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 250)
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        instance = this
        am = getSystemService(AudioManager::class.java)
        goForeground()
        registerSessionReceiver()
        Sessions.open.keys.toList().forEach { attach(it) }
        startVisualizer()
        am.registerAudioDeviceCallback(deviceCallback, handler)
        am.registerAudioPlaybackCallback(playbackCallback, handler)
        scanPlayback(am.activePlaybackConfigurations)
        onOutputChanged(false)
        handler.post(tick)
        EqWidget.updateAll(this)
        EqTile.refresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEXT -> { Store.cyclePreset(1); forceApply() }
            ACTION_PREV -> { Store.cyclePreset(-1); forceApply() }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        try { am.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Exception) {}
        fx.values.forEach { it.release() }
        fx.clear()
        try { viz?.setEnabled(false); viz?.release() } catch (_: Exception) {}
        viz = null
        instance = null
        AppState.sessions = emptyList()
        AppState.frames = 0
        AppState.spectrum.fill(0f)
        AppState.changed()
        EqWidget.updateAll(this)
        EqTile.refresh(this)
        super.onDestroy()
    }

    // ---------------- Foreground & notifikasi ----------------

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
        val preset = Store.presetName ?: "Kustom"
        val ai = if (Store.adaptiveOn) " · AI aktif" else ""
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("EQ Adaptif: $preset")
            .setContentText("Equalizer berjalan$ai")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "◀ Preset", svc(ACTION_PREV, 1)).build())
            .addAction(Notification.Action.Builder(null, "Preset ▶", svc(ACTION_NEXT, 2)).build())
            .addAction(Notification.Action.Builder(null, "Matikan", svc(ACTION_STOP, 3)).build())
            .build()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Equalizer", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        lastNotifPreset = Store.presetName
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

    private fun updateNotification() {
        lastNotifPreset = Store.presetName
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
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

    // ---------------- Efek per sesi ----------------

    /** Mode global aktif dan berhasil: efek per sesi tidak dipasang supaya EQ tidak berlipat. */
    private fun globalActive(): Boolean = Store.legacy && !legacyFailed

    fun attach(sid: Int) {
        if (fx.containsKey(sid)) return
        if (sid != 0 && globalActive()) { publishSessions(); return }
        try {
            val s = SessionFx(sid)
            fx[sid] = s
            s.apply(currentParams(), true)
            AppState.dpError = null
        } catch (e: Exception) {
            if (sid == 0) legacyFailed = true
            AppState.dpError = "Gagal memasang EQ ke sesi ${if (sid == 0) "global" else sid}: ${e.message ?: e.javaClass.simpleName}"
        }
        publishSessions()
    }

    fun detach(sid: Int) {
        fx.remove(sid)?.release()
        publishSessions()
    }

    private fun publishSessions() {
        AppState.sessions = fx.keys.sorted().map { sid ->
            if (sid == 0) "Semua aplikasi (mode global)" else Sessions.friendly(Sessions.open[sid])
        }
        AppState.changed()
    }

    private fun currentParams(): FxParams {
        val corrOn = Store.adaptiveOn
        val user = Store.userCurve()
        val g = FloatArray(Bands.N) { (user[it] + if (corrOn) adaptive.C[it] else 0f).coerceIn(-15f, 15f) }
        val boost = if (corrOn) (adaptive.C.maxOrNull() ?: 0f).coerceAtLeast(0f) else 0f
        val toneBoost = maxOf(Store.bass, Store.treble, 0f) * 0.5f
        lastApplied = g
        return FxParams(
            gains = g,
            preamp = Store.preamp - boost * 0.5f - toneBoost,
            balance = Store.balance,
            limiterOn = Store.limiterOn,
            limiterThreshold = Store.limiterThreshold,
            virtualizer = Store.virtualizer,
            reverb = Store.reverb
        )
    }

    /** Dipanggil setelah pengaturan berubah dari layar, widget, atau notifikasi. */
    fun forceApply() {
        val p = currentParams()
        fx.values.forEach { it.apply(p, false) }
        if (Store.presetName != lastNotifPreset) updateNotification()
        EqWidget.updateAll(this)
        AppState.changed()
    }

    // ---------------- Deteksi pemutaran (cara kedua) ----------------

    /**
     * Sebagian aplikasi tidak selalu mengumumkan sesi audionya. Android memberi tahu setiap kali
     * ada pemutaran; di banyak HP nomor sesinya bisa dibaca, sehingga EQ bisa langsung menempel.
     */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            scanPlayback(configs)
        }
    }

    private fun scanPlayback(configs: List<AudioPlaybackConfiguration>?) {
        configs ?: return
        for (c in configs) {
            try {
                val usage = c.audioAttributes.usage
                if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME &&
                    usage != AudioAttributes.USAGE_UNKNOWN) continue
                val sid = hiddenInt(c, "getSessionId")
                if (sid == null || sid <= 0 || fx.containsKey(sid)) continue
                if (!Sessions.open.containsKey(sid)) {
                    val uid = hiddenInt(c, "getClientUid")
                    val pkg = uid?.let { packageManager.getPackagesForUid(it)?.firstOrNull() }
                    if (pkg == packageName) continue
                    Sessions.open[sid] = pkg ?: "?"
                }
                attach(sid)
            } catch (_: Exception) {}
        }
    }

    private fun hiddenInt(o: Any, method: String): Int? = try {
        o.javaClass.getMethod(method).invoke(o) as? Int
    } catch (_: Throwable) {
        null
    }

    // ---------------- Perangkat keluaran ----------------

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { onOutputChanged(true) }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { onOutputChanged(true) }
    }

    private fun detectOutput(): String {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = outs.map { it.type }.toSet()
        val wired = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE
        )
        val bt = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= 31) {
            bt.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            bt.add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        return when {
            types.any { it in wired } -> Outputs.WIRED
            types.any { it in bt } -> Outputs.BT
            else -> Outputs.SPEAKER
        }
    }

    private fun onOutputChanged(announce: Boolean) {
        val o = detectOutput()
        val changed = o != AppState.output
        AppState.output = o
        if (Store.autoDevice && (changed || !announce)) {
            val name = Store.devicePresets[o]
            if (name != null && name != Store.presetName && Store.applyPreset(name)) forceApply()
        }
        AppState.changed()
    }

    // ---------------- Analisis ----------------

    private fun startVisualizer() {
        if (viz != null) return
        if (!hasMic()) {
            AppState.vizError = "Izin \"Rekam audio\" belum diberikan, jadi spektrum dan AI Auto-seimbang tidak aktif. EQ tetap jalan."
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
            AppState.vizError = "Penganalisis audio tidak tersedia di HP ini (${e.message ?: e.javaClass.simpleName}). EQ tetap jalan."
        }
    }

    private fun onFft(fft: ByteArray, srHz: Float) {
        updateSpectrum(fft, srHz)
        val p = Adaptive.bandPowers(fft, srHz)
        // Visualizer mendengar suara SETELAH EQ; kurangi EQ yang dipasang agar didapat karakter asli lagu.
        val applied = if (fx.isEmpty()) null else lastApplied
        if (applied != null) {
            for (i in 0 until Bands.N) p[i] = p[i] / 10.0.pow(applied[i] / 10.0)
        }
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
            val db = 20 * log10(m + 1e-3)                    // ~0..45 dB untuk FFT 8-bit
            val v = (db / 42.0).coerceIn(0.0, 1.0).toFloat()
            s[b] = if (v > s[b]) v else s[b] * 0.82f + v * 0.18f // naik cepat, turun pelan
        }
        AppState.spectrumListener?.invoke()
    }

    private fun step() {
        if (globalActive()) {
            if (!fx.containsKey(0)) attach(0)
            fx.keys.filter { it != 0 }.forEach { detach(it) }
        }
        if (!Store.legacy) {
            legacyFailed = false
            if (fx.containsKey(0)) detach(0)
            Sessions.open.keys.filter { !fx.containsKey(it) }.forEach { attach(it) }
        }
        if (viz == null && hasMic()) startVisualizer()
        if (fx.isEmpty() && !Store.legacy) scanPlayback(am.activePlaybackConfigurations)

        adaptive.update(Store.target ?: Adaptive.DEFAULT_TARGET, Store.strength, Store.limit)
        val p = currentParams()
        fx.values.forEach { it.apply(p) }
        if (Store.presetName != lastNotifPreset) updateNotification()

        AppState.corr = adaptive.C.copyOf()
        AppState.frames = adaptive.frames
        AppState.changed()
    }

    /** Kunci karakter suara lagu yang sedang diputar sebagai target AI. */
    fun lockTarget(): Boolean {
        if (adaptive.frames < Adaptive.FULL * 6 / 10) return false
        Store.target = adaptive.makeTarget(Store.adaptiveOn)
        Store.targetName = "lagu referensi"
        Store.save()
        return true
    }
}
