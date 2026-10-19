package com.example.jarvis

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Le scelte di personalizzazione della schermata: stile del menù, gesti, blocchi della home,
 * pulsanti rapidi e sfondo con la tua foto. Qui ci sono solo i dati; la schermata le disegna.
 */
object Personalizza {

    // ================================================================
    //  STILE DEL MENÙ
    // ================================================================

    val STILI_MENU = listOf(
        "dock" to "Dock in basso: Casa, Chat, Parla, Giochi e Opzioni sempre a portata",
        "radiale" to "Solo radiale: tieni premuta la sfera e il menù si apre a ventaglio"
    )

    fun stileMenu(p: SharedPreferences) = (p.getString("stile_menu", "dock") ?: "dock")
        .let { s -> if (STILI_MENU.any { it.first == s }) s else "dock" }

    /** Gesti sulla sfera (scorri su, giù, a sinistra, a destra): si possono usare con qualsiasi stile. */
    fun gesti(p: SharedPreferences) = p.getBoolean("gesti_sfera", false)

    /** Cosa fa ogni gesto sulla sfera (stile "gesti"). */
    val AZIONI_GESTO = listOf(
        "chat" to "Chat e cronologia",
        "opzioni" to "Opzioni (tutte le voci)",
        "giochi" to "Giochi",
        "agenda" to "Agenda di oggi",
        "meteo" to "Meteo",
        "trofei" to "Livelli e trofei",
        "minigiochi" to "Minigiochi",
        "personalizza" to "Personalizza",
        "niente" to "Niente"
    )

    val DIREZIONI = listOf("su" to "Scorri in su", "giu" to "Scorri in giù", "sinistra" to "Scorri a sinistra", "destra" to "Scorri a destra")

    private val GESTI_PREDEFINITI = mapOf("su" to "chat", "giu" to "opzioni", "sinistra" to "giochi", "destra" to "agenda")

    fun azioneGesto(p: SharedPreferences, direzione: String) =
        p.getString("gesto_$direzione", GESTI_PREDEFINITI[direzione] ?: "niente") ?: "niente"

    fun nomeAzione(id: String) = AZIONI_GESTO.firstOrNull { it.first == id }?.second ?: id

    // ================================================================
    //  BLOCCHI DELLA HOME
    // ================================================================

    val BLOCCHI = listOf(
        "meteo" to "Meteo",
        "batteria" to "Batteria",
        "agenda" to "Agenda di oggi",
        "promemoria" to "Promemoria",
        "gioco" to "Gioco del giorno",
        "trofei" to "Livello e trofei",
        "sfida" to "Sfida di oggi",
        "spesa" to "Lista della spesa"
    )

    fun nomeBlocco(id: String) = BLOCCHI.firstOrNull { it.first == id }?.second ?: id

    /** I blocchi scelti, nell'ordine in cui compaiono. Nessuno = la home di sempre. */
    fun blocchi(p: SharedPreferences): List<String> =
        (p.getString("blocchi", "") ?: "").split(",").map { it.trim() }.filter { id -> BLOCCHI.any { it.first == id } }.distinct()

    fun scriviBlocchi(e: SharedPreferences.Editor, elenco: List<String>): SharedPreferences.Editor =
        e.putString("blocchi", elenco.distinct().joinToString(","))

    val DIMENSIONI_SFERA = listOf("grande" to "Grande", "media" to "Media", "piccola" to "Piccola")

    fun pesoSfera(p: SharedPreferences) = when (p.getString("sfera_dimensione", "grande")) {
        "piccola" -> 0.6f
        "media" -> 0.95f
        else -> 1.3f
    }

    // ================================================================
    //  PULSANTI RAPIDI
    // ================================================================

    class Pulsante(val id: String, val etichetta: String, val frase: String)

    /** Frasi speciali che non vanno al cervello ma fanno un'azione della schermata. */
    const val ZITTO = "#zitto"
    const val GIOCO_DEL_GIORNO = "#gioco"
    const val MINIGIOCHI = "#minigiochi"
    const val TROFEI = "#trofei"
    const val TORCIA = "#torcia"

