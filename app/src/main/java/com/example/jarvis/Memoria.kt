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

/** La memoria di Jarvis sul telefono: ricordi, lezioni, routine, diario, riassunto e riflessione. */
class Memoria(private val c: Cervello) {

    private val ctx: Context get() = c.contesto()
    private val uscita get() = c.uscita
    private val prefs get() = c.prefs
    private val principale get() = c.principale
    private val strumentiUsati get() = c.strumentiUsati
    private val casa get() = c.casa
    private val richiestaCorrente get() = c.richiestaCorrente
    private val chiParla get() = c.chiParla
    private val privata get() = c.privata
    private fun chiamaSemplice(richiesta: String, maxToken: Int): String = c.chiamaSemplice(richiesta, maxToken)
    private fun conversazioneRecente(): String = c.conversazioneRecente()

    internal fun leggiFile(nome: String): String? = try {
        val file = File(ctx.filesDir, nome)
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        null
    }


    internal fun scriviFile(nome: String, testo: String) {
        try {
            File(ctx.filesDir, nome).writeText(testo)
        } catch (e: Exception) {
            // spazio pieno o file non scrivibile: si continua senza salvare
        }
    }


    fun leggiLista(chiave: String): MutableList<String> {
        val array = try {
            JSONArray(prefs.getString(chiave, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        return MutableList(array.length()) { array.optString(it) }
    }


    internal fun salvaLista(chiave: String, lista: List<String>) {
        prefs.edit().putString(chiave, JSONArray(lista).toString()).apply()
    }


    /** Aggiunge una voce se non c'è già. Restituisce true se è nuova. */
    internal fun aggiungiSenzaDoppioni(chiave: String, voce: String, massimo: Int): Boolean {
        val pulita = voce.trim().trimEnd('.')
        if (pulita.isEmpty()) return false
        val lista = leggiLista(chiave)
        if (lista.any { it.equals(pulita, ignoreCase = true) }) return false
        lista.add(pulita)
        while (lista.size > massimo) lista.removeAt(0)
        salvaLista(chiave, lista)
        return true
    }


    // ---------- diario delle richieste ----------

    internal fun leggiDiario(): JSONArray = try {
        JSONArray(leggiFile("diario.json") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }


    internal fun scriviNelDiario(domanda: String, risposta: String, tipo: String = "ia") {
        if (privata) return
        val voce = JSONObject()
            .put("t", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALIAN).format(Date()))
            .put("d", domanda)
            .put("r", risposta.take(300))
            .put("s", JSONArray(strumentiUsati.distinct()))
            .put("k", tipo)
            .put("u", chiParla)
        val diario = leggiDiario()
        diario.put(voce)
        val inizio = (diario.length() - Cervello.MAX_DIARIO).coerceAtLeast(0)
        val accorciato = JSONArray()
        for (i in inizio until diario.length()) accorciato.put(diario.get(i))
        scriviFile("diario.json", accorciato.toString())
    }


    internal fun cercaNelDiario(testo: String): String {
        val diario = leggiDiario()
        if (diario.length() == 0) return "Il diario è vuoto."
        val parole = testo.lowercase(Locale.ITALIAN).split(Regex("\\s+")).filter { it.length > 2 }
        val trovate = mutableListOf<String>()
        for (i in diario.length() - 1 downTo 0) {
            val v = diario.optJSONObject(i) ?: continue
            val tutto = (v.optString("t") + " " + v.optString("d") + " " + v.optString("r")).lowercase(Locale.ITALIAN)
            if (parole.isEmpty() || parole.any { tutto.contains(it) }) {
                trovate.add("${v.optString("t")}: ha chiesto \"${v.optString("d")}\", risposta: ${v.optString("r").take(120)}")
            }
            if (trovate.size >= 10) break
        }
        return if (trovate.isEmpty()) "Nessuna richiesta trovata per \"$testo\"." else trovate.joinToString("\n")
    }


    // ---------- routine ----------

    fun leggiRoutine(): JSONArray = try {
        JSONArray(prefs.getString("routine", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }


    internal fun creaRoutine(nome: String, frase: String, istruzioni: String): String {
        if (nome.isBlank() || istruzioni.isBlank()) return "Per creare una routine servono un nome e le istruzioni."
        val routine = leggiRoutine()
        val nuove = JSONArray()
        for (i in 0 until routine.length()) {
            val r = routine.getJSONObject(i)
            if (!r.optString("nome").equals(nome.trim(), ignoreCase = true)) nuove.put(r)
        }
        nuove.put(
            JSONObject()
                .put("nome", nome.trim())
                .put("frase", frase.trim().ifBlank { nome.trim() })
                .put("istruzioni", istruzioni.trim())
        )
        prefs.edit().putString("routine", nuove.toString()).apply()
        return "Routine ${nome.trim()} salvata."
    }


    internal fun eliminaRoutine(nome: String): String {
        val routine = leggiRoutine()
        val nuove = JSONArray()
        for (i in 0 until routine.length()) {
            val r = routine.getJSONObject(i)
            if (!r.optString("nome").equals(nome.trim(), ignoreCase = true)) nuove.put(r)
        }
        prefs.edit().putString("routine", nuove.toString()).apply()
        return if (nuove.length() < routine.length()) "Routine eliminata." else "Non esiste una routine chiamata $nome."
    }


    // ================================================================
    //  MEMORIA PERTINENTE: solo i ricordi che servono per la richiesta di adesso
    // ================================================================

    internal val PAROLE_VUOTE = setOf(
        "della", "delle", "degli", "dello", "nella", "nelle", "sono", "come", "cosa", "anche", "questo", "questa",
        "quello", "quella", "perché", "quando", "dove", "allora", "molto", "tutto", "tutti", "fammi", "dimmi",
        "puoi", "vorrei", "voglio", "jarvis", "utente", "sempre", "ogni", "essere", "fare", "avere", "dopo", "prima"
    )


    internal fun radici(testo: String): Set<String> = testo.lowercase(Locale.ITALIAN)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 3 && it !in PAROLE_VUOTE }
        .map { it.take(5) }
        .toSet()


    /** Fatti di base da avere sempre presenti (nome, famiglia, casa, lavoro). */
    internal val PAROLE_BASE = listOf("chiama", "nome", "vive", "abita", "lavor", "moglie", "marito", "figli", "compagn", "fidanzat", "preferisc", "non vuole", "odia")


    internal fun ricordiPertinenti(tutti: List<String>, massimo: Int = 25): List<String> {
        if (tutti.size <= massimo) return tutti
        val chiave = radici(richiestaCorrente + " " + ultimaDomandaPrecedente())
        val pertinenti = tutti.map { it to radici(it).count { r -> r in chiave } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(massimo / 2)
            .map { it.first }
        val base = tutti.filter { r -> PAROLE_BASE.any { r.lowercase(Locale.ITALIAN).contains(it) } }.take(8)
        val recenti = tutti.takeLast(6)
        return (base + pertinenti + recenti).distinct()
    }


    internal fun ultimaDomandaPrecedente(): String {
        val diario = leggiDiario()
        return diario.optJSONObject(diario.length() - 1)?.optString("d") ?: ""
    }


    /** Le 3 richieste del diario più simili a quella di adesso (escluse le ultimissime, già nella conversazione). */
    internal fun richiestePassateSimili(): String {
        val chiave = radici(richiestaCorrente)
        if (chiave.size < 2) return ""
        val diario = leggiDiario()
        val fine = (diario.length() - 6).coerceAtLeast(0)
        val trovate = (0 until fine).mapNotNull { diario.optJSONObject(it) }
            .map { it to radici(it.optString("d")).count { r -> r in chiave } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(3)
        return trovate.joinToString("; ") { (v, _) -> "${v.optString("t")} \"${v.optString("d")}\" → ${v.optString("r").take(100)}" }
    }


    internal fun cercaRicordi(testo: String): String {
        val chiave = radici(testo)
        val tutti = leggiLista("memoria") + leggiLista("lezioni")
        val trovati = tutti.filter { r -> chiave.isEmpty() || radici(r).any { it in chiave } || r.contains(testo, ignoreCase = true) }
        return if (trovati.isEmpty()) "Non ho ricordi su \"$testo\"." else trovati.take(20).joinToString("; ")
    }


    // ---------- memoria: ricordi, lezioni, dimenticare ----------

    internal fun ricorda(informazione: String): String {
        if (privata) return "Modalità privata: non salvo niente."
        // i ricordi dei familiari si riconoscono dal nome tra parentesi quadre
        val voce = if (chiParla.isNotEmpty()) "[$chiParla] $informazione" else informazione
        return if (aggiungiSenzaDoppioni("memoria", voce, Cervello.MAX_RICORDI)) "Memorizzato." else "Lo sapevo già."
    }


    internal fun imparaLezione(lezione: String): String =
        if (privata) "Modalità privata: non salvo niente." else if (aggiungiSenzaDoppioni("lezioni", lezione, Cervello.MAX_LEZIONI)) "Lezione imparata." else "La conoscevo già."


    internal fun dimentica(parola: String): String {
        val p = parola.lowercase(Locale.ITALIAN).trim()
        if (p.isEmpty()) return "Dimmi cosa devo dimenticare."
        if (p == "tutto") {
            prefs.edit().remove("memoria").remove("lezioni").remove("routine").remove("riassunto").apply()
            return "Ho cancellato ricordi, lezioni, routine e riassunto."
        }
        var tolti = 0
        for (chiave in listOf("memoria", "lezioni")) {
            val prima = leggiLista(chiave)
            val dopo = prima.filterNot { it.lowercase(Locale.ITALIAN).contains(p) }
            tolti += prima.size - dopo.size
            salvaLista(chiave, dopo)
        }
        val routine = leggiRoutine()
        val rimaste = JSONArray()
        for (i in 0 until routine.length()) {
            val r = routine.getJSONObject(i)
            if (r.toString().lowercase(Locale.ITALIAN).contains(p)) tolti++ else rimaste.put(r)
        }
        prefs.edit().putString("routine", rimaste.toString()).apply()
        return if (tolti > 0) "Fatto, l'ho dimenticato." else "Non avevo niente su $parola."
    }

    /** Le ultime richieste del diario, dalla più recente, per la schermata Cronologia. */
    fun ultimeRichieste(quante: Int): String {
        val diario = leggiDiario()
        if (diario.length() == 0) return "Ancora nessuna richiesta."
        val righe = mutableListOf<String>()
        for (i in diario.length() - 1 downTo maxOf(0, diario.length() - quante)) {
            val v = diario.optJSONObject(i) ?: continue
            righe.add("${v.optString("t")}\n› ${v.optString("d")}\n${v.optString("r")}")
        }
        return righe.joinToString("\n\n")
    }


    fun numeroRichieste() = leggiDiario().length()


    /** Il diario intero (per le statistiche). */
    fun diario(): JSONArray = leggiDiario()


    internal fun aggiornaRiassunto(testoNuovo: String) {
        val vecchio = prefs.getString("riassunto", "") ?: ""
        val richiesta = "Questo è il riassunto delle conversazioni passate tra l'utente e Jarvis, il suo assistente vocale:\n" +
            vecchio.ifBlank { "(vuoto)" } +
            "\n\nQueste sono conversazioni più recenti da aggiungere:\n$testoNuovo\n\n" +
            "Scrivi il riassunto aggiornato in italiano, al massimo 1000 caratteri. " +
            "Conserva cosa interessa all'utente, cosa chiede spesso, le cose in sospeso e le sue preferenze. " +
            "Togli i dettagli inutili. Non includere password, codici o dati bancari. " +
            "Rispondi solo con il riassunto."
        val nuovo = chiamaSemplice(richiesta, 600)
        if (nuovo.isNotBlank()) prefs.edit().putString("riassunto", nuovo.take(1500)).apply()
    }


    /** Jarvis rilegge le ultime richieste e decide da solo cosa imparare. */
    internal fun rifletti() {
        val diario = leggiDiario()
        if (diario.length() < 3) return
        val inizio = (diario.length() - 20).coerceAtLeast(0)
        val righe = StringBuilder()
        for (i in inizio until diario.length()) {
            val v = diario.optJSONObject(i) ?: continue
            val strumenti = v.optJSONArray("s")?.let { s -> (0 until s.length()).joinToString(",") { s.optString(it) } } ?: ""
            righe.append("${v.optString("t")} | ${v.optString("d")} | ${strumenti.ifBlank { "-" }} | ${v.optString("r").take(150)}\n")
        }
        val routine = leggiRoutine()
        val nomiRoutine = (0 until routine.length()).joinToString(", ") { routine.getJSONObject(it).optString("nome") }

        val richiesta = "Sei il modulo di auto-miglioramento di Jarvis, un assistente vocale su telefono Android.\n" +
            "Cose che Jarvis sa già dell'utente: ${leggiLista("memoria").joinToString("; ").ifBlank { "niente" }}\n" +
            "Lezioni già imparate: ${leggiLista("lezioni").joinToString("; ").ifBlank { "nessuna" }}\n" +
            "Routine esistenti: ${nomiRoutine.ifBlank { "nessuna" }}\n\n" +
            "Ultime richieste (data | richiesta | strumenti usati | risposta):\n$righe\n" +
            "Conversazione recente:\n${conversazioneRecente()}\n\n" +
            "Impara soprattutto dalle discussioni: interessi, progetti, persone, opinioni e preferenze emerse parlando.\n" +
            "Individua cosa imparare per servire meglio l'utente. Rispondi SOLO con un JSON valido in questo formato:\n" +
            "{\"ricordi\": [\"fatti stabili e nuovi detti dall'utente\"], " +
            "\"lezioni\": [\"regole pratiche per rispondere meglio, ad esempio preferenze o errori da evitare\"], " +
            "\"routine\": [{\"nome\": \"...\", \"frase\": \"frase che l'utente dirà\", \"istruzioni\": \"cosa fare passo per passo\"}]}\n" +
            "Regole: al massimo 3 elementi per lista; niente che sia già presente; " +
            "una routine solo se l'utente ha ripetuto la stessa sequenza di azioni almeno 3 volte; " +
            "liste vuote se non c'è niente di nuovo; non dedurre dati sensibili come salute, religione, " +
            "politica o orientamento; mai password, codici o dati bancari."

        val risposta = chiamaSemplice(richiesta, 800)
        val da = risposta.indexOf('{')
        val a = risposta.lastIndexOf('}')
        if (da < 0 || a <= da) return
        val json = JSONObject(risposta.substring(da, a + 1))

        val imparato = mutableListOf<String>()
        json.optJSONArray("ricordi")?.let { lista ->
            for (i in 0 until minOf(lista.length(), 3)) {
                val voce = lista.optString(i)
                if (aggiungiSenzaDoppioni("memoria", voce, Cervello.MAX_RICORDI)) imparato.add(voce)
            }
        }
        json.optJSONArray("lezioni")?.let { lista ->
            for (i in 0 until minOf(lista.length(), 3)) {
                val voce = lista.optString(i)
                if (aggiungiSenzaDoppioni("lezioni", voce, Cervello.MAX_LEZIONI)) imparato.add(voce)
            }
        }
        json.optJSONArray("routine")?.let { lista ->
            val esistenti = leggiRoutine()
            for (i in 0 until minOf(lista.length(), 2)) {
                val r = lista.optJSONObject(i) ?: continue
                val nome = r.optString("nome")
                val giaPresente = (0 until esistenti.length()).any {
                    esistenti.getJSONObject(it).optString("nome").equals(nome, ignoreCase = true)
                }
                if (nome.isNotBlank() && !giaPresente) {
                    creaRoutine(nome, r.optString("frase"), r.optString("istruzioni"))
                    imparato.add("nuova routine \"$nome\" (di' \"${r.optString("frase")}\")")
                }
            }
        }

        if (imparato.isNotEmpty()) {
            val testo = "Ho imparato: " + imparato.joinToString("; ") + ". Puoi vedere tutto con 🧠."
            principale.post { uscita?.notaMemoria(testo) }
        }
    }

}
