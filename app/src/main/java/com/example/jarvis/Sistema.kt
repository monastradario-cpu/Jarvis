package com.example.jarvis

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/** Impostazioni del telefono comandate a voce: non disturbare, suoneria e luminosità. */
object Sistema {

    private fun apri(ctx: Context, intento: Intent) {
        try {
            ctx.startActivity(intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    private fun accessoNonDisturbare(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val n = ctx.getSystemService(NotificationManager::class.java)
        return n.isNotificationPolicyAccessGranted
    }

    fun nonDisturbare(ctx: Context, attiva: Boolean): String {
        if (Build.VERSION.SDK_INT < 23) return "Questo telefono non permette di controllare il non disturbare."
        if (!accessoNonDisturbare(ctx)) {
            apri(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Per il non disturbare mi serve un permesso: ho aperto la pagina, attiva Jarvis e ripeti la richiesta."
        }
        val n = ctx.getSystemService(NotificationManager::class.java)
        n.setInterruptionFilter(if (attiva) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (attiva) "Non disturbare attivato." else "Non disturbare disattivato."
    }

    fun suoneria(ctx: Context, modalita: String): String {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val m = modalita.lowercase(Locale.ITALIAN)
        val (valore, testo) = when {
            m.contains("silenz") || m.contains("muto") -> AudioManager.RINGER_MODE_SILENT to "Telefono in silenzioso."
            m.contains("vibr") -> AudioManager.RINGER_MODE_VIBRATE to "Telefono in vibrazione."
            else -> AudioManager.RINGER_MODE_NORMAL to "Suoneria attiva."
        }
        if (valore == AudioManager.RINGER_MODE_SILENT && !accessoNonDisturbare(ctx)) {
            apri(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Per mettere il silenzioso mi serve il permesso del non disturbare: attiva Jarvis e ripeti."
        }
        return try {
            audio.ringerMode = valore
            testo
        } catch (e: SecurityException) {
            apri(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "Mi serve il permesso del non disturbare: attiva Jarvis e ripeti."
        }
    }

    fun luminosita(ctx: Context, valore: String): String {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(ctx)) {
            apri(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "Per la luminosità mi serve il permesso di modificare le impostazioni: attivalo per Jarvis e ripeti."
        }
        val r = ctx.contentResolver
        val v = valore.lowercase(Locale.ITALIAN)
        if (v.contains("auto")) {
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Luminosità automatica."
        }
        val attuale = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val percento = when {
            v.contains("mass") -> 100
            v.contains("minim") -> 3
            v.contains("alz") || v.contains("più") || v.contains("aument") -> (attuale + 25).coerceAtMost(100)
            v.contains("abbass") || v.contains("meno") || v.contains("diminu") -> (attuale - 25).coerceAtLeast(3)
            else -> Regex("\\d{1,3}").find(v)?.value?.toIntOrNull()?.coerceIn(1, 100) ?: return "Dimmi che luminosità vuoi, in percentuale."
        }
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, percento * 255 / 100)
        return "Luminosità al $percento per cento."
    }
}
