package com.example.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Livelli, punti e trofei: si guadagnano usando Jarvis e giocando con lui.
 * Tutto resta sul telefono.
 */
object Trofei {

    class Trofeo(val id: String, val nome: String, val descrizione: String, val contatore: String, val soglia: Int)

    /** I trofei: si sbloccano quando un contatore raggiunge la soglia. */
    val TUTTI = listOf(
        Trofeo("prima", "Prima chiacchierata", "Fai la prima richiesta", "richieste", 1),
        Trofeo("cento", "Cento richieste", "Fai 100 richieste al tuo assistente", "richieste", 100),
        Trofeo("mille", "Inseparabili", "Fai 1000 richieste", "richieste", 1000),
        Trofeo("mattiniero", "Mattiniero", "10 richieste prima delle 8", "mattino", 10),
        Trofeo("nottambulo", "Nottambulo", "10 richieste dopo mezzanotte", "notte", 10),
        Trofeo("senza_rete", "Senza rete", "50 comandi fatti senza l'IA", "locali", 50),
        Trofeo("esploratore", "Esploratore", "Usa 10 funzioni diverse", "strumenti_diversi", 10),
        Trofeo("serie3", "Tre di fila", "Usalo 3 giorni di fila", "serie", 3),
        Trofeo("serie7", "Una settimana", "7 giorni di fila", "serie", 7),
        Trofeo("serie30", "Un mese insieme", "30 giorni di fila", "serie", 30),
        Trofeo("giocatore", "Giocatore", "Gioca la prima partita", "giochi", 1),
        Trofeo("tutti_giochi", "Tuttofare", "Prova tutti e 5 i giochi a voce", "giochi_diversi", 5),
        Trofeo("impiccato", "Salvato!", "Vinci all'impiccato", "vittoria_impiccato", 1),
        Trofeo("impiccato_perfetto", "Senza un errore", "Vinci all'impiccato senza errori", "impiccato_perfetto", 1),
        Trofeo("indovino", "Indovino", "Risolvi 10 indovinelli", "indovinello_risolto", 10),
        Trofeo("quiz_perfetto", "Quiz perfetto", "5 risposte giuste su 5", "quiz_perfetto", 1),
        Trofeo("sfuggente", "Imprendibile", "Non si fa indovinare in 20 domande", "venti_vinte", 1),
        Trofeo("narratore", "Fine della storia", "Arriva in fondo a una storia a bivi", "storia_finita", 1),
        Trofeo("eco", "Memoria di ferro", "Arriva a 10 a Eco di luce", "eco_10", 1),
        Trofeo("acchiappa", "Riflessi pronti", "25 punti ad Acchiappa", "acchiappa_25", 1),
        Trofeo("ritmo", "Senso del ritmo", "18 colpi su 20 a Ritmo", "ritmo_18", 1),
        Trofeo("equilibrio", "Mano ferma", "20 secondi in equilibrio", "equilibrio_20", 1),
        Trofeo("scuotitore", "Non scuotermi!", "Scuoti il telefono 10 volte", "scossa", 10),
        Trofeo("stilista", "Stilista", "Salva una personalizzazione", "personalizza", 1)
    )

    /** Le sfide del giorno: una diversa ogni giorno. */
    private class Sfida(val testo: String, val contatore: String, val quante: Int, val premio: Int)

    private val SFIDE = listOf(
        Sfida("Risolvi 3 indovinelli", "indovinello_risolto", 3, 50),
        Sfida("Fai 5 comandi senza l'IA", "locali", 5, 30),
        Sfida("Vinci una partita all'impiccato", "vittoria_impiccato", 1, 50),
        Sfida("Gioca a un minigioco con la sfera", "minigioco", 1, 20),
        Sfida("Finisci un quiz", "quiz_finito", 1, 40),
        Sfida("Gioca a due giochi diversi", "giochi", 2, 40),
        Sfida("Fai 10 richieste", "richieste", 10, 30)
    )

    private val NOMI_LIVELLI = listOf(
        1 to "Novizio", 3 to "Apprendista", 6 to "Assistente", 10 to "Esperto", 15 to "Maestro", 20 to "Leggenda"
    )

    /** Chiamato quando si sblocca qualcosa: messaggio da mostrare (può arrivare da qualsiasi thread). */
    @Volatile var alSblocco: ((String) -> Unit)? = null

