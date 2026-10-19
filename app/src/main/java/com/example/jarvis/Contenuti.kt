package com.example.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** File, pagine web e foto: analisi, traduzioni e resoconto della settimana. */
class Contenuti(private val c: Cervello) {

    private val ctx: Context get() = c.contesto()
    private fun usaClaude() = c.usaClaude()
    private fun chiaveAttiva(): String = c.chiaveAttiva()
    private val integrazioni get() = c.integrazioni
    private fun nomeAssistente() = c.nomeAssistente()
    private fun personalita(): String = c.personalita()
    private fun chiamaSemplice(richiesta: String, maxToken: Int): String = c.chiamaSemplice(richiesta, maxToken)
    private fun leggiDiario(): JSONArray = c.leggiDiario()
    private fun geminiFile(file: List<Cervello.Contenuto>, richiesta: String): String = c.geminiFile(file, richiesta)
    private fun claudeFile(file: List<Cervello.Contenuto>, richiesta: String): String = c.claudeFile(file, richiesta)
    private fun visioneGemini(base64: String, richiesta: String): String = c.visioneGemini(base64, richiesta)
    private fun visioneClaude(base64: String, richiesta: String): String = c.visioneClaude(base64, richiesta)

    /** Il testo leggibile di una pagina web. */
    fun testoDellaPagina(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Jarvis")
        val html = try {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
        val titolo = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val corpo = html
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</h[1-6]>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
        return (if (titolo.isNotEmpty()) "Titolo: $titolo\n" else "") + corpo.take(60000)
    }


    /** Riassume, traduce o risponde a domande su un contenuto condiviso. */
    fun analizzaContenuto(c: Cervello.Contenuto, istruzione: String): String = analizzaContenuti(listOf(c), istruzione, "")


    /**
     * Risponde su uno o più file caricati (foto, PDF, documenti, audio, video).
     * @param contesto domande e risposte precedenti sugli stessi file, per le domande successive
     */
    fun analizzaContenuti(lista: List<Cervello.Contenuto>, istruzione: String, contesto: String): String {
        if (chiaveAttiva().isEmpty()) return "Manca la chiave API: inseriscila in Cervello."
        val testi = lista.filter { it.base64 == null }
        val file = lista.filter { it.base64 != null }
        val richiesta = StringBuilder()
        if (contesto.isNotBlank()) richiesta.append("Domande e risposte precedenti su questi file:\n").append(contesto.takeLast(3000)).append("\n\n")
        richiesta.append(istruzione).append("\nRispondi in italiano, in modo chiaro, adatto a essere letto ad alta voce, senza simboli di formattazione.")
        if (file.isNotEmpty()) richiesta.append("\nFile allegati: ").append(file.joinToString(", ") { it.titolo }).append(".")
        for (t in testi) richiesta.append("\n\nCONTENUTO DI \"${t.titolo}\":\n").append(t.testo.take(60000 / testi.size.coerceAtLeast(1)))
        val inizio = System.currentTimeMillis()
        val r = when {
            file.isEmpty() -> chiamaSemplice(richiesta.toString(), 1500)
            usaClaude() -> claudeFile(file, richiesta.toString())
            else -> geminiFile(file, richiesta.toString())
        }
        Statistiche.richiesta(ctx, "ia", System.currentTimeMillis() - inizio, lista.map { "file_${it.tipo}" })
        return r.ifBlank { "Non ho trovato niente da dire su questo contenuto." }
    }


    /** Riassunto breve da conservare nell'archivio dei documenti. */
    fun riassuntoPerArchivio(c: Cervello.Contenuto): String = analizzaContenuti(
        listOf(c),
        "Fai un riassunto di questo documento in al massimo 5 frasi, da conservare in archivio. Indica che tipo di documento è " +
            "e riporta date, scadenze, importi, nomi, indirizzi e numeri importanti.",
        ""
    )


    /** Traduzione per il traduttore simultaneo: solo il testo tradotto, niente commenti. */
    fun traduci(testo: String, da: String, a: String): String {
        if (chiaveAttiva().isEmpty()) return "Manca la chiave API."
        val r = chiamaSemplice(
            "Traduci dal $da al $a questa frase detta a voce in una conversazione. Rendi il senso in modo naturale, " +
                "come lo direbbe una persona madrelingua. Rispondi SOLO con la traduzione, senza virgolette né commenti.\n\n$testo",
            500
        )
        Statistiche.richiesta(ctx, "ia", 0, listOf("traduttore"))
        return r.trim().trim('"', '«', '»')
    }


    /** Resoconto della settimana: cosa è successo e cosa aspetta l'utente nei prossimi giorni. */
    fun resocontoSettimana(): String {
        if (chiaveAttiva().isEmpty()) return "Manca la chiave API."
        val limite = SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN).format(Date(System.currentTimeMillis() - 7 * 24 * 3600_000L))
        val diario = leggiDiario()
        val righe = (0 until diario.length()).mapNotNull { diario.optJSONObject(it) }
            .filter { it.optString("t") >= limite }
            .takeLast(150)
            .joinToString("\n") { "${it.optString("t")}: ${it.optString("d")} → ${it.optString("r").take(80)}" }
        val impegni = try {
            if (Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                integrazioni.leggiCalendario("domani", 7) else ""
        } catch (e: Exception) { "" }
        val promemoria = Promemoria.descrivi(ctx)
        val richiesta = "Sei ${nomeAssistente()}, assistente vocale. ${personalita()}\n" +
            "Scrivi il resoconto della settimana dell'utente, da leggere ad alta voce la domenica sera.\n" +
            "Racconta in 6-8 frasi: i temi principali di cui si è occupato, le cose fatte, quelle rimaste in sospeso, " +
            "e cosa lo aspetta nei prossimi giorni. Discorsivo, niente elenchi, niente simboli.\n\n" +
            "RICHIESTE DELLA SETTIMANA:\n${righe.ifBlank { "nessuna" }}\n\nIMPEGNI DEI PROSSIMI 7 GIORNI:\n${impegni.ifBlank { "nessuno" }}\n\n" +
            "PROMEMORIA ATTIVI:\n$promemoria"
        return chiamaSemplice(richiesta, 900).ifBlank { "Questa settimana non ho abbastanza informazioni per un resoconto." }
    }


    /** Vista dal vivo: descrive un fotogramma della fotocamera (usata da VistaDalVivo). */
    fun descriviFotogramma(base64: String, domanda: String, contesto: String): String {
        val richiesta = "Sei gli occhi di ${Nome.attuale}, un assistente vocale. L'utente sta inquadrando con la fotocamera " +
            "e chiede: \"${domanda.ifBlank { "cosa vedi?" }}\". " +
            (if (contesto.isNotBlank()) "Domande e risposte precedenti: $contesto. " else "") +
            "Rispondi in italiano, in modo concreto e breve (al massimo 3 frasi, da leggere ad alta voce). " +
            "Se ci sono testi, leggili; se chiede una traduzione, traducili in italiano."
        val inizio = System.currentTimeMillis()
        val r = if (usaClaude()) visioneClaude(base64, richiesta) else visioneGemini(base64, richiesta)
        Statistiche.richiesta(ctx, "vista", System.currentTimeMillis() - inizio, listOf("vista_dal_vivo"))
        return r
    }

}
