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

    fun prova(c: Cervello, originale: String, esegui: Boolean = true): String? {
        var t = originale.lowercase(IT).trim()
            .replace(Regex("[.!?]+$"), "")
            .replace(Regex("\\s+"), " ")
            .replace("’", "'")
        t = t.replace(Regex("^((ehi|ciao|ok|hey) )?jarvis,? "), "")
            .replace(Regex("^(per favore|perfavore|puoi|potresti|mi puoi|riesci a) "), "")
            .replace(Regex(",? (per favore|grazie)$"), "")
            .trim()
        if (t.isEmpty()) return null
        val ctx = c.contesto()

        // ---------- modalità privata ----------
        if (Regex("^((attiva|entra in|metti) (la )?modalità privata|modalità privata|non ricordare (questa conversazione|niente|nulla)( di quello che (dico|diciamo))?)$").matches(t)) {
            return fatto(esegui) { c.modalitaPrivata(true) }
        }
        if (Regex("^((esci dalla|disattiva la|fine( della)?|chiudi la|termina la) modalità privata)$").matches(t)) {
            return fatto(esegui) { c.modalitaPrivata(false) }
        }

        // ---------- liste ----------
        val nomeLista = "(?:la |nella |alla |dalla )?lista(?: (?:della|delle|dei|degli|di|del|da))? ?(.*)"
        Regex("^(?:aggiungi|metti|segna) (.+?) (?:alla|nella|in) $nomeLista$".replace("(?:la |nella |alla |dalla )?", "")).find(t)?.let { m ->
            return fatto(esegui) { Liste.aggiungi(ctx, m.groupValues[2].ifBlank { "spesa" }, m.groupValues[1].replace(Regex("^(il|lo|la|i|gli|le|l'|un|una|uno|del|della|dei|delle) "), "")) }
        }
        Regex("^(?:togli|cancella|rimuovi|elimina) (.+?) dalla $nomeLista$".replace("(?:la |nella |alla |dalla )?", "")).find(t)?.let { m ->
            return fatto(esegui) { Liste.togli(ctx, m.groupValues[2].ifBlank { "spesa" }, m.groupValues[1].replace(Regex("^(il|lo|la|i|gli|le|l'|un|una|uno) "), "")) }
        }
        Regex("^svuota $nomeLista$").find(t)?.let { m -> return fatto(esegui) { Liste.togli(ctx, m.groupValues[1].ifBlank { "spesa" }, "tutto") } }
        Regex("^(?:cosa c'è|che cosa c'è|che c'è|cosa ho|leggimi|leggi|dimmi|mostrami) $nomeLista$").find(t)?.let { m ->
            return fatto(esegui) { Liste.leggi(ctx, m.groupValues[1].ifBlank { "spesa" }) }
        }

        // ---------- torcia ----------
        Regex("^(accendi|attiva|spegni|disattiva|accendere|spegnere)( la| il)? (torcia|flash|luce del telefono)$").find(t)?.let {
            return fatto(esegui) { c.torcia(!it.groupValues[1].startsWith("spegn") && !it.groupValues[1].startsWith("disatt")) }
        }
        Regex("^(torcia|flash) (accesa|on|spenta|off)$").find(t)?.let {
            return fatto(esegui) { c.torcia(it.groupValues[2] == "accesa" || it.groupValues[2] == "on") }
        }

        // ---------- volume ----------
        Regex("^(metti|imposta|porta)?( il)? ?volume (al|a) (\\d{1,3}) ?(%|per ?cento)?$").find(t)?.let {
            return fatto(esegui) { c.volumePercento(it.groupValues[4].toInt()) }
        }
        if (Regex("^((alza|metti)( il)? volume al massimo|volume (al )?massimo|massimo volume)$").matches(t)) return fatto(esegui) { c.regolaVolume("massimo", 1) }
        if (Regex("^(alza|aumenta)( un po')?( il)? volume( un po')?$|^volume (su|più alto)$|^più forte$").matches(t)) return fatto(esegui) { c.regolaVolume("alza", 3) }
        if (Regex("^(abbassa|diminuisci|riduci)( un po')?( il)? volume( un po')?$|^volume (giù|più basso)$|^più piano$").matches(t)) return fatto(esegui) { c.regolaVolume("abbassa", 3) }
        if (Regex("^(muto|silenzia( tutto)?|togli (l'audio|il volume|l'audio al telefono)|metti (in )?muto|azzera il volume)$").matches(t)) return fatto(esegui) { c.regolaVolume("muto", 1) }
        if (Regex("^((metti|imposta|abbassa)( il)? volume al minimo|volume (al )?minimo)$").matches(t)) return fatto(esegui) { c.volumePercento(7) }

        // ---------- ora, data, batteria ----------
        if (Regex("^((che|sai che|mi dici che) ore sono|che ora è|che ora e|dimmi l'ora|mi dici l'ora|ora esatta)$").matches(t)) {
            return "Sono le " + SimpleDateFormat("H:mm", IT).format(Date()) + "."
        }
        if (Regex("^(che giorno (è|e)( oggi)?|che data (è|e)( oggi)?|quanti ne abbiamo( oggi)?|oggi che giorno (è|e))$").matches(t)) {
            return "Oggi è " + SimpleDateFormat("EEEE d MMMM yyyy", IT).format(Date()) + "."
        }
        if (Regex("^(quanta batteria (ho|c'è|rimane|resta|mi resta|mi rimane)|livello (della )?batteria|batteria|carica della batteria)$").matches(t)) {
            return fatto(esegui) { c.batteria() }
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
            return fatto(esegui) { c.impostaSveglia(ora, minuti, "") }
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
            return fatto(esegui) { c.impostaTimer(secondi, "") }
        }

        // ---------- controlli della musica ----------
        if (Regex("^((metti )?(in )?pausa( la musica)?|ferma la musica|stop( alla)? musica|stoppa( la musica)?|metti pausa)$").matches(t)) {
            return fatto(esegui) { c.controllaMusica("pausa") }
        }
        if (Regex("^(riprendi( la musica| la riproduzione)?|play|continua la musica|fai ripartire la musica|rimetti la musica)$").matches(t)) {
            return fatto(esegui) { c.controllaMusica("play") }
        }
        if (Regex("^((la )?(prossima|successiva)( canzone| traccia)?|(il )?(prossimo|successivo)( brano)?|(canzone|brano) successiv[ao]|salta( la canzone| il brano| questa| questo)?|cambia canzone)$").matches(t)) {
            return fatto(esegui) { c.controllaMusica("avanti") }
        }
        if (Regex("^((la )?canzone precedente|(il )?brano precedente|torna alla canzone precedente|metti la precedente)$").matches(t)) {
            return fatto(esegui) { c.controllaMusica("indietro") }
        }

        // ---------- musica ----------
        Regex("^(?:riproduci|fammi sentire|fammi ascoltare|suona|voglio ascoltare|ascoltiamo|metti su)(?: un po' di| della| dei| delle| la| il| lo| i| le| gli| l')? ?(.+?)(?: su ($APP_MUSICALI))?$")
            .find(t)?.let { m -> return fatto(esegui) { musica(c, m.groupValues[1], m.groupValues[2]) } }
        Regex("^metti(?: un po' di| della| la| il)? (musica|una canzone|la canzone|canzoni|l'album|la playlist|un album|una playlist)(?: di| del| dei| degli| delle)? ?(.*?)(?: su ($APP_MUSICALI))?$")
            .find(t)?.let { m -> return fatto(esegui) { musica(c, m.groupValues[2], m.groupValues[3]) } }
        Regex("^metti (.+) su ($APP_MUSICALI)$").find(t)?.let { m -> return fatto(esegui) { musica(c, m.groupValues[1], m.groupValues[2]) } }

        // ---------- chiamate ----------
        Regex("^(?:chiama|telefona a|fai una chiamata a|fai una telefonata a) (.{2,40})$").find(t)?.let { m ->
            var chi = m.groupValues[1]
            if (Regex(" (e|per|dicendo|che|quando|domani|tra|alle)( |$)").containsMatchIn(chi)) return null
            chi = chi.replace(Regex("^(la |il |lo |l'|i |a |mia |mio |al |alla )+"), "").trim()
            if (chi.isEmpty()) return null
            return fatto(esegui) { c.chiama(chi) }
        }

        // ---------- note ----------
        Regex("^(?:prendi nota|annota|scrivi una nota|segna|appunta)(?: che|:|,)? (.{3,})$").find(t)?.let { m ->
            return fatto(esegui) { c.prendiNota(m.groupValues[1].replaceFirstChar { it.titlecase(IT) }) }
        }

        // ---------- cortesie: niente IA per un grazie o un saluto ----------
        if (Regex("^(grazie( mille| tante| jarvis)?|ti ringrazio|perfetto,? grazie|ok,? grazie|va bene,? grazie)$").matches(t)) {
            return listOf("Prego.", "A sua disposizione.", "Di nulla.", "È un piacere.").random()
        }
        if (Regex("^(ci sei|sei lì|sei li|mi senti|sei sveglio|ehi|salve)$").matches(t)) return "Sono qui. Mi dica."
        if (t == "buongiorno" || t == "buonasera") {
            return (if (t == "buongiorno") "Buongiorno" else "Buonasera") + ". Sono le " + SimpleDateFormat("H:mm", IT).format(Date()) + ". Mi dica pure."
        }
        if (Regex("^(ripeti|puoi ripetere|ripetimi|cosa hai detto|come hai detto|non ho capito,? ripeti)$").matches(t)) {
            return c.ultimaRisposta.ifBlank { "Non ho ancora detto niente da ripetere." }
        }

        // ---------- meteo (Open-Meteo, senza IA) ----------
        val giorni = "oggi|domani|dopodomani"
        Regex("^(?:che tempo fa|che tempo farà|com'è il tempo|come sarà il tempo|com'è il meteo|meteo|previsioni(?: del tempo| meteo)?|che tempo c'è)(?: ($giorni))?(?: (?:a|ad|in|per) ([\\p{L}' ]{2,30}?))?(?: ($giorni))?$").find(t)?.let { m ->
            return fatto(esegui) { meteo(c, m.groupValues[2], m.groupValues[1].ifEmpty { m.groupValues[3] }) }
        }
        Regex("^(?:piove|pioverà|nevica|nevicherà|farà brutto|farà bello)(?: ($giorni))?(?: (?:a|ad|in) ([\\p{L}' ]{2,30}?))?(?: ($giorni))?$").find(t)?.let { m ->
            return fatto(esegui) { meteo(c, m.groupValues[2], m.groupValues[1].ifEmpty { m.groupValues[3] }) }
        }
        Regex("^(?:quanti gradi (?:ci sono|fa)|che temperatura (?:c'è|fa)|fa freddo|fa caldo)(?: (?:a|ad|in) ([\\p{L}' ]{2,30}))?(?: fuori)?$").find(t)?.let { m ->
            return fatto(esegui) { meteo(c, m.groupValues[1], "oggi") }
        }

        // ---------- promemoria ----------
        val quando = "oggi|domani|dopodomani|lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica|lunedi|martedi|mercoledi|giovedi|venerdi"
        Regex("^ricordami(?: ($quando))? (?:alle|per le|a) (\\d{1,2})(?:[:.](\\d{2}))?(?: (?:di sera|del pomeriggio))? (?:di |che |da )?(.{3,})$").find(t)?.let { m ->
            return fatto(esegui) { promemoria(c, m.groupValues[4], m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt(), t) }
        }
        Regex("^ricordami (?:di |che |da )?(.{3,}?)(?: ($quando))? (?:alle|per le) (\\d{1,2})(?:[:.](\\d{2}))?(?: (?:di sera|del pomeriggio))?$").find(t)?.let { m ->
            return fatto(esegui) { promemoria(c, m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { "0" }.toInt(), t) }
        }
        Regex("^ricordami tra (\\d+|un|una|due|tre|cinque|dieci|quindici|venti|trenta|mezz'ora|mezzora) ?(minuti|minuto|ore|ora)? (?:di |che |da )?(.{3,})$").find(t)?.let { m ->
            val minuti = when {
                m.groupValues[1].startsWith("mezz") -> 30
                else -> (numero(m.groupValues[1]) ?: return null) * (if (m.groupValues[2].startsWith("or")) 60 else 1)
            }
            val quando2 = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, minuti) }
            return fatto(esegui) { Promemoria.aggiungiOrario(ctx, m.groupValues[3], quando2, "no") }
        }

        // ---------- agenda ----------
        Regex("^(?:cosa|che cosa|che impegni|quali impegni|che appuntamenti|quali appuntamenti) (?:ho|c'è|ci sono)(?: in programma| in agenda| da fare)? ($quando)$").find(t)?.let { m ->
            return fatto(esegui) { c.integrazioni.leggiCalendario(m.groupValues[1], 1) }
        }
        Regex("^(?:ho (?:degli |qualche |altri )?(?:impegni|appuntamenti)|agenda(?: di)?) ($quando)$").find(t)?.let { m ->
            return fatto(esegui) { c.integrazioni.leggiCalendario(m.groupValues[1], 1) }
        }

        // ---------- messaggi e note ----------
        if (Regex("^(leggi(mi)? i (miei )?messaggi|ho (dei |nuovi |dei nuovi )?messaggi|chi mi ha scritto|ci sono (nuovi )?messaggi|leggi(mi)? le notifiche|messaggi nuovi)$").matches(t)) {
            return fatto(esegui) { NotificheJarvis.leggi(ctx, "") }
        }
        Regex("^(?:cosa mi ha scritto|leggi(?:mi)? i messaggi di|cosa ha scritto) ([\\p{L}' ]{2,30})$").find(t)?.let { m ->
            return fatto(esegui) { NotificheJarvis.leggi(ctx, m.groupValues[1].trim()) }
        }
        if (Regex("^(leggi(mi)? le (mie )?note|che note ho|quali note ho|le mie note)$").matches(t)) return fatto(esegui) { c.telefono.leggiNote(5) }

        // ---------- posizione e navigazione ----------
        if (Regex("^(dove sono|dove mi trovo|in che via sono|dove siamo)$").matches(t)) return fatto(esegui) { c.integrazioni.doveSono() }
        Regex("^(?:portami|naviga verso|naviga fino a|guidami|andiamo|indicazioni per|come arrivo) (?:a |ad |al |alla |allo |all'|ai |agli |alle |in |verso |da |dal |dalla )?(.{2,60})$").find(t)?.let { m ->
            return fatto(esegui) { c.integrazioni.naviga(m.groupValues[1].trim(), "") }
        }

        // ---------- notizie (ANSA e Google News, senza IA) ----------
        Regex("^(?:(?:dammi|dimmi|leggi(?:mi)?|che) )?(?:le )?(?:ultime )?notizie(?: di oggi)?(?: (?:di|della|del|dello|sulla|sullo|sul|su) ([\\p{L}' ]{2,30}))?$").find(t)?.let { m ->
            return fatto(esegui) { notizie(c, m.groupValues[1].trim()) }
        }

        // ---------- non disturbare, suoneria, luminosità ----------
        if (Regex("^(attiva|metti|accendi) (il )?non disturbare$").matches(t)) return fatto(esegui) { Sistema.nonDisturbare(ctx, true) }
        if (Regex("^(disattiva|togli|spegni) (il )?non disturbare$").matches(t)) return fatto(esegui) { Sistema.nonDisturbare(ctx, false) }
        Regex("^(?:metti(?: il telefono)? (?:in |il |la )?)?(silenzioso|vibrazione|modalità silenziosa|suoneria normale|togli il silenzioso|attiva la suoneria)$").find(t)?.let { m ->
            val v = m.groupValues[1]
            return fatto(esegui) { Sistema.suoneria(ctx, if (v.contains("togli") || v.contains("normale") || v.contains("attiva")) "normale" else v) }
        }
        Regex("^(?:metti |imposta )?(?:la )?luminosità (?:al |a )?(\\d{1,3}) ?(?:%|per ?cento)?$").find(t)?.let { m -> return fatto(esegui) { Sistema.luminosita(ctx, m.groupValues[1]) } }
        Regex("^(alza|abbassa|aumenta|diminuisci) (?:la )?luminosità$|^luminosità (massima|minima|automatica|al massimo|al minimo)$").find(t)?.let { m ->
            return fatto(esegui) { Sistema.luminosita(ctx, m.groupValues[1].ifEmpty { m.groupValues[2] }.replace("al ", "").replace("aumenta", "alza").replace("diminuisci", "abbassa")) }
        }

        // ---------- apri un'app ----------
        Regex("^(?:apri|avvia|lancia|aprimi)(?: l'app| l'applicazione| app| la app)? ([\\p{L}\\d' .+&-]{2,30})$").find(t)?.let { m ->
            val nome = m.groupValues[1].trim().replace(Regex("^(la |il |lo |l')"), "")
            if (nome.split(" ").size > 3 || Regex(" (e|per|su|con|a) ").containsMatchIn(" $nome ")) return null
            if (!esegui) return "ok"
            val esito = c.apriApp(nome)
            if (!esito.startsWith("Ho aperto ")) return null      // non è un'app: decide il cervello
            return "Apro " + esito.removePrefix("Ho aperto ").substringBefore(". Se") .trimEnd('.') + "."
        }
        return null
    }

    /**
     * Più comandi in una frase ("spegni la torcia, metti la sveglia alle 7 e abbassa il volume"):
     * se li capisco tutti li eseguo senza IA, altrimenti decide il cervello.
     */
    fun provaCatena(c: Cervello, testo: String, tutteObbligatorie: Boolean = false): String? {
        val parti = testo.split(Regex("\\s*(?:,|;| e poi | poi | dopodiché | e )\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parti.size > 6) return null
        if (parti.size < 2 && !tutteObbligatorie) return null
        // prima controllo di capirli tutti (senza eseguire niente), poi li eseguo
        if (parti.any { prova(c, it, esegui = false) == null }) return null
        return parti.joinToString(" ") { prova(c, it) ?: "Non sono riuscito a fare: $it." }
    }

    /** In modalità prova (esegui = false) non fa niente: dice solo che il comando è stato capito. */
    private inline fun fatto(esegui: Boolean, azione: () -> String?): String? = if (esegui) azione() else "ok"

    private fun meteo(c: Cervello, citta: String, giorno: String): String = try {
        c.telefono.meteo(citta.trim(), giorno.ifEmpty { "oggi" })
    } catch (e: Exception) {
        "Senza internet non posso sapere che tempo fa."
    }

    private fun promemoria(c: Cervello, testo: String, giorno: String, ora: Int, minuti: Int, frase: String): String? {
        var h = ora
        if ((frase.contains("di sera") || frase.contains("del pomeriggio")) && h < 12) h += 12
        if (h !in 0..23 || minuti !in 0..59) return null
        val data = c.integrazioni.interpretaData(giorno.ifEmpty { "oggi" }) ?: return null
        data.set(java.util.Calendar.HOUR_OF_DAY, h)
        data.set(java.util.Calendar.MINUTE, minuti)
        // "alle 8" senza giorno, ma le 8 sono già passate: intende domani (o stasera, se è mattina e dice un'ora piccola)
        if (giorno.isEmpty() && data.timeInMillis <= System.currentTimeMillis()) {
            if (h < 12 && java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < h + 12) data.add(java.util.Calendar.HOUR_OF_DAY, 12)
            else data.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return Promemoria.aggiungiOrario(c.contesto(), testo.trim(), data, "no")
    }

    private val CATEGORIE = setOf("principali", "cronaca", "politica", "mondo", "economia", "sport", "tecnologia")

    private fun notizie(c: Cervello, argomento: String): String {
        val cat = CATEGORIE.firstOrNull { argomento.contains(it) }
        val grezzo = c.integrazioni.notizie(cat ?: "principali", if (cat == null) argomento else "", 5)
        val titoli = grezzo.lines().mapNotNull { Regex("^\\d+\\) (.+)$").find(it)?.groupValues?.get(1) }
            .map { it.substringBefore(". ").trim().trimEnd('.') }
        if (titoli.isEmpty()) return grezzo
        val fonte = if (grezzo.startsWith("Fonte: ANSA")) "dall'ANSA" else "da Google News"
        val ordinali = listOf("Primo", "Secondo", "Terzo", "Quarto", "Quinto")
        return "Ecco le ultime notizie $fonte. " + titoli.take(5).mapIndexed { i, x -> "${ordinali[i]}: $x." }.joinToString(" ")
    }

    private fun musica(c: Cervello, cosa: String, app: String): String {
        val pulito = cosa.trim().removePrefix("musica").trim().removePrefix("di ").trim()
        val generico = pulito.isEmpty() || pulito in setOf("musica", "qualcosa", "un po' di musica", "la musica", "canzoni")
        return c.riproduci(if (generico) "" else pulito, app)
    }
}
