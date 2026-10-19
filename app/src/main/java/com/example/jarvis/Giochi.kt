package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import kotlin.random.Random

/**
 * Giochi a voce: impiccato, indovinelli e quiz funzionano anche offline;
 * venti domande e storie a bivi usano il cervello (internet).
 * Mentre un gioco è in corso, quello che dici va al gioco; i comandi veri
 * ("accendi la torcia", "che ore sono") passano comunque.
 */
object Giochi {

    val ELENCO = listOf(
        "quiz" to "Quiz",
        "indovinello" to "Indovinelli",
        "venti" to "20 domande",
        "impiccato" to "Impiccato",
        "storia" to "Storie a bivi"
    )

    /** Frase da dire per avviare ogni gioco (usata anche dai pulsanti della pagina GIOCHI). */
    val FRASI = mapOf(
        "quiz" to "facciamo un quiz",
        "indovinello" to "fammi un indovinello",
        "venti" to "giochiamo a venti domande",
        "impiccato" to "giochiamo all'impiccato",
        "storia" to "raccontami una storia a bivi"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun attivo(ctx: Context): JSONObject? = try {
        prefs(ctx).getString("gioco", null)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    private fun salva(ctx: Context, g: JSONObject?) {
        if (g == null) prefs(ctx).edit().remove("gioco").commit()
        else prefs(ctx).edit().putString("gioco", g.toString()).commit()
    }

    fun termina(ctx: Context) = salva(ctx, null)

    /** Riga breve per la schermata: "Impiccato · P A _ _ A". */
    fun inCorso(ctx: Context): String? {
        val g = attivo(ctx) ?: return null
        return when (g.optString("tipo")) {
            "impiccato" -> "Impiccato · ${caselle(g)} · errori ${g.optInt("errori")} di $ERRORI_MAX"
            "indovinello" -> "Indovinello in corso"
            "quiz" -> "Quiz · domanda ${g.optInt("n") + 1} di ${g.optJSONArray("domande")?.length() ?: 5}"
            "venti" -> "20 domande · ${g.optInt("n")} fatte"
            "storia" -> "Storia a bivi · capitolo ${g.optInt("passo") + 1}"
            else -> null
        }
    }

    // ================================================================
    //  INGRESSO
    // ================================================================

    fun norm(testo: String): String = Normalizer.normalize(testo.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("’", "'")
        .replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val USCITA = Regex(
        "^(basta|stop|fine|esci|smettila|basta cosi)( (giocare|gioco|di giocare|con il gioco|col gioco))?$|" +
            "basta giocare|fine del gioco|esci dal gioco|smetti(amo)? di giocare|non voglio piu giocare|chiudi il gioco|basta con (il|questo) gioco|finiamo il gioco"
    )

    private val SI = Regex("^(si|certo|ok|okay|va bene|vai|dai|ancora|un altra|un'altra|un altro|un'altro|si dai|volentieri|perche no|esatto|giusto|pronto|fatto|si grazie)( .*)?$")
    private val NO = Regex("^(no|nah|basta|no grazie|non ora|dopo|un'altra volta)( .*)?$")

    /**
     * Prova a gestire [originale] come parte di un gioco.
     * @return la risposta di Jarvis, oppure null se non c'entra con i giochi.
     */
    fun prova(c: Cervello, ctx: Context, originale: String): String? {
        val t = norm(originale)
        if (t.isEmpty()) return null
        val g = attivo(ctx)
        if (g == null) return avvia(c, ctx, t)

        if (USCITA.containsMatchIn(t)) {
            val soluzione = when (g.optString("tipo")) {
                "impiccato" -> " La parola era ${g.optString("parola")}."
                "indovinello" -> " La risposta era: ${INDOVINELLI[g.optInt("i")].risposte[0]}."
                else -> ""
            }
            termina(ctx)
            return "Va bene, fine del gioco.$soluzione Quando vuoi, giochiamo ancora."
        }
        if (t == "ripeti" || t == "ripeti la domanda" || t == "puoi ripetere" || t == "come") {
            val ultima = g.optString("ultima")
            if (ultima.isNotEmpty()) return ultima
        }
        // i comandi veri passano anche durante un gioco
        if (t.split(" ").size >= 2 && g.optString("tipo") !in listOf("scelta", "ancora")) {
            val comando = try { ComandiRapidi.prova(c, originale, esegui = false) } catch (e: Exception) { null }
            if (comando != null) return null
        }
        val r = when (g.optString("tipo")) {
            "scelta" -> {
                val scelto = giocoNominato(t)
                termina(ctx)
                if (scelto == null) return null
                avviaGioco(c, ctx, scelto, t)
            }
            "ancora" -> {
                termina(ctx)
                when {
                    SI.matches(t) -> avviaGioco(c, ctx, g.optString("gioco"), "")
                    NO.matches(t) -> "Va bene. Quando vuoi giocare, chiamami."
                    else -> return avvia(c, ctx, t)
                }
            }
            "impiccato" -> impiccato(ctx, g, t)
            "indovinello" -> indovinello(ctx, g, t)
            "quiz" -> quiz(ctx, g, t)
            "venti" -> venti(c, ctx, g, t)
            "storia" -> storia(c, ctx, g, t)
            else -> { termina(ctx); return null }
        }
        ricordaUltima(ctx, r)
        return r
    }

    private fun ricordaUltima(ctx: Context, r: String) {
        val g = attivo(ctx) ?: return
        g.put("ultima", r)
        salva(ctx, g)
    }

    private fun giocoNominato(t: String): String? = when {
        t.contains("impiccato") -> "impiccato"
        t.contains("indovinell") -> "indovinello"
        t.contains("quiz") -> "quiz"
        Regex("\\b(20|venti) domande|^(20|venti)$").containsMatchIn(t) -> "venti"
        t.contains("storia") || t.contains("bivi") || t.contains("avventura") -> "storia"
        else -> null
    }

    private fun avvia(c: Cervello, ctx: Context, t: String): String? {
        val parole = t.split(" ").size
        val vuole = Regex("\\b(gioc|facciamo|fammi|fai|dimmi|proponi|iniziamo|inizia|voglio|vorrei|un altro|un'altro|un altra|un'altra|partita|raccont|ancora|nuovo|nuova)").containsMatchIn(t)
        val gioco = when {
            t.contains("impiccato") && (vuole || parole <= 2) -> "impiccato"
            t.contains("indovinell") && (vuole || parole <= 2) -> "indovinello"
            Regex("\\b(20|venti) domande").containsMatchIn(t) && (vuole || parole <= 3) -> "venti"
            t.contains("quiz") && (vuole || parole <= 2) -> "quiz"
            Regex("storia (a bivi|interattiva)|avventura interattiva|storie a bivi").containsMatchIn(t) -> "storia"
            Regex("^(giochiamo|facciamo un gioco|giochiamo a qualcosa|ho voglia di giocare|voglio giocare|che giochi (conosci|sai fare|hai)|a cosa (possiamo giocare|giochiamo))$").matches(t) -> "scelta"
            else -> return null
        }
        val r = avviaGioco(c, ctx, gioco, t)
        ricordaUltima(ctx, r)
        return r
    }

    fun avviaGioco(c: Cervello, ctx: Context, gioco: String, t: String): String {
        if (gioco == "scelta") {
            salva(ctx, JSONObject().put("tipo", "scelta"))
            return "Posso proporti un quiz, un indovinello, venti domande, l'impiccato oppure una storia a bivi. A cosa giochiamo?"
        }
        val online = Orecchie.online(ctx) && c.chiaveAttiva().isNotEmpty()
        if ((gioco == "venti" || gioco == "storia") && !online) {
            return "Per ${if (gioco == "venti") "le venti domande" else "le storie a bivi"} mi serve internet. " +
                "Intanto possiamo fare l'impiccato, un indovinello o un quiz, che funzionano anche offline."
        }
        Trofei.giocoIniziato(ctx, gioco)
        return when (gioco) {
            "impiccato" -> avviaImpiccato(ctx, t)
            "indovinello" -> avviaIndovinello(ctx, -1)
            "quiz" -> avviaQuiz(c, ctx, t, online)
            "venti" -> avviaVenti(ctx)
            else -> avviaStoria(c, ctx, t)
        }
    }

    /** Alla fine di una partita: "Un'altra?" */
    private fun chiediAncora(ctx: Context, gioco: String) {
        salva(ctx, JSONObject().put("tipo", "ancora").put("gioco", gioco))
    }

    // ================================================================
    //  IMPICCATO
    // ================================================================

    const val ERRORI_MAX = 6

    private val PAROLE_FACILI = listOf(
        "CASA", "MARE", "SOLE", "LUNA", "GATTO", "CANE", "PANE", "LIBRO", "SEDIA", "FIORE", "TRENO", "PORTA",
        "NEVE", "STELLA", "PIZZA", "MELA", "BARCA", "TOPO", "LUPO", "NUVOLA", "ZAINO", "TORTA", "PESCE", "FOGLIA"
    )
    private val PAROLE_MEDIE = listOf(
        "GIARDINO", "FINESTRA", "CHITARRA", "MONTAGNA", "FARFALLA", "CASTELLO", "CUCINA", "TELEFONO", "OMBRELLO",
        "PALLONE", "CANDELA", "ORSETTO", "SCUOLA", "FORMAGGIO", "SPIAGGIA", "LAMPADA", "ZUCCHERO", "CAVALLO",
        "DIVANO", "CAMPANA", "FRAGOLA", "CONIGLIO", "BALENA", "DINOSAURO", "ASTRONAVE", "PIOGGIA", "TAVOLO", "ALBERO"
    )
    private val PAROLE_DIFFICILI = listOf(
        "ARCOBALENO", "FRIGORIFERO", "LAVATRICE", "ELICOTTERO", "COCCODRILLO", "APPARTAMENTO", "SCRIVANIA",
        "TEMPORALE", "PIPISTRELLO", "CALEIDOSCOPIO", "LABIRINTO", "MONGOLFIERA", "CAVATAPPI", "ORCHESTRA",
        "TELESCOPIO", "VULCANO", "PINGUINO", "ARCIPELAGO", "MERIDIANA", "FENICOTTERO", "PAPPAGALLO", "BICICLETTA"
    )

    private val NOMI_LETTERE = mapOf(
        "a" to 'A', "bi" to 'B', "ci" to 'C', "di" to 'D', "e" to 'E', "effe" to 'F', "gi" to 'G', "acca" to 'H',
        "i" to 'I', "i lunga" to 'J', "cappa" to 'K', "elle" to 'L', "emme" to 'M', "enne" to 'N', "o" to 'O',
        "pi" to 'P', "cu" to 'Q', "erre" to 'R', "esse" to 'S', "ti" to 'T', "u" to 'U', "vu" to 'V', "vi" to 'V',
        "doppia vu" to 'W', "doppia v" to 'W', "ics" to 'X', "ipsilon" to 'Y', "zeta" to 'Z', "zed" to 'Z'
    )

    private fun caselle(g: JSONObject): String {
        val dette = g.optString("dette")
        return g.optString("parola").map { if (it in dette) "$it" else "_" }.joinToString(" ")
    }

    fun avviaImpiccato(ctx: Context, t: String, parolaFissa: String? = null): String {
        val elenco = when {
            t.contains("facile") -> PAROLE_FACILI
            t.contains("difficil") -> PAROLE_DIFFICILI
            else -> PAROLE_MEDIE
        }
        val parola = parolaFissa ?: elenco[Random.nextInt(elenco.size)]
        val g = JSONObject().put("tipo", "impiccato").put("parola", parola).put("dette", "").put("errori", 0)
        salva(ctx, g)
        return "Giochiamo all'impiccato! Ho pensato a una parola di ${parola.length} lettere: ${caselle(g)}. " +
            "Dimmi una lettera alla volta, oppure prova a indovinare la parola. Puoi sbagliare $ERRORI_MAX volte."
    }

    /** La lettera detta: "a", "la erre", "provo con la R", "R come Roma". */
    fun estraiLettera(t: String): Char? {
        var s = t.replace(Regex("^(provo (con )?|dico |c'e |ce |c e |e |ok |allora )"), "")
            .replace(Regex("^(la |una )?(lettera )?"), "")
            .replace(Regex(" come \\w+$"), "")
            .trim()
        if (s.length == 1 && s[0] in 'a'..'z') return s[0].uppercaseChar()
        NOMI_LETTERE[s]?.let { return it }
        s = s.removePrefix("l'").trim()
        if (s.length == 1 && s[0] in 'a'..'z') return s[0].uppercaseChar()
        return NOMI_LETTERE[s]
    }

    private fun impiccato(ctx: Context, g: JSONObject, t: String): String {
        val parola = g.optString("parola")
        var dette = g.optString("dette")
        var errori = g.optInt("errori")

        // tentativo con la parola intera
        val tentativo = t.replace(Regex("^(la parola e |e |secondo me e |forse |dico |provo |penso sia |credo sia )"), "").replace(" ", "")
        if (tentativo.length == parola.length && tentativo.uppercase() == parola) {
            return vittoriaImpiccato(ctx, g)
        }
        val lettera = estraiLettera(t)
        if (lettera == null) {
            if (tentativo.length == parola.length && tentativo.all { it in 'a'..'z' }) {
                errori++
                g.put("errori", errori)
                if (errori >= ERRORI_MAX) return sconfittaImpiccato(ctx, parola)
                salva(ctx, g)
                return "No, non è ${tentativo.uppercase()}. ${caselle(g)}. Errori: $errori di $ERRORI_MAX."
            }
            return "Dimmi una lettera, per esempio «la R», oppure la parola intera. Ora sei a: ${caselle(g)}."
        }
        if (lettera in dette) return "La $lettera l'hai già detta. ${caselle(g)}."
        dette += lettera
        g.put("dette", dette)
        if (lettera in parola) {
            if (parola.all { it in dette }) return vittoriaImpiccato(ctx, g)
            salva(ctx, g)
            val quante = parola.count { it == lettera }
            return "Sì, ${if (quante > 1) "ci sono $quante $lettera" else "la $lettera c'è"}! ${caselle(g)}."
        }
        errori++
        g.put("errori", errori)
        if (errori >= ERRORI_MAX) return sconfittaImpiccato(ctx, parola)
        salva(ctx, g)
        return "No, la $lettera non c'è. ${caselle(g)}. Errori: $errori di $ERRORI_MAX."
    }

    private fun vittoriaImpiccato(ctx: Context, g: JSONObject): String {
        val errori = g.optInt("errori")
        chiediAncora(ctx, "impiccato")
        Suoni.suona(ctx, "vittoria")
        Trofei.evento(ctx, "vittoria_impiccato", 30)
        if (errori == 0) Trofei.evento(ctx, "impiccato_perfetto", 20)
        return "Bravissimo! La parola era ${g.optString("parola")}" +
            (if (errori == 0) ", e senza nemmeno un errore!" else ", con $errori errori.") + " Un'altra partita?"
    }

    private fun sconfittaImpiccato(ctx: Context, parola: String): String {
        chiediAncora(ctx, "impiccato")
        Suoni.suona(ctx, "errore")
        Trofei.evento(ctx, "impiccato_perso", 5)
        return "Impiccato! La parola era $parola. Vuoi la rivincita?"
    }

    // ================================================================
    //  INDOVINELLI
    // ================================================================

    class Indovinello(val domanda: String, val risposte: List<String>, val indizio: String)

    val INDOVINELLI = listOf(
        Indovinello("Ha i denti ma non morde. Cos'è?", listOf("pettine"), "Lo usi per i capelli."),
        Indovinello("Più è grande, meno si vede. Cos'è?", listOf("buio", "oscurita"), "Arriva quando spegni la luce."),
        Indovinello("Cammina senza gambe e, quando passa, non torna più. Cos'è?", listOf("tempo"), "Lo misurano gli orologi."),
        Indovinello("Ha un occhio solo ma non vede. Cos'è?", listOf("ago"), "Serve per cucire."),
        Indovinello("Più ne togli, più diventa grande. Cos'è?", listOf("buca", "buco"), "Si scava nella terra."),
        Indovinello("Ha i tasti ma non apre nessuna porta, e fa musica. Cos'è?", listOf("pianoforte", "piano", "tastiera"), "Lo suonava Mozart."),
        Indovinello("Si rompe appena lo nomini. Cos'è?", listOf("silenzio"), "C'è quando nessuno parla."),
        Indovinello("Ha una testa e una croce, ma non ha corpo. Cos'è?", listOf("moneta", "euro", "monetina"), "La lanci per decidere."),
        Indovinello("Corre ma non ha piedi, ha un letto ma non dorme. Cos'è?", listOf("fiume", "torrente"), "Finisce nel mare."),
        Indovinello("Più si asciuga, più si bagna. Cos'è?", listOf("asciugamano", "asciugamani", "telo"), "Lo usi dopo la doccia."),
        Indovinello("Ha le foglie ma non è un albero, ha la copertina ma non ha freddo. Cos'è?", listOf("libro", "quaderno"), "Ha anche le pagine."),
        Indovinello("Ha il collo ma non ha la testa. Cos'è?", listOf("bottiglia", "fiasco"), "Contiene acqua o vino."),
        Indovinello("Ti segue col sole e sparisce al buio. Cos'è?", listOf("ombra"), "È scura e ha la tua forma."),
        Indovinello("Sono pieno di buchi ma tengo l'acqua. Cosa sono?", listOf("spugna"), "La usi per lavare i piatti."),
        Indovinello("Ha quattro gambe ma non cammina. Cos'è?", listOf("tavolo", "sedia", "tavola", "sgabello"), "Ci mangi sopra."),
        Indovinello("Vola senza ali e piange senza occhi. Cos'è?", listOf("nuvola", "nube", "nuvole"), "Porta la pioggia."),
        Indovinello("Puoi prenderlo ma non lanciarlo. Cos'è?", listOf("raffreddore", "influenza"), "Ti fa starnutire."),
        Indovinello("Quale mese ha ventotto giorni?", listOf("tutti", "ognuno", "ogni mese"), "Pensa bene: non solo febbraio."),
        Indovinello("Ha le pale ma non scava, gira con il vento. Cos'è?", listOf("mulino", "pala eolica", "girandola"), "Una volta macinava il grano."),
        Indovinello("Più gli dai da mangiare, più cresce, ma se gli dai da bere muore. Cos'è?", listOf("fuoco", "fiamma", "falo"), "Scalda e brucia."),
        Indovinello("Ha le lancette ma non sa applaudire. Cos'è?", listOf("orologio", "sveglia"), "Segna le ore."),
        Indovinello("Si apre quando piove e si chiude col sole. Cos'è?", listOf("ombrello"), "Ti ripara dalla pioggia."),
        Indovinello("Ha le corna ma non è una mucca, e porta la casa con sé. Cos'è?", listOf("lumaca", "chiocciola"), "È molto lenta."),
        Indovinello("È tuo, ma gli altri lo usano più di te. Cos'è?", listOf("nome"), "Te l'hanno dato i genitori."),
        Indovinello("Bianca dentro, verde fuori: se vuoi che te lo dica, aspetta. Cos'è?", listOf("pera"), "È un frutto: rileggi bene l'ultima parola."),
        Indovinello("Più lo usi, più diventa piccolo, e fa la schiuma. Cos'è?", listOf("sapone", "saponetta"), "Ti lavi le mani con lui."),
        Indovinello("Cade dal cielo, è bianca e non si fa male. Cos'è?", listOf("neve", "fiocco di neve"), "Arriva d'inverno."),
        Indovinello("Ha un solo piede e un cappello, ma non è un uomo. Cos'è?", listOf("fungo", "funghi"), "Si trova nel bosco."),
        Indovinello("Ha tanti occhi ma non vede niente. Cos'è?", listOf("patata", "patate"), "Si può fare fritta."),
        Indovinello("La butti in acqua quando ti serve e la tiri su quando non ti serve più. Cos'è?", listOf("ancora"), "La usano le navi."),
        Indovinello("Gira tutto il mondo restando sempre in un angolo. Cos'è?", listOf("francobollo"), "Si attacca sulle lettere."),
        Indovinello("Più invecchia, più vale, e riposa nella botte. Cos'è?", listOf("vino"), "Si fa con l'uva."),
        Indovinello("Dorme di giorno, vola di notte, ha le ali ma non è un uccello. Cos'è?", listOf("pipistrello"), "Vive nelle grotte."),
        Indovinello("Ha due ruote, non ha motore e la spingi con i piedi. Cos'è?", listOf("bicicletta", "bici", "monopattino"), "Ha i pedali."),
        Indovinello("Ha le righe ma non è un quaderno, e vive in Africa. Cos'è?", listOf("zebra"), "Sembra un cavallo a strisce."),
        Indovinello("Scende sempre e non sale mai. Cos'è?", listOf("pioggia", "acqua"), "Bagna le strade."),
        Indovinello("Più è caldo, più è fresco. Cos'è?", listOf("pane"), "Lo compri dal fornaio."),
        Indovinello("Ha le chiavi, ha lo schermo e ci scrivi sopra. Cos'è?", listOf("computer", "pc", "portatile", "tastiera"), "Lo usi anche per lavorare.")
    )

    /** L'indovinello del giorno (blocco della home). */
    fun indiceDelGiorno() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % INDOVINELLI.size

    fun avviaIndovinello(ctx: Context, indice: Int): String {
        val i = if (indice in INDOVINELLI.indices) indice else {
            val fatti = prefs(ctx).getString("indovinelli_fatti", "") ?: ""
            val recenti = fatti.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            val liberi = INDOVINELLI.indices.filter { it !in recenti }.ifEmpty { INDOVINELLI.indices.toList() }
            liberi[Random.nextInt(liberi.size)]
        }
        val fatti = ((prefs(ctx).getString("indovinelli_fatti", "") ?: "").split(",").filter { it.isNotEmpty() } + "$i").takeLast(15)
        prefs(ctx).edit().putString("indovinelli_fatti", fatti.joinToString(",")).apply()
        salva(ctx, JSONObject().put("tipo", "indovinello").put("i", i).put("tentativi", 0))
        return "Ecco l'indovinello: ${INDOVINELLI[i].domanda}"
    }

    private fun indovinello(ctx: Context, g: JSONObject, t: String): String {
        val ind = INDOVINELLI[g.optInt("i").coerceIn(0, INDOVINELLI.size - 1)]
        if (Regex("aiut|indizio|suggeriment|aiutino").containsMatchIn(t)) {
            return "Indizio: ${ind.indizio}"
        }
        if (Regex("non (lo )?so|mi arrendo|dimmi la (risposta|soluzione)|^soluzione$|qual e la (risposta|soluzione)|arrendo").containsMatchIn(t)) {
            chiediAncora(ctx, "indovinello")
            return "La risposta era: ${ind.risposte[0]}. Ne vuoi un altro?"
        }
        val parole = t.split(" ").toSet()
        val giusta = ind.risposte.any { r -> if (r.contains(" ")) t.contains(r) else r in parole || parole.any { it.removeSuffix("i") == r.removeSuffix("o") && it.length > 3 } }
        if (giusta) {
            chiediAncora(ctx, "indovinello")
            Suoni.suona(ctx, "vittoria")
            Trofei.evento(ctx, "indovinello_risolto", 10)
            return "Esatto, ${ind.risposte[0]}! Ne vuoi un altro?"
        }
        val tentativi = g.optInt("tentativi") + 1
        g.put("tentativi", tentativi)
        salva(ctx, g)
        return when {
            tentativi >= 4 -> {
                chiediAncora(ctx, "indovinello")
                "Niente da fare: era ${ind.risposte[0]}. Ne vuoi un altro?"
            }
            tentativi == 2 -> "No. Ti do un indizio: ${ind.indizio}"
            else -> "No, riprova! Se vuoi, chiedimi un indizio."
        }
    }

    // ================================================================
    //  QUIZ
    // ================================================================

    private val QUIZ_LOCALE = listOf(
        Triple("Qual è la capitale dell'Australia?", listOf("Sydney", "Canberra", "Melbourne"), 1),
        Triple("Quanti sono i pianeti del sistema solare?", listOf("Otto", "Nove", "Sette"), 0),
        Triple("Chi ha dipinto la Gioconda?", listOf("Michelangelo", "Raffaello", "Leonardo da Vinci"), 2),
        Triple("Qual è il fiume più lungo d'Italia?", listOf("Po", "Tevere", "Adige"), 0),
        Triple("In che anno Cristoforo Colombo arrivò in America?", listOf("1492", "1498", "1453"), 0),
        Triple("Qual è il simbolo chimico dell'oro?", listOf("Or", "Au", "Ag"), 1),
        Triple("Quante zampe ha un ragno?", listOf("Sei", "Otto", "Dieci"), 1),
        Triple("Qual è l'oceano più grande?", listOf("Atlantico", "Indiano", "Pacifico"), 2),
        Triple("Chi ha scritto la Divina Commedia?", listOf("Petrarca", "Dante Alighieri", "Boccaccio"), 1),
        Triple("Qual è la montagna più alta delle Alpi?", listOf("Monte Rosa", "Cervino", "Monte Bianco"), 2),
        Triple("Quanti giorni ha un anno bisestile?", listOf("365", "366", "364"), 1),
        Triple("Qual è il pianeta più vicino al Sole?", listOf("Venere", "Mercurio", "Marte"), 1),
        Triple("Qual è l'animale più grande del mondo?", listOf("Elefante africano", "Balenottera azzurra", "Squalo balena"), 1),
        Triple("Che colore si ottiene mescolando blu e giallo?", listOf("Verde", "Viola", "Arancione"), 0),
        Triple("Qual è la capitale del Canada?", listOf("Toronto", "Ottawa", "Montréal"), 1),
        Triple("Quanti lati ha un esagono?", listOf("Cinque", "Sei", "Otto"), 1),
        Triple("Chi ha composto Le quattro stagioni?", listOf("Vivaldi", "Verdi", "Puccini"), 0),
        Triple("In quale città si trova il Colosseo?", listOf("Roma", "Verona", "Napoli"), 0),
        Triple("Qual è il gas più abbondante nell'aria?", listOf("Ossigeno", "Azoto", "Anidride carbonica"), 1),
        Triple("Quante sono le regioni italiane?", listOf("Venti", "Diciotto", "Ventidue"), 0),
        Triple("Chi ha formulato la teoria della relatività?", listOf("Newton", "Einstein", "Galileo"), 1),
        Triple("Qual è lo stato più piccolo del mondo?", listOf("Monaco", "San Marino", "Città del Vaticano"), 2),
        Triple("Quale gas assorbono le piante dall'aria per crescere?", listOf("Ossigeno", "Anidride carbonica", "Elio"), 1),
        Triple("Quanti minuti ci sono in un'ora e mezza?", listOf("90", "80", "100"), 0),
        Triple("Qual è l'isola più grande del Mediterraneo?", listOf("Sardegna", "Sicilia", "Cipro"), 1),
        Triple("In quale continente si trova l'Egitto?", listOf("Asia", "Africa", "Europa"), 1),
        Triple("Chi ha scritto I promessi sposi?", listOf("Alessandro Manzoni", "Giovanni Verga", "Italo Calvino"), 0),
        Triple("Qual è l'osso più lungo del corpo umano?", listOf("Femore", "Tibia", "Omero"), 0),
        Triple("Quante corde ha una chitarra classica?", listOf("Quattro", "Sei", "Dodici"), 1),
        Triple("Qual è la capitale della Spagna?", listOf("Barcellona", "Madrid", "Siviglia"), 1),
        Triple("A quanti gradi bolle l'acqua al livello del mare?", listOf("90", "100", "120"), 1),
        Triple("Quale di questi mammiferi sa volare davvero?", listOf("Pipistrello", "Scoiattolo", "Lontra"), 0),
        Triple("Quanti colori ha l'arcobaleno, tradizionalmente?", listOf("Cinque", "Sette", "Nove"), 1),
        Triple("Quale strumento misura la pressione dell'aria?", listOf("Termometro", "Barometro", "Igrometro"), 1),
        Triple("Qual è il deserto caldo più grande del mondo?", listOf("Gobi", "Sahara", "Kalahari"), 1),
        Triple("Qual è la capitale del Giappone?", listOf("Pechino", "Seul", "Tokyo"), 2)
    )

    private fun domandeLocali(): JSONArray {
        val a = JSONArray()
        for (q in QUIZ_LOCALE.shuffled().take(5)) {
            a.put(JSONObject().put("d", q.first).put("o", JSONArray(q.second)).put("r", q.third))
        }
        return a
    }

    private fun domandeDalCervello(c: Cervello, argomento: String): JSONArray? = try {
        val richiesta = "Crea 5 domande di quiz in italiano " +
            (if (argomento.isBlank()) "di cultura generale, su argomenti vari" else "sull'argomento: $argomento") +
            ". Difficoltà media, fatti certi e verificabili. Ogni domanda ha 3 risposte possibili e una sola giusta. " +
            "Rispondi SOLO con un array JSON, senza altro testo: [{\"d\":\"domanda\",\"o\":[\"risposta 1\",\"risposta 2\",\"risposta 3\"],\"r\":indice_della_giusta_da_0_a_2}]"
        val testo = c.chiamaSemplice(richiesta, 1200)
        val inizio = testo.indexOf('[')
        val fine = testo.lastIndexOf(']')
        val a = JSONArray(testo.substring(inizio, fine + 1))
        val buone = JSONArray()
        for (i in 0 until a.length()) {
            val q = a.optJSONObject(i) ?: continue
            val o = q.optJSONArray("o") ?: continue
            if (q.optString("d").isNotBlank() && o.length() == 3 && q.optInt("r", -1) in 0..2) buone.put(q)
        }
        if (buone.length() >= 3) buone else null
    } catch (e: Exception) {
        null
    }

    private fun testoDomanda(g: JSONObject): String {
        val domande = g.optJSONArray("domande") ?: JSONArray()
        val n = g.optInt("n")
        val q = domande.optJSONObject(n) ?: return ""
        val o = q.optJSONArray("o") ?: JSONArray()
        return "Domanda ${n + 1} di ${domande.length()}: ${q.optString("d")} " +
            "A: ${o.optString(0)}. B: ${o.optString(1)}. C: ${o.optString(2)}."
    }

    fun avviaQuiz(c: Cervello, ctx: Context, t: String, online: Boolean): String {
        val argomento = Regex("quiz (?:di |sul |sulla |sullo |sui |sulle |sugli |su |sull'|dell'|della |del )(.+)$").find(t)?.groupValues?.get(1)
            ?.replace(Regex("^(la |il |lo |i |gli |le |l')"), "")?.trim() ?: ""
        var domande = if (online) domandeDalCervello(c, argomento) else null
        val avviso = if (domande == null && argomento.isNotBlank()) "Senza il cervello non posso fare domande su $argomento: uso le mie. " else ""
        if (domande == null) domande = domandeLocali()
        val g = JSONObject().put("tipo", "quiz").put("domande", domande).put("n", 0).put("giuste", 0)
        salva(ctx, g)
        return "${avviso}Iniziamo il quiz! Rispondi A, B o C. ${testoDomanda(g)}"
    }

    /** "a", "la b", "risposta c", "la seconda", "tre", oppure il testo della risposta. */
    fun indiceRisposta(t: String, opzioni: List<String>): Int? {
        val s = t.replace(Regex("^(la |risposta |rispondo |dico |e |direi |secondo me |allora )+"), "").trim()
        when (s) {
            "a", "1", "uno", "prima", "la prima", "a come ancona" -> return 0
            "b", "bi", "2", "due", "seconda", "b come bologna" -> return 1
            "c", "ci", "3", "tre", "terza", "c come como" -> return 2
        }
        Regex("^(?:la )?(a|b|c)\\b").find(s)?.let { return "abc".indexOf(it.groupValues[1]) }
        opzioni.forEachIndexed { i, o -> val n = norm(o); if (n.isNotEmpty() && (s == n || s.contains(n))) return i }
        return null
    }

    private fun quiz(ctx: Context, g: JSONObject, t: String): String {
        val domande = g.optJSONArray("domande") ?: return run { termina(ctx); "Il quiz si è interrotto." }
        val n = g.optInt("n")
        val q = domande.optJSONObject(n) ?: return run { termina(ctx); "Il quiz è finito." }
        val o = q.optJSONArray("o") ?: JSONArray()
        val opzioni = (0 until o.length()).map { o.optString(it) }
        val scelta = indiceRisposta(t, opzioni) ?: return "Dimmi A, B oppure C. ${testoDomanda(g)}"
        val giusta = q.optInt("r")
        var giuste = g.optInt("giuste")
        val esito = if (scelta == giusta) {
            giuste++
            Suoni.suona(ctx, "vittoria")
            "Giusto!"
        } else {
            Suoni.suona(ctx, "errore")
            "No, era la ${"ABC"[giusta]}: ${opzioni.getOrElse(giusta) { "" }}."
        }
        g.put("giuste", giuste).put("n", n + 1)
        if (n + 1 >= domande.length()) {
            chiediAncora(ctx, "quiz")
            Trofei.evento(ctx, "quiz_finito", 10 + 5 * giuste)
            if (giuste == domande.length() && giuste >= 5) Trofei.evento(ctx, "quiz_perfetto", 30)
            val giudizio = when {
                giuste == domande.length() -> "Perfetto, tutte giuste!"
                giuste * 2 >= domande.length() -> "Bel risultato."
                else -> "La prossima andrà meglio."
            }
            return "$esito Quiz finito: $giuste su ${domande.length()}. $giudizio Un altro quiz?"
        }
        salva(ctx, g)
        return "$esito ${testoDomanda(g)}"
    }

    // ================================================================
    //  VENTI DOMANDE: Jarvis indovina a cosa pensi
    // ================================================================

    private fun avviaVenti(ctx: Context): String {
        salva(ctx, JSONObject().put("tipo", "venti").put("n", 0).put("storia", JSONArray()).put("attesa", "pronto"))
        return "Pensa a qualcosa: una persona famosa, un animale o un oggetto. Io ti farò domande a cui rispondere sì o no. " +
            "Quando sei pronto, dimmi «pronto»."
    }

    private fun prossimaDomanda(c: Cervello, g: JSONObject): Pair<String, Boolean>? = try {
        val storia = g.optJSONArray("storia") ?: JSONArray()
        val righe = (0 until storia.length()).joinToString("\n") { storia.optString(it) }
        val n = g.optInt("n")
        val richiesta = "Stai giocando a venti domande in italiano. L'utente pensa a una cosa (persona, animale o oggetto) " +
            "e tu devi indovinarla facendo domande a cui si risponde sì o no. Domande e risposte finora:\n" +
            (if (righe.isBlank()) "(nessuna)" else righe) +
            "\nHai già fatto $n domande su 20. Scrivi SOLO la prossima domanda, breve. " +
            "Quando pensi di sapere la risposta (o alla ventesima domanda), scrivi «IPOTESI: È ...?»."
        val r = c.chiamaSemplice(richiesta, 120).trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (r.isBlank()) null
        else if (r.uppercase().startsWith("IPOTESI")) Pair(r.substringAfter(":").trim(), true)
        else Pair(r, false)
    } catch (e: Exception) {
        null
    }

    private fun venti(c: Cervello, ctx: Context, g: JSONObject, t: String): String {
        when (g.optString("attesa")) {
            "pronto" -> {
                if (!SI.matches(t) && !t.contains("pronto")) return "Dimmi «pronto» quando hai pensato a qualcosa."
            }
            "rivela" -> {
                chiediAncora(ctx, "venti")
                Trofei.evento(ctx, "venti_vinte", 40)
                return "Ah, ecco! ${originaleBreve(t)}: non ci sarei mai arrivato. Mi hai battuto. Un'altra partita?"
            }
            else -> {
                val storia = g.optJSONArray("storia") ?: JSONArray()
                storia.put("D: ${g.optString("domanda")} R: $t")
                g.put("storia", storia)
                if (g.optBoolean("ipotesi") && Regex("^(si|esatto|giusto|bravo|hai indovinato|indovinato|certo|esattamente|proprio)").containsMatchIn(t)) {
                    chiediAncora(ctx, "venti")
                    Trofei.evento(ctx, "venti_perse", 10)
                    Suoni.suona(ctx, "vittoria")
                    return "Evvai, ho indovinato in ${g.optInt("n")} domande! Un'altra partita?"
                }
            }
        }
        val n = g.optInt("n")
        if (n >= 20) {
            g.put("attesa", "rivela")
            salva(ctx, g)
            return "Venti domande finite: mi arrendo! A cosa avevi pensato?"
        }
        val prossima = prossimaDomanda(c, g) ?: run {
            termina(ctx)
            return "Non riesco a contattare il cervello. Proviamo l'impiccato o un indovinello, che funzionano offline?"
        }
        g.put("n", n + 1).put("domanda", prossima.first).put("ipotesi", prossima.second).put("attesa", "risposta")
        salva(ctx, g)
        return if (prossima.second) "Domanda ${n + 1}: ci provo! ${prossima.first}" else "Domanda ${n + 1}: ${prossima.first}"
    }

    private fun originaleBreve(t: String) = t.replace(Regex("^(era |e |ho pensato a |avevo pensato a |pensavo a )"), "")
        .replaceFirstChar { it.uppercase() }.take(60)

    // ================================================================
    //  STORIE A BIVI
    // ================================================================

    private const val CAPITOLI = 6

    private fun avviaStoria(c: Cervello, ctx: Context, t: String): String {
        val tema = Regex("(?:storia|avventura)(?: a bivi| interattiva)? (?:di |sui |sugli |sulle |su |con |nel |nello |nella |tra )(.+)$").find(t)?.groupValues?.get(1) ?: ""
        val richiesta = "Inizia una storia interattiva in italiano" + (if (tema.isNotBlank()) " sul tema: $tema" else "") +
            ". Seconda persona singolare, adatta a tutti, frasi semplici e vivaci. Scrivi al massimo 90 parole, " +
            "poi proponi due scelte così: «Uno: ...» e «Due: ...». Non usare elenchi o asterischi."
        val r = try { c.chiamaSemplice(richiesta, 400).trim() } catch (e: Exception) { "" }
        if (r.isBlank()) return "Non riesco a contattare il cervello per la storia. Proviamo un indovinello?"
        salva(ctx, JSONObject().put("tipo", "storia").put("passo", 0).put("testo", r))
        return r
    }

    private fun storia(c: Cervello, ctx: Context, g: JSONObject, t: String): String {
        val scelta = when {
            Regex("^(1|uno|la prima|prima|la uno)\\b").containsMatchIn(t) -> "la scelta Uno"
            Regex("^(2|due|la seconda|seconda|la due)\\b").containsMatchIn(t) -> "la scelta Due"
            else -> "questo: $t"
        }
        val passo = g.optInt("passo") + 1
        val finale = passo >= CAPITOLI - 1
        val richiesta = "Stai raccontando una storia interattiva in italiano, in seconda persona, adatta a tutti. " +
            "Ecco la storia finora:\n${g.optString("testo").takeLast(1800)}\n\nIl lettore sceglie $scelta. " +
            if (finale) "Concludi la storia con un bel finale, al massimo 100 parole, senza altre scelte."
            else "Continua (al massimo 90 parole) e proponi due nuove scelte così: «Uno: ...» e «Due: ...». Non usare elenchi o asterischi."
        val r = try { c.chiamaSemplice(richiesta, 450).trim() } catch (e: Exception) { "" }
        if (r.isBlank()) return "Il cervello non risponde. Riprova a dirmi la tua scelta, oppure di' «basta giocare»."
        if (finale) {
            chiediAncora(ctx, "storia")
            Trofei.evento(ctx, "storia_finita", 40)
            return "$r Fine. Ne vuoi un'altra?"
        }
        g.put("passo", passo).put("testo", g.optString("testo") + "\n[Scelta: $scelta]\n" + r)
        salva(ctx, g)
        return r
    }
}
