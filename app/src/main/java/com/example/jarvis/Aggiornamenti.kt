package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamento automatico: Jarvis controlla su GitHub se c'è una versione nuova,
 * la scarica e apre l'installazione (che Android chiede sempre di confermare).
 */
object Aggiornamenti {

    private const val REPO = "monastradario-cpu/Jarvis"
    private const val CANALE = "jarvis_aggiornamenti"
    private const val ID_NOTIFICA = 960_001

    class Nuova(val numero: Int, val url: String, val dimensione: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun versioneInstallata(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (e: Exception) {
        0L
    }

    /** La versione più recente pubblicata, se è più nuova di quella installata. */
    fun controlla(ctx: Context): Nuova? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Jarvis-Android")
        val testo = try {
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val json = JSONObject(testo)
        val numero = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
        if (numero <= versioneInstallata(ctx)) return null
        val risorse = json.optJSONArray("assets") ?: return null
        for (i in 0 until risorse.length()) {
            val a = risorse.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) {
                return Nuova(numero, a.optString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** Dal controllo periodico: al massimo ogni 6 ore, e avvisa una volta sola per versione. */
    fun controllaInBackground(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("aggiornamenti_auto", true)) return
        if (System.currentTimeMillis() - p.getLong("aggiornamenti_ultimo", 0L) < 6 * 3600_000L) return
        p.edit().putLong("aggiornamenti_ultimo", System.currentTimeMillis()).apply()
        val nuova = try { controlla(ctx) } catch (e: Exception) { null } ?: return
        if (p.getInt("aggiornamenti_avvisato", 0) == nuova.numero) return
        p.edit().putInt("aggiornamenti_avvisato", nuova.numero).apply()
        val gestore = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            gestore.createNotificationChannel(NotificationChannel(CANALE, "Aggiornamenti di ${Nome.attuale}", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val apri = PendingIntent.getActivity(
            ctx, ID_NOTIFICA, Intent(ctx, MainActivity::class.java).putExtra("aggiorna", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val costruttore = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANALE) else Notification.Builder(ctx)
        try {
            gestore.notify(
                ID_NOTIFICA, costruttore
                    .setSmallIcon(R.drawable.ic_notifica)
                    .setContentTitle("È pronta la versione ${nuova.numero} di ${Nome.attuale}")
                    .setContentText("Tocca per aggiornare: ricordi e impostazioni restano.")
                    .setContentIntent(apri)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) { }
    }

    /** Scarica l'APK (va chiamato da un thread in background). Restituisce il file o null. */
    fun scarica(ctx: Context, nuova: Nuova, avanzamento: (Int) -> Unit): File? {
        val cartella = File(ctx.cacheDir, "aggiornamenti").apply { mkdirs() }
        cartella.listFiles()?.forEach { it.delete() }
        val file = File(cartella, "Jarvis-${nuova.numero}.apk")
        val c = URL(nuova.url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Jarvis-Android")
        try {
            if (c.responseCode !in 200..299) return null
            val totale = c.contentLengthLong.takeIf { it > 0 } ?: nuova.dimensione
            var letti = 0L
            var ultimo = -1
            c.inputStream.use { ingresso ->
                file.outputStream().use { uscita ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = ingresso.read(buffer)
                        if (n < 0) break
                        uscita.write(buffer, 0, n)
                        letti += n
                        val percento = if (totale > 0) (letti * 100 / totale).toInt() else 0
                        if (percento != ultimo) { ultimo = percento; avanzamento(percento) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return if (file.length() > 1_000_000) file else null
    }

    /** Apre l'installazione. Se manca il permesso, apre la pagina per darlo e restituisce false. */
    fun installa(ctx: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.file", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
