package id.khafidz.eqadaptif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Aplikasi musik (YouTube Music, Spotify, dll) mengumumkan sesi audionya lewat broadcast ini.
 * Dengan nomor sesi itu, EQ bisa dipasang ke suara aplikasi tersebut tanpa root.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sid <= 0) return
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
        Store.init(context)
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> Sessions.onOpen(sid, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> Sessions.onClose(sid)
        }
    }
}
