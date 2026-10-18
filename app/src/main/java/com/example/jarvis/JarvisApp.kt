package com.example.jarvis

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L'applicazione: registra gli errori che fanno chiudere Jarvis, così al riavvio
 * si può vedere (e mandare) cosa è successo. Protegge anche dalla voce neurale difettosa.
 */
class JarvisApp : Application() {

    companion object {
        const val FILE_ERRORE = "ultimo_errore.txt"

        fun errore(ctx: Context): File = File(ctx.filesDir, FILE_ERRORE)
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)

        // Se l'app si è chiusa mentre la voce neurale lavorava (errore nativo), torno alla voce del telefono
        if (prefs.getBoolean("neurale_rischio", false)) {
            prefs.edit()
                .putBoolean("neurale_rischio", false)
                .putString("motore_voce", "android")
                .putBoolean("neurale_guasta", true)
                .commit()
            try {
                errore(this).writeText(intestazione() + "La voce neurale ha fatto chiudere l'app (errore nel motore vocale). " +
                    "Sono tornato alla voce del telefono.\nVoce scelta: ${prefs.getString("voce_neurale", "")}\n")
            } catch (e: Exception) { }
        }

        val precedente = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { filo, eccezione ->
            try {
                val traccia = StringWriter().also { eccezione.printStackTrace(PrintWriter(it)) }.toString()
                errore(this).writeText(intestazione() + "Thread: ${filo.name}\n\n" + traccia.take(12000))
            } catch (e: Throwable) { }
            precedente?.uncaughtException(filo, eccezione)
        }
    }

    private fun intestazione(): String {
        val versione = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
        } catch (e: Exception) { "?" }
        return "Jarvis versione $versione · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALIAN).format(Date()) + "\n\n"
    }
}
