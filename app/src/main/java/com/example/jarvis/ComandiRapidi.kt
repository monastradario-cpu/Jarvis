package com.example.jarvis

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Comandi semplici eseguiti subito sul telefono, senza intelligenza artificiale:
 * istantanei, funzionano anche senza internet e non consumano richieste di Gemini o Claude.
 * Se la frase non è chiara restituisce null e decide il cervello.
 */
object ComandiRapidi {

    private val IT = Locale.ITALIAN

    private val NUMERI = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "un'" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12,
        "tredici" to 13, "quattordici" to 14, "quindici" to 15, "venti" to 20, "venticinque" to 25,
        "trenta" to 30, "quaranta" to 40, "quarantacinque" to 45, "cinquanta" to 50, "sessanta" to 60, "novanta" to 90
    )

    private fun numero(s: String): Int? = s.trim().toIntOrNull() ?: NUMERI[s.trim()]

    private val APP_MUSICALI = "spotify|youtube music|youtube|amazon music|deezer|apple music"

    fun prova(c: Cervello, originale: String): String? {
        var t = originale.lowercase(IT).trim()
            .replace(Regex("[.!?]+$"), "")
            .replace(Regex("\\s+"), " ")
            .replace("’", "'")
        t = t.replace(Regex("^((ehi|ciao|ok|hey) )?jarvis,? "), "")
            .replace(Regex("^(per favore|perfavore|puoi|potresti|mi puoi|riesci a) "), "")
            .replace(Regex(",? (per favore|grazie)$"), "")
            .trim()
        if (t.isEmpty()) return null

        // ---------- torcia ----------
        Regex("^(accendi|attiva|spegni|disattiva|accendere|spegnere)( la| il)? (torcia|flash|luce del telefono)$").find(t)?.let {
            return c.torcia(!it.groupValues[1].startsWith("spegn") && !it.groupValues[1].startsWith("disatt"))
        }
        Regex("^(torcia|flash) (accesa|on|spenta|off)$").find(t)?.let {
            return c.torcia(it.groupValues[2] == "accesa" || it.groupValues[2] == "on")
        }

        // ---------- volume ----------
        Regex("^(metti|imposta|porta)?( il)? ?volume (al|a) (\\d{1,3}) ?(%|per ?cento)?$").find(t)?.let {
            return c.volumePercento(it.groupValues[4].toInt())
        }
        if (Regex("^((alza|metti)( il)? volume al massimo|volume (al )?massimo|massimo volume)$").matches(t)) return c.regolaVolume("massimo", 1)
        if (Regex("^(alza|aumenta)( un po')?( il)? volume( un po')?$|^volume (su|più alto)$|^più forte$").matches(t)) return c.regolaVolume("alza", 3)
        if (Regex("^(abbassa|diminuisci|riduci)( un po')?( il)? volume( un po')?$|^volume (giù|più basso)$|^più piano$").matches(t)) return c.regolaVolume("abbassa", 3)
        if (Regex("^(muto|silenzia( tutto)?|togli (l'audio|il volume|l'audio al telefono)|metti (in )?muto|azzera il volume)$").matches(t)) return c.regolaVolume("muto", 1)

        // ---------- ora, data, batteria ----------
        if (Regex("^((che|sai che|mi dici che) ore sono|che ora è|che ora e|dimmi l'ora|mi dici l'ora|ora esatta)$").matches(t)) {
            return "Sono le " + SimpleDateFormat("H:mm", IT).format(Date()) + "."
        }
        if (Regex("^(che giorno (è|e)( oggi)?|che data (è|e)( oggi)?|quanti ne abbiamo( oggi)?|oggi che giorno (è|e))$").matches(t)) {
            return "Oggi è " + SimpleDateFormat("EEEE d MMMM yyyy", IT).format(Date()) + "."
        }
        if (Regex("^(quanta batteria (ho|c'è|rimane|resta|mi resta|mi rimane)|livello (della )?batteria|batteria|carica della batteria)$").matches(t)) {
            return c.batteria()
        }

        // ---------- sveglia ----------
        Regex(
            "^(?:(?:metti|imposta|punta|fissa|programma)(?:mi)?(?: una| la)? sveglia|svegliami)" +
                "(?: domani)? (?:alle|per le|a|all') ?(\\d{1,2})(?:[:.](\\d{2})| e (mezza|mezzo|un quarto|tre quarti|\\d{1,2}))?" +
                "(?: (?:di|del|della) (mattina|sera|pomeriggio|notte))?$"
        ).find(t)?.let { m ->
            var ora = m.groupValues[1].toInt()
            val minuti = when {
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
                m.groupValues[3] == "mezza" || m.groupValues[3] == "mezzo" -> 30
                m.groupValues[3] == "un quarto" -> 15
                m.groupValues[3] == "tre quarti" -> 45
                m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
                else -> 0
            }
            if ((m.groupValues[4] == "sera" || m.groupValues[4] == "pomeriggio") && ora < 12) ora += 12
            if (ora !in 0..23 || minuti !in 0..59) return null
            return c.impostaSveglia(ora, minuti, "")
        }

        // ---------- timer ----------
        Regex(
            "^(?:(?:metti|imposta|avvia|fai partire|fammi partire|parti con)(?: un| il)? )?timer(?: di| da| per)? " +
                "(\\d+|un|uno|una|due|tre|quattro|cinque|sei|sette|otto|nove|dieci|quindici|venti|trenta|quaranta|quarantacinque|un'|mezz'ora|mezzora)" +
                " ?(minuti|minuto|secondi|secondo|ore|ora)?$"
        ).find(t)?.let { m ->
            val q = m.groupValues[1]
            val secondi = when {
                q == "mezz'ora" || q == "mezzora" -> 1800
                else -> {
                    val n = numero(q) ?: return null
                    when (m.groupValues[2]) {
                        "secondi", "secondo" -> n
                        "ore", "ora" -> n * 3600
                        else -> n * 60
                    }
                }
            }
            return c.impostaTimer(secondi, "")
        }

        // ---------- controlli della musica ----------
        if (Regex("^((metti )?(in )?pausa( la musica)?|ferma la musica|stop( alla)? musica|stoppa( la musica)?|metti pausa)$").matches(t)) {
            return c.controllaMusica("pausa")
        }
        if (Regex("^(riprendi( la musica| la riproduzione)?|play|continua la musica|fai ripartire la musica|rimetti la musica)$").matches(t)) {
            return c.controllaMusica("play")
        }
        if (Regex("^((la )?(prossima|successiva)( canzone| traccia)?|(il )?(prossimo|successivo)( brano)?|(canzone|brano) successiv[ao]|salta( la canzone| il brano| questa| questo)?|cambia canzone)$").matches(t)) {
            return c.controllaMusica("avanti")
        }
        if (Regex("^((la )?canzone precedente|(il )?brano precedente|torna alla canzone precedente|metti la precedente)$").matches(t)) {
            return c.controllaMusica("indietro")
        }

        // ---------- musica ----------
        Regex("^(?:riproduci|fammi sentire|fammi ascoltare|suona|voglio ascoltare|ascoltiamo|metti su)(?: un po' di| della| dei| delle| la| il| lo| i| le| gli| l')? ?(.+?)(?: su ($APP_MUSICALI))?$")
            .find(t)?.let { m -> return musica(c, m.groupValues[1], m.groupValues[2]) }
        Regex("^metti(?: un po' di| della| la| il)? (musica|una canzone|la canzone|canzoni|l'album|la playlist|un album|una playlist)(?: di| del| dei| degli| delle)? ?(.*?)(?: su ($APP_MUSICALI))?$")
            .find(t)?.let { m -> return musica(c, m.groupValues[2], m.groupValues[3]) }
        Regex("^metti (.+) su ($APP_MUSICALI)$").find(t)?.let { m -> return musica(c, m.groupValues[1], m.groupValues[2]) }

        // ---------- chiamate ----------
        Regex("^(?:chiama|telefona a|fai una chiamata a|fai una telefonata a) (.{2,40})$").find(t)?.let { m ->
            var chi = m.groupValues[1]
            if (Regex(" (e|per|dicendo|che|quando|domani|tra|alle)( |$)").containsMatchIn(chi)) return null
            chi = chi.replace(Regex("^(la |il |lo |l'|i |a |mia |mio |al |alla )+"), "").trim()
            if (chi.isEmpty()) return null
            return c.chiama(chi)
        }

        // ---------- note ----------
        Regex("^(?:prendi nota|annota|scrivi una nota|segna|appunta)(?: che|:|,)? (.{3,})$").find(t)?.let { m ->
            return c.prendiNota(m.groupValues[1].replaceFirstChar { it.titlecase(IT) })
        }

        // ---------- apri un'app ----------
        Regex("^(?:apri|avvia|lancia|aprimi)(?: l'app| l'applicazione| app| la app)? ([\\p{L}\\d' .+&-]{2,30})$").find(t)?.let { m ->
            val nome = m.groupValues[1].trim().replace(Regex("^(la |il |lo |l')"), "")
            if (nome.split(" ").size > 3 || Regex(" (e|per|su|con|a) ").containsMatchIn(" $nome ")) return null
            val esito = c.apriApp(nome)
            if (!esito.startsWith("Ho aperto ")) return null      // non è un'app: decide il cervello
            return "Apro " + esito.removePrefix("Ho aperto ").substringBefore(". Se") .trimEnd('.') + "."
        }
        return null
    }

    private fun musica(c: Cervello, cosa: String, app: String): String {
        val pulito = cosa.trim().removePrefix("musica").trim().removePrefix("di ").trim()
        val generico = pulito.isEmpty() || pulito in setOf("musica", "qualcosa", "un po' di musica", "la musica", "canzoni")
        return c.riproduci(if (generico) "" else pulito, app)
    }
}