    private val blocco = Any()
    private val formatoGiorno = SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun leggi(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("trofei", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun salva(ctx: Context, s: JSONObject) = prefs(ctx).edit().putString("trofei", s.toString()).apply()

    fun azzera(ctx: Context) = prefs(ctx).edit().remove("trofei").commit()

    // ================================================================
    //  LIVELLI
    // ================================================================

    /** Punti che servono per passare dal livello [livello] al successivo. */
    fun puntiPerSalire(livello: Int) = 100 + 50 * (livello - 1)

    /** (livello, punti dentro il livello, punti che servono per salire) */
    fun livello(xp: Int): Triple<Int, Int, Int> {
        var l = 1
        var resto = xp
        while (resto >= puntiPerSalire(l)) {
            resto -= puntiPerSalire(l)
            l++
        }
        return Triple(l, resto, puntiPerSalire(l))
    }

    fun nomeLivello(l: Int) = NOMI_LIVELLI.lastOrNull { l >= it.first }?.second ?: "Novizio"

    fun punti(ctx: Context) = leggi(ctx).optInt("xp", 0)

    fun sbloccati(ctx: Context): Set<String> {
        val a = leggi(ctx).optJSONArray("sbloccati") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }

    fun serie(ctx: Context) = leggi(ctx).optJSONObject("giorni")?.optInt("serie", 0) ?: 0

    fun contatore(ctx: Context, nome: String) = leggi(ctx).optJSONObject("contatori")?.optInt(nome, 0) ?: 0

    // ================================================================
    //  EVENTI
    // ================================================================

    /**
     * Qualcosa è successo: aggiorna contatori, punti, serie di giorni, sfida del giorno e trofei.
     * @param contatore quale contatore aumenta (vedi i trofei)
     * @param punti punti guadagnati
     */
    fun evento(ctx: Context, contatore: String, punti: Int = 0, quanto: Int = 1) {
        val messaggi = ArrayList<String>()
        synchronized(blocco) {
            val s = leggi(ctx)
            val prima = livello(s.optInt("xp", 0)).first
            val contatori = s.optJSONObject("contatori") ?: JSONObject().also { s.put("contatori", it) }
            contatori.put(contatore, contatori.optInt(contatore, 0) + quanto)
            var xp = s.optInt("xp", 0) + punti

            // serie di giorni di fila
            val oggi = formatoGiorno.format(Date())
            val giorni = s.optJSONObject("giorni") ?: JSONObject().also { s.put("giorni", it) }
            if (giorni.optString("ultimo") != oggi) {
                val ieri = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
                val serie = if (giorni.optString("ultimo") == formatoGiorno.format(ieri.time)) giorni.optInt("serie", 0) + 1 else 1
                giorni.put("ultimo", oggi).put("serie", serie).put("migliore", maxOf(serie, giorni.optInt("migliore", 0)))
                contatori.put("serie", serie)
                xp += 5
            }

            // sfida del giorno
            val sfida = sfidaDiOggi()
            val stato = s.optJSONObject("sfida") ?: JSONObject()
            if (stato.optString("giorno") != oggi) {
                stato.put("giorno", oggi).put("progresso", 0).put("fatta", false)
            }
            if (!stato.optBoolean("fatta") && sfida.contatore == contatore) {
                val progresso = stato.optInt("progresso", 0) + quanto
                stato.put("progresso", progresso)
                if (progresso >= sfida.quante) {
                    stato.put("fatta", true)
                    xp += sfida.premio
                    messaggi.add("Sfida di oggi completata: ${sfida.testo.lowercase(Locale.ITALIAN)}. +${sfida.premio} punti.")
                }
            }
            s.put("sfida", stato)

            // trofei
            val sbloccati = s.optJSONArray("sbloccati") ?: JSONArray().also { s.put("sbloccati", it) }
            val gia = (0 until sbloccati.length()).map { sbloccati.optString(it) }.toMutableSet()
            for (t in TUTTI) {
                if (t.id in gia) continue
                if (contatori.optInt(t.contatore, 0) >= t.soglia) {
                    sbloccati.put(t.id)
                    gia.add(t.id)
                    xp += 25
                    messaggi.add("Trofeo sbloccato: ${t.nome}. ${t.descrizione}.")
                }
            }
            s.put("xp", xp)
            val dopo = livello(xp).first
            if (dopo > prima) messaggi.add("Sei salito al livello $dopo: ${nomeLivello(dopo)}.")
            salva(ctx, s)
        }
        if (messaggi.isNotEmpty()) {
            try { Suoni.fanfara(ctx) } catch (e: Throwable) { }
            for (m in messaggi) try { alSblocco?.invoke(m) } catch (e: Throwable) { }
        }
    }

    /** Ogni richiesta a Jarvis: punti, ore del giorno, funzioni usate. */
    fun richiesta(ctx: Context, tipo: String, strumenti: List<String>) {
        val ora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (ora < 8 && ora >= 5) evento(ctx, "mattino")
        if (ora < 5) evento(ctx, "notte")
        if (tipo == "rapida" || tipo == "offline") evento(ctx, "locali")
        if (strumenti.isNotEmpty()) {
            synchronized(blocco) {
                val s = leggi(ctx)
                val usati = s.optJSONArray("usati") ?: JSONArray().also { s.put("usati", it) }
                val gia = (0 until usati.length()).map { usati.optString(it) }.toMutableSet()
                var nuovi = 0
                for (n in strumenti) if (gia.add(n)) { usati.put(n); nuovi++ }
                salva(ctx, s)
                if (nuovi > 0) evento(ctx, "strumenti_diversi", 3 * nuovi, nuovi)
            }
        }
        evento(ctx, "richieste", 2)
    }

    /** Un gioco a voce è iniziato. */
    fun giocoIniziato(ctx: Context, gioco: String) {
        var nuovo = false
        synchronized(blocco) {
            val s = leggi(ctx)
            val giocati = s.optJSONArray("giocati") ?: JSONArray().also { s.put("giocati", it) }
            val gia = (0 until giocati.length()).map { giocati.optString(it) }
            if (gioco !in gia) { giocati.put(gioco); nuovo = true }
            salva(ctx, s)
        }
        if (nuovo) evento(ctx, "giochi_diversi")
        evento(ctx, "giochi", 5)
    }

    // ================================================================
    //  DESCRIZIONE PER LA PAGINA TROFEI
    // ================================================================

    private fun sfidaDiOggi(): Sfida = SFIDE[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % SFIDE.size]

    /** (testo, progresso, quante, fatta) */
    fun sfida(ctx: Context): String {
        val sfida = sfidaDiOggi()
        val stato = leggi(ctx).optJSONObject("sfida")
        val oggi = formatoGiorno.format(Date())
        val progresso = if (stato?.optString("giorno") == oggi) stato.optInt("progresso", 0) else 0
        val fatta = stato?.optString("giorno") == oggi && stato.optBoolean("fatta")
        return if (fatta) "${sfida.testo} · fatta! +${sfida.premio} punti"
        else "${sfida.testo} · ${progresso.coerceAtMost(sfida.quante)} di ${sfida.quante} · +${sfida.premio} punti"
    }
}
