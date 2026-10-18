package com.example.jarvis

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Casa intelligente.
 * - Home Assistant (gratuito, in casa): luci, prese, termostati, tapparelle e qualsiasi dispositivo collegato,
 *   compresi quelli di Google Home, Alexa, Tuya/Smart Life, Philips Hue, Shelly, Xiaomi.
 * - Scorciatoie web: un nome e un indirizzo (IFTTT, webhook di Home Assistant, Shelly, routine Alexa...).
 */
class Casa(private val prefs: SharedPreferences) {

    private val IT = Locale.ITALIAN

    private fun indirizzo() = (prefs.getString("casa_url", "") ?: "").trim().trimEnd('/')
    private fun token() = Cassaforte.leggi(prefs, "casa_token").trim()
    fun configurata() = indirizzo().isNotEmpty() && token().isNotEmpty()

    private val AVVISO = "La casa intelligente non è collegata. In SISTEMA, COLLEGAMENTI inserisci l'indirizzo e il token " +
        "di Home Assistant, oppure crea delle scorciatoie web."

    private fun richiesta(metodo: String, percorso: String, corpo: JSONObject? = null): String {
        val connessione = URL(indirizzo() + percorso).openConnection() as HttpURLConnection
        connessione.requestMethod = metodo
        connessione.connectTimeout = 8000
        connessione.readTimeout = 15000
        connessione.setRequestProperty("Authorization", "Bearer ${token()}")
        connessione.setRequestProperty("Content-Type", "application/json")
        if (corpo != null) {
            connessione.doOutput = true
            connessione.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
        }
        val codice = connessione.responseCode
        val testo = (if (codice in 200..299) connessione.inputStream else connessione.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        connessione.disconnect()
        if (codice == 401) throw Exception("il token di Home Assistant non è valido")
        if (codice !in 200..299) throw Exception("Home Assistant ha risposto con errore $codice")
        return testo
    }

    private val DOMINI = setOf(
        "light", "switch", "climate", "cover", "fan", "media_player", "scene", "script", "lock",
        "input_boolean", "vacuum", "humidifier", "water_heater", "button", "automation"
    )

    private val NOMI_DOMINI = mapOf(
        "light" to "luce", "switch" to "presa/interruttore", "climate" to "clima", "cover" to "tapparella",
        "fan" to "ventilatore", "media_player" to "TV/casse", "scene" to "scena", "script" to "script", "lock" to "serratura",
        "input_boolean" to "interruttore", "vacuum" to "robot aspirapolvere", "humidifier" to "umidificatore",
        "water_heater" to "scaldabagno", "button" to "pulsante", "automation" to "automazione"
    )

    private val STATI = mapOf(
        "on" to "acceso", "off" to "spento", "open" to "aperta", "closed" to "chiusa", "locked" to "chiusa a chiave",
        "unlocked" to "aperta", "playing" to "in riproduzione", "paused" to "in pausa", "idle" to "inattivo",
        "heat" to "riscaldamento", "cool" to "raffreddamento", "unavailable" to "non raggiungibile", "docked" to "alla base",
        "cleaning" to "sta pulendo"
    )

    private fun dispositivi(): List<JSONObject> {
        val stati = JSONArray(richiesta("GET", "/api/states"))
        return (0 until stati.length()).mapNotNull { stati.optJSONObject(it) }
            .filter { it.optString("entity_id").substringBefore('.') in DOMINI }
    }

    private fun nome(e: JSONObject) =
        e.optJSONObject("attributes")?.optString("friendly_name")?.ifBlank { null } ?: e.optString("entity_id")

    fun elenca(filtro: String): String {
        if (!configurata()) return AVVISO
        return try {
            val f = filtro.lowercase(IT).trim()
            val elenco = dispositivi().filter { e ->
                val dominio = e.optString("entity_id").substringBefore('.')
                f.isEmpty() || nome(e).lowercase(IT).contains(f) || (NOMI_DOMINI[dominio] ?: "").contains(f.removeSuffix("i").removeSuffix("e")) ||
                    e.optString("entity_id").contains(f)
            }.take(40)
            if (elenco.isEmpty()) "Nessun dispositivo trovato" + (if (f.isNotEmpty()) " per \"$filtro\"." else ".")
            else elenco.joinToString("\n") { e ->
                val dominio = e.optString("entity_id").substringBefore('.')
                val stato = e.optString("state")
                val extra = when (dominio) {
                    "climate" -> e.optJSONObject("attributes")?.let { a ->
                        val attuale = a.opt("current_temperature")
                        val obiettivo = a.opt("temperature")
                        " (ora $attuale gradi, impostati $obiettivo)"
                    } ?: ""
                    "light" -> e.optJSONObject("attributes")?.optInt("brightness", -1)?.takeIf { it >= 0 }
                        ?.let { " (luminosità ${it * 100 / 255}%)" } ?: ""
                    else -> ""
                }
                "${nome(e)} [${NOMI_DOMINI[dominio] ?: dominio}]: ${STATI[stato] ?: stato}$extra"
            }
        } catch (e: Exception) {
            "Non riesco a raggiungere Home Assistant: ${e.message}."
        }
    }

    /** Il dispositivo il cui nome assomiglia di più a quello detto. */
    private fun trova(cercato: String, elenco: List<JSONObject>): JSONObject? {
        val c = cercato.lowercase(IT).trim()
        elenco.firstOrNull { nome(it).lowercase(IT) == c || it.optString("entity_id") == c }?.let { return it }
        val parole = c.split(Regex("\\s+")).filter { it.length > 2 && it !in setOf("del", "della", "dello", "nel", "nella", "sul", "sulla") }
        val tipo = when {
            c.contains("luc") || c.contains("lampad") -> "light"
            c.contains("tappar") || c.contains("serrand") || c.contains("tenda") -> "cover"
            c.contains("termostat") || c.contains("riscald") || c.contains("condizion") || c.contains("clima") -> "climate"
            c.contains("presa") || c.contains("interrutt") -> "switch"
            c.contains("tv") || c.contains("televis") || c.contains("cass") -> "media_player"
            c.contains("aspirapolv") || c.contains("robot") -> "vacuum"
            c.contains("scena") -> "scene"
            else -> null
        }
        return elenco.map { e ->
            val n = nome(e).lowercase(IT) + " " + e.optString("entity_id").replace('_', ' ')
            var punti = parole.count { n.contains(it.take(5)) } * 2
            if (tipo != null && e.optString("entity_id").startsWith("$tipo.")) punti += 1
            e to punti
        }.filter { it.second >= 2 }.maxByOrNull { it.second }?.first
    }

    fun comanda(dispositivo: String, azione: String, valore: String): String {
        if (!configurata()) return AVVISO
        return try {
            val elenco = dispositivi()
            val e = trova(dispositivo, elenco)
                ?: return "Non trovo \"$dispositivo\" tra i dispositivi di casa. Usa casa_dispositivi per vedere i nomi."
            val id = e.optString("entity_id")
            val dominio = id.substringBefore('.')
            val a = azione.lowercase(IT)
            val numero = Regex("-?\\d+(?:[.,]\\d+)?").find(valore)?.value?.replace(',', '.')?.toDoubleOrNull()
            val dati = JSONObject().put("entity_id", id)
            val servizio: Pair<String, String> = when {
                dominio == "scene" || dominio == "script" -> dominio to "turn_on"
                dominio == "button" -> "button" to "press"
                dominio == "cover" && numero != null -> { dati.put("position", numero.toInt().coerceIn(0, 100)); "cover" to "set_cover_position" }
                dominio == "cover" && (a.contains("apr") || a.contains("alz") || a.contains("su")) -> "cover" to "open_cover"
                dominio == "cover" && (a.contains("chiud") || a.contains("abbass") || a.contains("giù")) -> "cover" to "close_cover"
                dominio == "cover" && a.contains("ferm") -> "cover" to "stop_cover"
                dominio == "lock" && (a.contains("sblocc") || a.contains("apr")) -> "lock" to "unlock"
                dominio == "lock" -> "lock" to "lock"
                dominio == "climate" && numero != null -> { dati.put("temperature", numero); "climate" to "set_temperature" }
                dominio == "vacuum" && (a.contains("base") || a.contains("torn") || a.contains("ferm")) -> "vacuum" to "return_to_base"
                dominio == "vacuum" -> "vacuum" to "start"
                dominio == "media_player" && numero != null -> { dati.put("volume_level", (numero / 100.0).coerceIn(0.0, 1.0)); "media_player" to "volume_set" }
                dominio == "light" && numero != null && !a.contains("spegn") -> { dati.put("brightness_pct", numero.toInt().coerceIn(1, 100)); "light" to "turn_on" }
                a.contains("spegn") || a.contains("disattiv") || a == "off" -> "homeassistant" to "turn_off"
                a.contains("altern") || a.contains("invert") || a.contains("toggle") -> "homeassistant" to "toggle"
                else -> "homeassistant" to "turn_on"
            }
            richiesta("POST", "/api/services/${servizio.first}/${servizio.second}", dati)
            val fatto = when (servizio.second) {
                "turn_off" -> "spento"
                "toggle" -> "cambiato"
                "open_cover" -> "aperta"
                "close_cover" -> "chiusa"
                "set_cover_position" -> "al ${numero?.toInt()}%"
                "set_temperature" -> "impostato a ${numero} gradi"
                "volume_set" -> "volume al ${numero?.toInt()}%"
                "lock" -> "chiusa a chiave"
                "unlock" -> "aperta"
                "press", "start" -> "avviato"
                "return_to_base" -> "torna alla base"
                else -> if (dati.has("brightness_pct")) "acceso al ${numero?.toInt()}%" else "acceso"
            }
            "${nome(e)}: $fatto."
        } catch (e: Exception) {
            "Non riesco a comandare la casa: ${e.message}."
        }
    }

    // ---------- scorciatoie web ----------

    /** Le scorciatoie scritte nelle impostazioni, una per riga: nome = indirizzo */
    fun scorciatoie(): List<Pair<String, String>> =
        (prefs.getString("scorciatoie", "") ?: "").lines()
            .mapNotNull { riga ->
                val i = riga.indexOf('=')
                if (i <= 0) null else riga.substring(0, i).trim() to riga.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() && (it.second.startsWith("http") || it.second.startsWith("POST ", ignoreCase = true)) }

    fun scorciatoia(nome: String): String {
        val elenco = scorciatoie()
        if (elenco.isEmpty()) return "Non ci sono scorciatoie. Puoi crearle in SISTEMA, COLLEGAMENTI, scrivendo per esempio: cancello = https://..."
        val n = nome.lowercase(IT).trim()
        val (titolo, url) = elenco.firstOrNull { it.first.lowercase(IT) == n }
            ?: elenco.firstOrNull { it.first.lowercase(IT).contains(n) || n.contains(it.first.lowercase(IT)) }
            ?: return "Non trovo la scorciatoia \"$nome\". Quelle disponibili sono: ${elenco.joinToString(", ") { it.first }}."
        return try {
            // "POST " davanti all'indirizzo per i webhook che vogliono POST
            val post = url.startsWith("POST ", ignoreCase = true)
            val indirizzo = if (post) url.substring(5).trim() else url
            val c = URL(indirizzo).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            if (post || indirizzo.contains("/api/webhook/") || indirizzo.contains("maker.ifttt.com")) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write("{}".toByteArray()) }
            }
            val codice = c.responseCode
            c.disconnect()
            if (codice in 200..299) "Scorciatoia $titolo eseguita." else "La scorciatoia $titolo ha risposto con errore $codice."
        } catch (e: Exception) {
            "Non riesco a eseguire la scorciatoia $titolo: ${e.message}."
        }
    }
}