    val PULSANTI = listOf(
        Pulsante("torcia", "Torcia", TORCIA),
        Pulsante("meteo", "Meteo", "che tempo fa"),
        Pulsante("sveglia", "Sveglia 7:00", "metti la sveglia alle 7"),
        Pulsante("musica", "Musica", "metti la musica"),
        Pulsante("zitto", "Zitto", ZITTO),
        Pulsante("timer", "Timer 10 min", "timer di 10 minuti"),
        Pulsante("luci", "Luci", "accendi le luci"),
        Pulsante("gioco", "Gioco del giorno", GIOCO_DEL_GIORNO),
        Pulsante("dove", "Dove sono", "dove sono"),
        Pulsante("notizie", "Notizie", "dimmi le notizie"),
        Pulsante("agenda", "Agenda", "cosa ho oggi"),
        Pulsante("ripeti", "Ripeti", "ripeti"),
        Pulsante("minigiochi", "Minigiochi", MINIGIOCHI),
        Pulsante("trofei", "Trofei", TROFEI)
    )

    const val MAX_PULSANTI = 6

    /** I pulsanti inventati da te: (etichetta, frase). */
    fun pulsantiMiei(p: SharedPreferences): List<Pulsante> = try {
        val a = JSONArray(p.getString("pulsanti_miei", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val e = o.optString("e").trim()
            val f = o.optString("f").trim()
            if (e.isEmpty() || f.isEmpty()) null else Pulsante("mio$i", e, f)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun scriviPulsantiMiei(e: SharedPreferences.Editor, elenco: List<Pair<String, String>>): SharedPreferences.Editor {
        val a = JSONArray()
        for ((etichetta, frase) in elenco) a.put(JSONObject().put("e", etichetta).put("f", frase))
        return e.putString("pulsanti_miei", a.toString())
    }

    fun tuttiIPulsanti(p: SharedPreferences) = PULSANTI + pulsantiMiei(p)

    /** I pulsanti scelti per la home, al massimo sei. */
    fun pulsanti(p: SharedPreferences): List<Pulsante> {
        val tutti = tuttiIPulsanti(p)
        return (p.getString("pulsanti", "") ?: "").split(",").mapNotNull { id -> tutti.firstOrNull { it.id == id } }
            .distinctBy { it.id }.take(MAX_PULSANTI)
    }

    fun scriviPulsanti(e: SharedPreferences.Editor, ids: List<String>): SharedPreferences.Editor =
        e.putString("pulsanti", ids.distinct().take(MAX_PULSANTI).joinToString(","))

    // ================================================================
    //  SFONDO
    // ================================================================

    fun fileFoto(ctx: Context) = File(ctx.filesDir, "sfondo_foto.jpg")

    private val SFONDI_DEL_GIORNO = listOf("stelle", "griglia", "onde", "nessuno")

    /** Lo sfondo di oggi: quello scelto, oppure uno diverso ogni giorno. */
    fun sfondoAttuale(ctx: Context, p: SharedPreferences): String {
        if (p.getBoolean("sfondo_giornaliero", false)) {
            val scelte = if (fileFoto(ctx).exists()) SFONDI_DEL_GIORNO + "foto" else SFONDI_DEL_GIORNO
            return scelte[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % scelte.size]
        }
        val s = p.getString("sfondo", "nessuno") ?: "nessuno"
        return if (s == "foto" && !fileFoto(ctx).exists()) "nessuno" else s
    }

    fun oscura(p: SharedPreferences) = p.getInt("sfondo_oscura", 55).coerceIn(0, 90)
    fun sfocatura(p: SharedPreferences) = p.getInt("sfondo_sfoca", 20).coerceIn(0, 100)

    /** Le chiavi toccate dalla personalizzazione (per annullare una prova). */
    val CHIAVI = listOf(
        "stile_menu", "gesti_sfera", "interfaccia", "testo_dimensione", "testo_carattere", "conversazione", "mostra_dati", "animazioni", "gesto_su", "gesto_giu", "gesto_sinistra", "gesto_destra",
        "blocchi", "blocchi_sopra", "sfera_dimensione",
        "pulsanti", "pulsanti_miei",
        "sfondo", "sfondo_oscura", "sfondo_sfoca", "sfondo_giornaliero",
        "suono_sveglia", "vibrazione_sveglia", "suoni_volume",
        "suoni_fine", "suoni_conferma", "suoni_pensa", "suoni_trofeo", "suoni_notte",
        "forma", "3d_alone", "3d_velocita", "3d_dimensione", "tema", "r_tonalita"
    )

    /** Fotografia delle scelte attuali, per poterle ripristinare se la prova non piace. */
    fun istantanea(p: SharedPreferences): Map<String, Any?> = CHIAVI.associateWith { p.all[it] }

    fun ripristina(p: SharedPreferences, foto: Map<String, Any?>) {
        val e = p.edit()
        for ((k, v) in foto) {
            when (v) {
                null -> e.remove(k)
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Float -> e.putFloat(k, v)
                is Long -> e.putLong(k, v)
            }
        }
        e.commit()
    }
}
