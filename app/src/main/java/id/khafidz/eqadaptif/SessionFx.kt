package id.khafidz.eqadaptif

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import kotlin.math.abs
import kotlin.math.log10

/** Parameter suara yang dipasang ke setiap sesi. */
class FxParams(
    val gains: FloatArray,
    val edges: FloatArray,
    val preamp: Float,
    val balance: Int,
    val limiterOn: Boolean,
    val limiterThreshold: Float,
    val compressor: Int,
    val virtualizer: Int,
    val reverb: Int,
    val loudBoost: Float
)

/**
 * Semua efek untuk satu sesi audio:
 *  - DynamicsProcessing: EQ 10/31 band, compressor, preamp + balance per kanal, limiter
 *  - Virtualizer (stereo luas / 3D), opsional
 *  - PresetReverb, opsional
 */
class SessionFx(val sid: Int, val bandCount: Int) {
    private val dp: DynamicsProcessing
    private var virt: Virtualizer? = null
    private var rev: PresetReverb? = null
    private var loud: LoudnessEnhancer? = null
    private var loudFailed = false
    private var last: FxParams? = null

    init {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                  // stereo
            true, bandCount,    // pre-EQ
            true, 1,            // compressor (1 band, seluruh frekuensi)
            false, 0,           // post-EQ
            true                // limiter
        ).build()
        dp = DynamicsProcessing(Int.MAX_VALUE, sid, cfg)
        dp.setEnabled(true)
    }

    fun apply(p: FxParams, force: Boolean = false) {
        if (p.gains.size != bandCount) return
        val l = last
        // EQ
        if (force || l == null || p.gains.indices.any { abs(p.gains[it] - l.gains[it]) > 0.05f }) {
            for (i in 0 until bandCount) {
                try {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, p.edges[i], p.gains[i]))
                } catch (_: Exception) {}
            }
        }
        // Preamp + balance
        if (force || l == null || abs(p.loudBoost - l.loudBoost) > 0.05f) applyLoudness(p.loudBoost)
        if (force || l == null || abs(p.preamp - l.preamp) > 0.05f || p.balance != l.balance ||
            abs(p.loudBoost - l.loudBoost) > 0.05f) {
            val b = p.balance / 100f
            val left = if (b > 0) 20f * log10((1f - b).coerceAtLeast(0.01f)) else 0f
            val right = if (b < 0) 20f * log10((1f + b).coerceAtLeast(0.01f)) else 0f
            // Jika LoudnessEnhancer tidak tersedia di HP ini, penguat dipindah ke preamp (limiter menjaga puncak).
            val pre = p.preamp + if (loudFailed) p.loudBoost else 0f
            try {
                dp.setInputGainbyChannel(0, pre + left)
                dp.setInputGainbyChannel(1, pre + right)
            } catch (_: Exception) {
                try { dp.setInputGainAllChannelsTo(pre) } catch (_: Exception) {}
            }
        }
        // Compressor
        if (force || l == null || p.compressor != l.compressor) applyCompressor(p.compressor)
        // Limiter
        if (force || l == null || p.limiterOn != l.limiterOn || abs(p.limiterThreshold - l.limiterThreshold) > 0.05f) {
            try {
                dp.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, p.limiterOn, 0, 1f, 60f, 10f, p.limiterThreshold, 0f)
                )
            } catch (_: Exception) {}
        }
        if (force || l == null || p.virtualizer != l.virtualizer) applyVirtualizer(p.virtualizer)
        if (force || l == null || p.reverb != l.reverb) applyReverb(p.reverb)
        last = p
    }

    /** 0 mati, 1 ringan, 2 sedang, 3 kuat: menyamakan bagian pelan dan keras. */
    private fun applyCompressor(level: Int) {
        try {
            val on = level > 0
            val threshold = when (level) { 1 -> -18f; 2 -> -24f; else -> -30f }
            val ratio = when (level) { 1 -> 2f; 2 -> 3f; else -> 4f }
            val makeup = when (level) { 1 -> 3f; 2 -> 6f; else -> 9f }
            dp.setMbcAllChannelsTo(DynamicsProcessing.Mbc(true, on, 1))
            dp.setMbcBandAllChannelsTo(
                0,
                DynamicsProcessing.MbcBand(
                    on, 20000f,
                    10f, 150f,          // attack, release (ms)
                    ratio, threshold,
                    6f,                 // knee
                    -90f, 1f,           // noise gate off
                    0f, if (on) makeup else 0f
                )
            )
        } catch (_: Exception) {}
    }

    private fun applyLoudness(db: Float) {
        if (loudFailed) return
        try {
            if (db <= 0.05f) {
                loud?.setEnabled(false)
                return
            }
            val le = loud ?: LoudnessEnhancer(sid).also { loud = it }
            le.setTargetGain((db * 100).toInt())
            le.setEnabled(true)
        } catch (_: Exception) {
            loudFailed = true
        }
    }

    private fun applyVirtualizer(strength: Int) {
        try {
            if (strength <= 0) {
                virt?.setEnabled(false)
                return
            }
            val v = virt ?: Virtualizer(0, sid).also { virt = it }
            v.setStrength(strength.coerceIn(0, 1000).toShort())
            v.setEnabled(true)
        } catch (_: Exception) {}
    }

    private fun applyReverb(preset: Int) {
        try {
            if (preset <= 0) {
                rev?.setEnabled(false)
                return
            }
            val r = rev ?: PresetReverb(0, sid).also { rev = it }
            r.preset = preset.coerceIn(1, 6).toShort()
            r.setEnabled(true)
        } catch (_: Exception) {}
    }

    fun release() {
        try { dp.setEnabled(false); dp.release() } catch (_: Exception) {}
        try { virt?.release() } catch (_: Exception) {}
        try { rev?.release() } catch (_: Exception) {}
        try { loud?.release() } catch (_: Exception) {}
    }
}
