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

/** Il collegamento con i cervelli in rete: Gemini (formato OpenAI e API nativa) e Claude, normali e in streaming. */
class ClientIA(private val c: Cervello) {

    private val prefs get() = c.prefs

    // ================================================================
    //  CERVELLO CLAUDE (API nativa di Anthropic)
    // ================================================================

    internal fun modelloClaude() =
        (prefs.getString("modello_claude", Cervello.MODELLO_CLAUDE_PREDEFINITO) ?: Cervello.MODELLO_CLAUDE_PREDEFINITO).trim()


    /** Risposta di Claude in streaming: il testo arriva a pezzi e Jarvis inizia a parlare subito. */
    internal fun postClaudeFlusso(corpo: JSONObject, alPezzo: (String) -> Unit): JSONObject {
        val chiave = Cassaforte.leggi(prefs, "chiave_claude").trim()
        val connessione = URL(Cervello.URL_CLAUDE).openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 90000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("Accept", "text/event-stream")
        connessione.setRequestProperty("x-api-key", chiave)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        val richiesta = JSONObject(corpo.toString()).put("stream", true)
        connessione.outputStream.use { it.write(richiesta.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        if (codice !in 200..299) {
            val testo = connessione.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            connessione.disconnect()
            throw erroreClaude(codice, testo)
        }
        val blocchi = java.util.TreeMap<Int, JSONObject>()
        val jsonParziali = HashMap<Int, StringBuilder>()
        var motivo = ""
        try {
            connessione.inputStream.bufferedReader(Charsets.UTF_8).use { lettore ->
                while (true) {
                    val riga = lettore.readLine() ?: break
                    if (!riga.startsWith("data:")) continue
                    val evento = try { JSONObject(riga.substring(5).trim()) } catch (e: Exception) { continue }
                    val indice = evento.optInt("index", 0)
                    when (evento.optString("type")) {
                        "content_block_start" -> {
                            val b = JSONObject(evento.optJSONObject("content_block")?.toString() ?: "{}")
                            blocchi[indice] = b
                            if (b.optString("type") == "tool_use" || b.optString("type") == "server_tool_use") {
                                jsonParziali[indice] = StringBuilder()
                            }
                        }
                        "content_block_delta" -> {
                            val b = blocchi[indice] ?: continue
                            val d = evento.optJSONObject("delta") ?: continue
                            when (d.optString("type")) {
                                "text_delta" -> {
                                    val t = d.optString("text")
                                    b.put("text", b.optString("text") + t)
                                    if (t.isNotEmpty()) {
                                        pezziInviati = true
                                        alPezzo(t)
                                    }
                                }
                                "input_json_delta" -> jsonParziali[indice]?.append(d.optString("partial_json"))
                                "citations_delta" -> {
                                    val elenco = b.optJSONArray("citations") ?: JSONArray().also { b.put("citations", it) }
                                    d.optJSONObject("citation")?.let { elenco.put(it) }
                                }
                                "thinking_delta" -> b.put("thinking", b.optString("thinking") + d.optString("thinking"))
                                "signature_delta" -> b.put("signature", d.optString("signature"))
                            }
                        }
                        "content_block_stop" -> {
                            val parziale = jsonParziali.remove(indice)
                            val b = blocchi[indice]
                            if (parziale != null && b != null) {
                                b.put("input", if (parziale.isBlank()) JSONObject() else try {
                                    JSONObject(parziale.toString())
                                } catch (e: Exception) {
                                    JSONObject()
                                })
                            }
                        }
                        "message_delta" -> {
                            val m = evento.optJSONObject("delta")?.optString("stop_reason") ?: ""
                            if (m.isNotEmpty() && m != "null") motivo = m
                        }
                        "error" -> throw ErroreApi(529, "i server di Claude sono occupati, riprova tra poco.")
                    }
                }
            }
        } finally {
            connessione.disconnect()
        }
        val contenuto = JSONArray()
        for (b in blocchi.values) {
            if (b.has("citations") && b.isNull("citations")) b.remove("citations")
            contenuto.put(b)
        }
        return JSONObject().put("content", contenuto).put("stop_reason", motivo)
    }


    /** Domanda singola a Claude senza strumenti (usata per riassumere e riflettere). */
    internal fun chiamaClaudeSemplice(richiesta: String, maxToken: Int): String {
        val modello = modelloClaude()
        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", maxToken)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", richiesta)))
        if (modello.contains("haiku")) corpo.put("thinking", JSONObject().put("type", "disabled"))
        val contenuto = postClaude(corpo).optJSONArray("content") ?: return ""
        val testo = StringBuilder()
        for (i in 0 until contenuto.length()) {
            val b = contenuto.getJSONObject(i)
            if (b.optString("type") == "text") testo.append(b.optString("text"))
        }
        return testo.toString().trim()
    }


    internal fun postClaude(corpo: JSONObject): JSONObject {
        val chiave = Cassaforte.leggi(prefs, "chiave_claude").trim()
        val connessione = URL(Cervello.URL_CLAUDE).openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 90000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("x-api-key", chiave)
        connessione.setRequestProperty("anthropic-version", "2023-06-01")
        connessione.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        val flusso = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val risposta = flusso?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        connessione.disconnect()

        if (codice !in 200..299) throw erroreClaude(codice, risposta)
        return JSONObject(risposta)
    }


    internal fun erroreClaude(codice: Int, risposta: String): ErroreApi {
        val dettaglio = estraiErrore(risposta)
        val credito = dettaglio.contains("credit balance", ignoreCase = true)
        val messaggio = when {
            credito -> "il credito API di Claude è esaurito, ricaricalo su platform.claude.com."
            codice == 401 || codice == 403 -> "la chiave API di Claude non è valida, controllala nelle impostazioni."
            codice == 404 -> "modello Claude non trovato, controlla il nome nelle impostazioni."
            codice == 429 -> "troppe richieste in poco tempo, riprova tra qualche secondo."
            codice == 529 || codice >= 500 -> "i server di Claude sono occupati, riprova tra poco."
            else -> "errore $codice. $dettaglio"
        }
        val ripetibile = codice == 400 && !credito && dettaglio.contains("messages", ignoreCase = true)
        return ErroreApi(codice, messaggio, ripetibile)
    }


    /** Domanda singola a Gemini senza strumenti (usata per riassumere e riflettere). */
    internal fun chiamaGeminiSemplice(richiesta: String): String {
        val modello = prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO
        val corpo = JSONObject()
            .put("model", modello)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", richiesta)))
        val messaggio = postGemini(corpo).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (messaggio.isNull("content")) "" else messaggio.getString("content").trim()
    }


    /** Risposta di Gemini in streaming: il testo arriva a pezzi e Jarvis inizia a parlare subito. */
    internal fun postGeminiFlusso(corpo: JSONObject, alPezzo: (String) -> Unit): JSONObject {
        val base = (prefs.getString("base_url", Cervello.URL_PREDEFINITO) ?: Cervello.URL_PREDEFINITO).trim().trimEnd('/')
        val chiave = Cassaforte.leggi(prefs, "api_key").trim()
        val connessione = URL("$base/chat/completions").openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 60000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("Accept", "text/event-stream")
        connessione.setRequestProperty("Authorization", "Bearer $chiave")
        val richiesta = JSONObject(corpo.toString()).put("stream", true)
        connessione.outputStream.use { it.write(richiesta.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        if (codice !in 200..299) {
            val testo = connessione.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            connessione.disconnect()
            throw erroreGemini(codice, testo)
        }
        val testo = StringBuilder()
        val chiamate = mutableListOf<JSONObject>()
        val perIndice = HashMap<Int, JSONObject>()
        var extra: Any? = null
        try {
            connessione.inputStream.bufferedReader(Charsets.UTF_8).use { lettore ->
                while (true) {
                    val riga = lettore.readLine() ?: break
                    if (!riga.startsWith("data:")) continue
                    val dati = riga.substring(5).trim()
                    if (dati == "[DONE]") break
                    if (dati.isEmpty()) continue
                    val pezzo = try { JSONObject(dati) } catch (e: Exception) { continue }
                    pezzo.optJSONObject("error")?.let { throw ErroreApi(500, "errore di Gemini: ${it.optString("message")}") }
                    val delta = pezzo.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    if (delta.has("extra_content")) extra = delta.get("extra_content")
                    val contenuto = if (delta.isNull("content")) "" else delta.optString("content")
                    if (contenuto.isNotEmpty()) {
                        testo.append(contenuto)
                        pezziInviati = true
                        alPezzo(contenuto)
                    }
                    val nuove = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until nuove.length()) {
                        val d = nuove.optJSONObject(i) ?: continue
                        val id = d.optString("id")
                        val indice = if (d.has("index")) d.optInt("index") else -1
                        var voce: JSONObject? = if (indice >= 0) perIndice[indice] else null
                        // stesso indice ma id diverso: è una chiamata nuova
                        if (voce != null && id.isNotEmpty() && voce.optString("id").isNotEmpty() && voce.optString("id") != id) voce = null
                        if (voce == null && indice < 0 && id.isNotEmpty()) voce = chiamate.firstOrNull { it.optString("id") == id }
                        if (voce == null && indice < 0 && id.isEmpty()) voce = chiamate.lastOrNull()
                        if (voce == null) {
                            voce = JSONObject().put("id", id).put("type", "function")
                                .put("function", JSONObject().put("name", "").put("arguments", ""))
                            chiamate.add(voce)
                            if (indice >= 0) perIndice[indice] = voce
                        }
                        if (id.isNotEmpty()) voce.put("id", id)
                        d.optJSONObject("function")?.let { f ->
                            val fn = voce.getJSONObject("function")
                            if (f.has("name") && !f.isNull("name")) fn.put("name", fn.optString("name") + f.optString("name"))
                            if (f.has("arguments") && !f.isNull("arguments")) fn.put("arguments", fn.optString("arguments") + f.optString("arguments"))
                        }
                        if (d.has("extra_content")) voce.put("extra_content", d.get("extra_content"))
                    }
                }
            }
        } finally {
            connessione.disconnect()
        }
        val messaggio = JSONObject().put("role", "assistant")
        messaggio.put("content", if (testo.isEmpty()) JSONObject.NULL else testo.toString())
        if (chiamate.isNotEmpty()) messaggio.put("tool_calls", JSONArray(chiamate))
        if (extra != null) messaggio.put("extra_content", extra)
        return messaggio
    }


    internal fun erroreGemini(codice: Int, risposta: String) = ErroreApi(
        codice,
        when (codice) {
            400 -> "richiesta non valida. ${estraiErrore(risposta)}"
            401, 403 -> "la chiave API non è valida, controllala nelle impostazioni."
            404 -> "modello o indirizzo non trovato, controlla le impostazioni."
            429 -> "ho raggiunto il limite di richieste gratuite, riprova tra un po'."
            else -> "errore $codice. ${estraiErrore(risposta)}"
        }
    )


    internal fun postGemini(corpo: JSONObject): JSONObject {
        val base = (prefs.getString("base_url", Cervello.URL_PREDEFINITO) ?: Cervello.URL_PREDEFINITO).trim().trimEnd('/')
        val chiave = Cassaforte.leggi(prefs, "api_key").trim()
        val connessione = URL("$base/chat/completions").openConnection() as HttpURLConnection
        connessione.requestMethod = "POST"
        connessione.connectTimeout = 15000
        connessione.readTimeout = 60000
        connessione.doOutput = true
        connessione.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connessione.setRequestProperty("Authorization", "Bearer $chiave")
        connessione.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

        val codice = connessione.responseCode
        val flusso = if (codice in 200..299) connessione.inputStream else connessione.errorStream
        val risposta = flusso?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        connessione.disconnect()

        if (codice !in 200..299) throw erroreGemini(codice, risposta)
        return JSONObject(risposta)
    }


    /** Domanda semplice al cervello attivo (Claude o Gemini). */
    internal fun chiamaSemplice(richiesta: String, maxToken: Int): String =
        if (usaClaude()) chiamaClaudeSemplice(richiesta, maxToken) else chiamaGeminiSemplice(richiesta)


    internal fun estraiErrore(testo: String): String = try {
        val radiceJson = JSONObject(testo)
        val errore = radiceJson.opt("error")
        when (errore) {
            is JSONObject -> errore.optString("message", testo)
            is JSONArray -> errore.optJSONObject(0)?.optJSONObject("error")?.optString("message") ?: testo
            else -> testo
        }
    } catch (e: Exception) {
        testo.take(200)
    }


    /** Gemini (API nativa) legge immagini, PDF, audio e video direttamente. */
    internal fun geminiFile(file: List<Cervello.Contenuto>, richiesta: String): String {
        val modello = prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO
        val parti = JSONArray()
        for (f in file) parti.put(JSONObject().put("inline_data", JSONObject().put("mime_type", f.mime).put("data", f.base64)))
        parti.put(JSONObject().put("text", richiesta))
        val corpo = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parti)))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$modello:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 180000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-goog-api-key", Cassaforte.leggi(prefs, "api_key").trim())
        c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
        val codice = c.responseCode
        val testo = (if (codice in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (codice !in 200..299) throw erroreGemini(codice, testo)
        val risposta = JSONObject(testo).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return ""
        return (0 until risposta.length()).mapNotNull { risposta.optJSONObject(it)?.optString("text") }.joinToString(" ").trim()
    }


    /** Claude legge immagini e PDF (non audio e video). */
    internal fun claudeFile(file: List<Cervello.Contenuto>, richiesta: String): String {
        val contenuto = JSONArray()
        for (f in file) {
            when {
                f.mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp") -> contenuto.put(JSONObject().put("type", "image")
                    .put("source", JSONObject().put("type", "base64").put("media_type", f.mime).put("data", f.base64)))
                f.mime == "application/pdf" -> contenuto.put(JSONObject().put("type", "document")
                    .put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", f.base64)))
                else -> return "Con Claude posso leggere solo testi, documenti, immagini e PDF. Per audio e video scegli Gemini in Cervello."
            }
        }
        contenuto.put(JSONObject().put("type", "text").put("text", richiesta))
        val modello = modelloClaude()
        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", contenuto)))
        if (modello.contains("haiku")) corpo.put("thinking", JSONObject().put("type", "disabled"))
        val blocchi = postClaude(corpo).optJSONArray("content") ?: return ""
        return (0 until blocchi.length()).mapNotNull { blocchi.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
    }


    internal fun visioneGemini(base64: String, richiesta: String): String {
        val modello = prefs.getString("modello", Cervello.MODELLO_PREDEFINITO) ?: Cervello.MODELLO_PREDEFINITO
        val contenuto = JSONArray()
            .put(JSONObject().put("type", "text").put("text", richiesta))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")))
        val corpo = JSONObject()
            .put("model", modello)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", contenuto)))
        val messaggio = postGemini(corpo).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (messaggio.isNull("content")) "niente di riconoscibile." else messaggio.getString("content").trim()
    }


    internal fun visioneClaude(base64: String, richiesta: String): String {
        val modello = modelloClaude()
        val contenuto = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", base64)))
            .put(JSONObject().put("type", "text").put("text", richiesta))
        val corpo = JSONObject()
            .put("model", modello)
            .put("max_tokens", 600)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", contenuto)))
        if (modello.contains("haiku")) corpo.put("thinking", JSONObject().put("type", "disabled"))
        val blocchi = postClaude(corpo).optJSONArray("content") ?: return "niente di riconoscibile."
        return (0 until blocchi.length()).mapNotNull { blocchi.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
    }


    fun usaClaude() = prefs.getString("fornitore", "gemini") == "claude"


    fun chiaveAttiva(): String =
        (if (usaClaude()) Cassaforte.leggi(prefs, "chiave_claude") else Cassaforte.leggi(prefs, "api_key")).trim()


    @Volatile internal var pezziInviati = false
}
