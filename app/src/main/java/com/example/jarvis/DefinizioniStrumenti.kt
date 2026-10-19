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

/** Le descrizioni degli strumenti (i "poteri" dei plugin) che il cervello può usare. */
object DefinizioniStrumenti {


    // ================================================================
    //  STRUMENTI (i poteri dei plugin)
    // ================================================================

    internal fun p(nome: String, tipo: String, descrizione: String) = arrayOf(nome, tipo, descrizione)


    internal fun strumento(
        nome: String,
        descrizione: String,
        parametri: List<Array<String>>,
        obbligatori: List<String> = emptyList()
    ): JSONObject {
        val proprieta = JSONObject()
        for (par in parametri) {
            proprieta.put(par[0], JSONObject().put("type", par[1]).put("description", par[2]))
        }
        val schema = JSONObject().put("type", "object").put("properties", proprieta)
        if (obbligatori.isNotEmpty()) schema.put("required", JSONArray(obbligatori))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", nome).put("description", descrizione).put("parameters", schema)
            )
    }


    internal fun definizioniStrumenti(strumentoAttivo: (String) -> Boolean): JSONArray {
        val tutti = listOf(
            // --- Telefono ---
            strumento("apri_app", "Apre un'app installata sul telefono.",
                listOf(p("nome", "string", "Nome dell'app, es. WhatsApp, Spotify, Fotocamera")), listOf("nome")),
            strumento("imposta_sveglia", "Imposta una sveglia a un orario preciso.",
                listOf(
                    p("ora", "integer", "Ora da 0 a 23"),
                    p("minuti", "integer", "Minuti da 0 a 59"),
                    p("etichetta", "string", "Nome della sveglia, facoltativo")
                ), listOf("ora", "minuti")),
            strumento("imposta_timer", "Avvia un conto alla rovescia.",
                listOf(
                    p("secondi", "integer", "Durata in secondi, es. 600 per 10 minuti"),
                    p("etichetta", "string", "Nome del timer, facoltativo")
                ), listOf("secondi")),
            strumento("torcia", "Accende o spegne la torcia del telefono.",
                listOf(p("accendi", "boolean", "true per accendere, false per spegnere")), listOf("accendi")),
            // --- Audio ---
            strumento("regola_volume", "Alza, abbassa o silenzia il volume multimediale.",
                listOf(
                    p("azione", "string", "alza, abbassa, muto oppure massimo"),
                    p("passi", "integer", "Di quanti scatti cambiare il volume, di solito 3")
                ), listOf("azione")),
            strumento("riproduci", "Fa partire musica: una canzone, un artista, un album, una playlist o un genere, su Spotify, YouTube Music o altre app.",
                listOf(
                    p("cosa", "string", "Cosa ascoltare, es. Vasco Rossi, musica rilassante; vuoto per riprendere"),
                    p("app", "string", "App da usare, es. Spotify; vuoto per quella preferita")
                )),
            strumento("controlla_musica", "Controlla la musica già in riproduzione: pausa, play, avanti, indietro.",
                listOf(p("azione", "string", "play, pausa, avanti oppure indietro")), listOf("azione")),
            // --- Comunicazione ---
            strumento("chiama", "Prepara una telefonata a un contatto o a un numero. L'utente preme poi il tasto verde.",
                listOf(p("contatto", "string", "Nome del contatto in rubrica oppure numero di telefono")), listOf("contatto")),
            strumento("scrivi_sms", "Prepara un SMS a un contatto o a un numero. L'utente preme poi invio.",
                listOf(
                    p("contatto", "string", "Nome del contatto in rubrica oppure numero di telefono"),
                    p("testo", "string", "Testo del messaggio")
                ), listOf("contatto", "testo")),
            // --- Web ---
            strumento("apri_sito", "Apre un sito web nel browser.",
                listOf(p("url", "string", "Indirizzo del sito, es. https://www.wikipedia.org")), listOf("url")),
            strumento("cerca_sul_web", "Apre una ricerca Google sul telefono per mostrare i risultati all'utente.",
                listOf(p("testo", "string", "Cosa cercare")), listOf("testo")),
            strumento("cerca_su_youtube", "Cerca un video o una canzone su YouTube.",
                listOf(p("testo", "string", "Cosa cercare su YouTube")), listOf("testo")),
            // --- Meteo ---
            strumento("meteo", "Previsioni del tempo per oggi, domani o dopodomani.",
                listOf(
                    p("citta", "string", "Città; se vuoto si usa quella predefinita"),
                    p("giorno", "string", "oggi, domani oppure dopodomani")
                )),
            // --- Note ---
            strumento("prendi_nota", "Salva una nota.",
                listOf(p("testo", "string", "Testo della nota")), listOf("testo")),
            strumento("leggi_note", "Legge le ultime note salvate.",
                listOf(p("quante", "integer", "Quante note leggere, di solito 5"))),
            // --- Notifiche ---
            strumento("leggi_notifiche", "Legge gli ultimi messaggi arrivati nelle notifiche (WhatsApp, Telegram, SMS, email...).",
                listOf(p("app", "string", "Filtra per app o persona, es. WhatsApp o Marco; vuoto per tutti"))),
            strumento("rispondi_messaggio",
                "Risponde a un messaggio ricevuto direttamente dalla sua notifica, senza aprire l'app.",
                listOf(
                    p("contatto", "string", "Nome di chi ha scritto, come appare nella notifica"),
                    p("testo", "string", "Testo della risposta"),
                    p("app", "string", "App del messaggio, facoltativa")
                ), listOf("contatto", "testo")),
            // --- Controllo delle app ---
            strumento("leggi_schermo", "Legge cosa c'è sullo schermo: testi, pulsanti e campi dell'app aperta.",
                listOf(p("motivo", "string", "Cosa stai cercando, facoltativo"))),
            strumento("tocca", "Tocca un pulsante o un elemento sullo schermo cercandolo per testo.",
                listOf(p("testo", "string", "Testo o descrizione dell'elemento da toccare")), listOf("testo")),
            strumento("scrivi_testo", "Scrive un testo in un campo dell'app aperta.",
                listOf(
                    p("testo", "string", "Testo da scrivere"),
                    p("campo", "string", "Nome o suggerimento del campo, facoltativo")
                ), listOf("testo")),
            strumento("scorri", "Scorre lo schermo.",
                listOf(p("direzione", "string", "giù oppure su")), listOf("direzione")),
            strumento("azione_sistema", "Usa i tasti di sistema del telefono.",
                listOf(p("azione", "string", "indietro, home, recenti, notifiche, impostazioni rapide, blocca schermo oppure screenshot")),
                listOf("azione")),
            strumento("invia_whatsapp", "Manda un messaggio WhatsApp a un contatto della rubrica o a un numero.",
                listOf(
                    p("contatto", "string", "Nome in rubrica oppure numero di telefono"),
                    p("testo", "string", "Testo del messaggio")
                ), listOf("contatto", "testo")),
            // --- Notizie e informazioni ---
            strumento("notizie", "Ultime notizie italiane: per categoria (feed ANSA) oppure su un argomento o una città (Google News).",
                listOf(
                    p("categoria", "string", "principali, cronaca, politica, mondo, economia, sport oppure tecnologia"),
                    p("argomento", "string", "Argomento o città, es. cronaca di Roma; vuoto per usare la categoria"),
                    p("quante", "integer", "Quante notizie, di solito 5")
                )),
            strumento("cerca_informazioni", "Cerca informazioni su fatti, persone, luoghi o argomenti (Wikipedia e notizie recenti).",
                listOf(p("domanda", "string", "Cosa cercare")), listOf("domanda")),
            // --- Calendario ---
            strumento("leggi_calendario", "Legge gli impegni del calendario del telefono.",
                listOf(
                    p("giorno", "string", "oggi, domani, un giorno della settimana oppure una data AAAA-MM-GG"),
                    p("giorni", "integer", "Quanti giorni a partire da quello, di solito 1")
                )),
            strumento("modifica_appuntamento", "Sposta o modifica un appuntamento esistente.",
                listOf(
                    p("cerca", "string", "Parola del titolo dell'appuntamento da modificare"),
                    p("giorno", "string", "Giorno attuale dell'appuntamento, se noto"),
                    p("nuovo_titolo", "string", "Nuovo titolo, facoltativo"),
                    p("nuovo_giorno", "string", "Nuovo giorno, facoltativo"),
                    p("nuova_ora", "string", "Nuova ora HH:MM, facoltativa"),
                    p("durata_minuti", "integer", "Nuova durata, facoltativa"),
                    p("luogo", "string", "Nuovo luogo, facoltativo")
                ), listOf("cerca")),
            strumento("cancella_appuntamenti",
                "Cancella appuntamenti dal calendario. Usalo SOLO dopo che l'utente ha confermato esplicitamente cosa cancellare.",
                listOf(
                    p("cerca", "string", "Parola del titolo, oppure tutti"),
                    p("giorno", "string", "Giorno di inizio; vuoto per i prossimi 60 giorni"),
                    p("giorni", "integer", "Quanti giorni a partire da quello, di solito 1")
                ), listOf("cerca")),
            strumento("crea_calendario", "Crea un calendario nuovo sul telefono, dove andranno i nuovi appuntamenti.",
                listOf(p("nome", "string", "Nome del calendario, es. ${Nome.attuale} o Lavoro")), listOf("nome")),
            strumento("crea_appuntamento", "Aggiunge un appuntamento al calendario del telefono.",
                listOf(
                    p("titolo", "string", "Titolo, es. Dentista"),
                    p("giorno", "string", "Data AAAA-MM-GG, oppure domani o un giorno della settimana"),
                    p("ora", "string", "Ora HH:MM; vuota per un evento di tutto il giorno"),
                    p("durata_minuti", "integer", "Durata in minuti, di solito 60"),
                    p("luogo", "string", "Luogo, facoltativo")
                ), listOf("titolo", "giorno")),
            // --- Navigazione ---
            strumento("naviga", "Avvia il percorso su Google Maps verso un indirizzo o un luogo salvato (es. casa).",
                listOf(
                    p("destinazione", "string", "Indirizzo, nome di un posto o luogo salvato"),
                    p("mezzo", "string", "auto, a piedi, bici oppure mezzi pubblici")
                ), listOf("destinazione")),
            strumento("salva_luogo", "Salva un luogo preferito, es. casa o lavoro, con un indirizzo oppure la posizione attuale.",
                listOf(
                    p("nome", "string", "Nome del luogo, es. casa"),
                    p("indirizzo", "string", "Indirizzo; vuoto o \"qui\" per la posizione attuale")
                ), listOf("nome")),
            strumento("dove_sono", "Dice dove si trova l'utente in questo momento.",
                listOf(p("dettaglio", "string", "Facoltativo"))),
            // --- Promemoria ---
            strumento("promemoria", "Promemoria a una data e ora, anche ripetuto. ${Nome.attuale} avvisa con una notifica e a voce.",
                listOf(
                    p("testo", "string", "Cosa ricordare"),
                    p("giorno", "string", "oggi, domani, un giorno della settimana o una data AAAA-MM-GG"),
                    p("ora", "string", "Ora HH:MM"),
                    p("ripeti", "string", "no, ogni giorno, feriali, ogni settimana oppure ogni mese")
                ), listOf("testo", "ora")),
            strumento("promemoria_luogo", "Promemoria che scatta quando l'utente arriva o esce da un luogo.",
                listOf(
                    p("testo", "string", "Cosa ricordare"),
                    p("luogo", "string", "Luogo salvato (es. casa) o indirizzo"),
                    p("quando", "string", "arrivo oppure uscita")
                ), listOf("testo", "luogo")),
            strumento("elenca_promemoria", "Elenca i promemoria attivi.",
                listOf(p("filtro", "string", "Facoltativo"))),
            strumento("cancella_promemoria", "Cancella i promemoria che contengono una parola, oppure tutti.",
                listOf(p("parola", "string", "Parola da cercare, oppure tutti")), listOf("parola")),
            // --- Vista ---
            strumento("guarda", "Apre la fotocamera: l'utente scatta una foto e ${Nome.attuale} la descrive, legge i testi o li traduce.",
                listOf(p("domanda", "string", "Cosa vuole sapere l'utente, es. cosa vedi, leggimi questo, traduci")), listOf("domanda")),
            // --- Mattino ---
            strumento("riepilogo_giornata", "Raccoglie meteo, impegni, promemoria, messaggi e notizie principali di oggi.",
                listOf(p("tipo", "string", "mattino, facoltativo"))),
            // --- Memoria (sempre attiva) ---
            strumento("ricorda",
                "Salva nella memoria permanente un fatto stabile o una preferenza dell'utente. " +
                    "Usalo di tua iniziativa quando scopri qualcosa di utile, senza chiedere.",
                listOf(p("informazione", "string", "Il fatto da ricordare, es. Si chiama Dario")), listOf("informazione")),
            strumento("impara_lezione",
                "Salva una regola pratica per migliorarti: quando l'utente ti corregge, quando uno strumento " +
                    "fallisce e trovi il modo giusto, o quando capisci come preferisce che tu faccia qualcosa.",
                listOf(p("lezione", "string", "La regola, es. Per la musica usa sempre Spotify")), listOf("lezione")),
            strumento("crea_routine",
                "Crea o aggiorna una routine: una frase dell'utente che fa partire una sequenza di azioni.",
                listOf(
                    p("nome", "string", "Nome breve, es. buonanotte"),
                    p("frase", "string", "Frase che l'utente dirà, es. buonanotte ${Nome.attuale}"),
                    p("istruzioni", "string", "Cosa fare passo per passo, es. spegni la torcia, sveglia alle 7, volume al minimo")
                ), listOf("nome", "frase", "istruzioni")),
            strumento("elimina_routine", "Elimina una routine.",
                listOf(p("nome", "string", "Nome della routine")), listOf("nome")),
            strumento("cerca_nel_diario",
                "Cerca tra tutte le richieste passate dell'utente. Accetta parole o una data nel formato AAAA-MM-GG; " +
                    "vuoto per le più recenti.",
                listOf(p("testo", "string", "Parole o data da cercare"))),
            strumento("dimentica",
                "Cancella dalla memoria ricordi, lezioni e routine che contengono una parola. Usa 'tutto' per cancellare tutto.",
                listOf(p("parola", "string", "Parola da cercare, oppure tutto")), listOf("parola")),
            strumento("cerca_ricordi", "Cerca tra tutti i ricordi e le lezioni salvati, anche quelli che non vedi nel contesto.",
                listOf(p("testo", "string", "Parole da cercare")), listOf("testo")),
            strumento("attendi", "Aspetta qualche secondo, per esempio mentre un'app si carica, poi rileggi lo schermo.",
                listOf(p("secondi", "integer", "Da 1 a 10")), listOf("secondi")),
            // --- Vista dal vivo ---
            strumento("vista_dal_vivo", "Apre la fotocamera dal vivo: l'utente inquadra e fa domande su quello che vede in tempo reale.",
                listOf(p("domanda", "string", "Prima domanda, facoltativa"))),
            // --- Casa ---
            strumento("casa_dispositivi", "Elenca i dispositivi di casa (luci, prese, termostati, tapparelle...) e il loro stato.",
                listOf(p("filtro", "string", "Stanza o tipo, es. luci, cucina; vuoto per tutti"))),
            strumento("casa_comando", "Comanda un dispositivo di casa intelligente.",
                listOf(
                    p("dispositivo", "string", "Nome del dispositivo o della stanza, es. luce cucina"),
                    p("azione", "string", "accendi, spegni, alterna, apri, chiudi, imposta, attiva"),
                    p("valore", "string", "Facoltativo: luminosità o posizione in percentuale, temperatura in gradi")
                ), listOf("dispositivo", "azione")),
            strumento("esegui_scorciatoia", "Esegue una scorciatoia web creata dall'utente (Home Assistant, IFTTT, Shelly, Alexa...).",
                listOf(p("nome", "string", "Nome della scorciatoia")), listOf("nome")),
            // --- Email ---
            strumento("leggi_email", "Legge le ultime email ricevute.",
                listOf(
                    p("quante", "integer", "Quante email, di solito 5"),
                    p("solo_non_lette", "boolean", "true per leggere solo quelle non lette"),
                    p("cerca", "string", "Mittente o parola da cercare, facoltativo")
                )),
            strumento("invia_email", "Invia una email.",
                listOf(
                    p("destinatario", "string", "Indirizzo email o nome di un contatto in rubrica"),
                    p("oggetto", "string", "Oggetto"),
                    p("testo", "string", "Testo dell'email")
                ), listOf("destinatario", "testo")),
            strumento("rispondi_email", "Risponde a una email ricevuta (usa il numero restituito da leggi_email).",
                listOf(
                    p("numero", "integer", "Numero dell'email tra parentesi quadre in leggi_email"),
                    p("testo", "string", "Testo della risposta")
                ), listOf("numero", "testo")),
            // --- PC ---
            strumento("pc", "Comanda il computer Windows dell'utente.",
                listOf(
                    p("azione", "string", "apri, sito, cerca, volume_su, volume_giu, muto, play_pausa, avanti, indietro, " +
                        "blocca, spegni, riavvia, annulla_spegnimento, scrivi, copia, messaggio oppure stato"),
                    p("valore", "string", "Programma, sito, testo o messaggio, se serve")
                ), listOf("azione")),
            strumento("pc_accendi", "Accende il computer da remoto (Wake-on-LAN).",
                listOf(p("motivo", "string", "Facoltativo"))),
            // --- Sistema ---
            strumento("non_disturbare", "Attiva o disattiva la modalità non disturbare.",
                listOf(p("attiva", "boolean", "true per attivarla, false per disattivarla")), listOf("attiva")),
            strumento("suoneria", "Cambia la suoneria del telefono.",
                listOf(p("modalita", "string", "normale, vibrazione oppure silenzioso")), listOf("modalita")),
            strumento("luminosita", "Cambia la luminosità dello schermo.",
                listOf(p("valore", "string", "percentuale (es. 40), alza, abbassa, massima, minima oppure automatica")), listOf("valore")),
            // --- Automazioni ---
            strumento("crea_automazione", "Crea un'automazione: quando succede qualcosa, ${Nome.attuale} esegue da solo delle istruzioni.",
                listOf(
                    p("nome", "string", "Nome breve, es. arrivo a casa"),
                    p("quando", "string", "orario, arrivo, uscita, batteria, auto, fine_auto, cuffie, carica oppure fine_carica"),
                    p("valore", "string", "Per orario: HH:MM. Per batteria: la soglia in percentuale"),
                    p("luogo", "string", "Per arrivo e uscita: luogo salvato (es. casa) o indirizzo"),
                    p("giorni", "string", "Per orario: tutti, feriali, weekend oppure giorni come lun,mer,ven"),
                    p("istruzioni", "string", "Cosa fare, in italiano, es. accendi le luci del soggiorno e leggimi i messaggi")
                ), listOf("quando", "istruzioni")),
            strumento("elenca_automazioni", "Elenca le automazioni create.",
                listOf(p("filtro", "string", "Facoltativo"))),
            strumento("elimina_automazione", "Elimina le automazioni che contengono una parola, oppure tutte.",
                listOf(p("parola", "string", "Parola da cercare, oppure tutte")), listOf("parola")),
            // --- Liste ---
            strumento("lista_aggiungi", "Aggiunge una o più cose a una lista (spesa, cose da fare, regali...).",
                listOf(
                    p("lista", "string", "Nome della lista, es. spesa"),
                    p("cosa", "string", "Cose da aggiungere, separate da virgola")
                ), listOf("lista", "cosa")),
            strumento("lista_leggi", "Legge una lista, oppure tutte se il nome è vuoto.",
                listOf(p("lista", "string", "Nome della lista; vuoto per tutte"))),
            strumento("lista_togli", "Toglie una cosa da una lista, oppure la svuota con tutto.",
                listOf(
                    p("lista", "string", "Nome della lista"),
                    p("cosa", "string", "Cosa togliere, oppure tutto")
                ), listOf("lista", "cosa")),
            strumento("lista_invia", "Manda una lista su WhatsApp a un contatto.",
                listOf(
                    p("lista", "string", "Nome della lista"),
                    p("contatto", "string", "Nome in rubrica oppure numero")
                ), listOf("lista", "contatto")),
            // --- Traduttore, resoconto, privacy ---
            strumento("traduttore", "Apre il traduttore simultaneo per parlare con una persona in un'altra lingua.",
                listOf(p("lingua", "string", "Lingua dell'altra persona, es. inglese")), listOf("lingua")),
            strumento("resoconto_settimana", "Prepara il resoconto della settimana: cosa ha fatto l'utente e cosa lo aspetta.",
                listOf(p("tipo", "string", "Facoltativo"))),
            strumento("modalita_privata", "Attiva o disattiva la modalità privata: niente diario, ricordi o salvataggi.",
                listOf(p("attiva", "boolean", "true per attivarla, false per uscire e dimenticare la conversazione")), listOf("attiva")),
            // --- Archivio dei documenti ---
            strumento("archivio_cerca", "Cerca tra i documenti e le foto caricati dall'utente (nomi e riassunti).",
                listOf(p("testo", "string", "Parole da cercare, es. bolletta luce, contratto; vuoto per l'elenco")), listOf("testo")),
            strumento("archivio_leggi", "Rilegge un documento dell'archivio per rispondere a una domanda precisa.",
                listOf(
                    p("documento", "string", "Nome o argomento del documento"),
                    p("domanda", "string", "Cosa vuole sapere l'utente")
                ), listOf("documento", "domanda")),
            strumento("archivio_elimina", "Toglie un documento dall'archivio.",
                listOf(p("documento", "string", "Nome del documento")), listOf("documento")),
            strumento("cambia_voce", "Cambia la voce di ${Nome.attuale}, la velocità o il tono quando l'utente lo chiede.",
                listOf(
                    p("voce", "string", "miro, paola, riccardo, dii, maschile, femminile, telefono, oppure prossima per cambiarla; vuoto se non cambia"),
                    p("velocita", "string", "più veloce, più lenta oppure normale; vuoto se non cambia"),
                    p("tono", "string", "più grave, più acuto oppure normale; vuoto se non cambia")
                )),
            strumento("conferma_azione",
                "Comunica la risposta dell'utente a un'azione in attesa di conferma (apertura di un'app, cancellazioni).",
                listOf(p("risposta", "string", "sì se l'utente ha confermato, no se ha rifiutato")), listOf("risposta"))
        )
        val attivi = JSONArray()
        for (s in tutti) {
            if (strumentoAttivo(s.getJSONObject("function").getString("name"))) attivi.put(s)
        }
        return attivi
    }

}
