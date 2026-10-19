package com.example.jarvis

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pannello PERSONALIZZA: si apre dal basso sopra la schermata e ogni scelta si vede subito (prova dal vivo).
 * CONFERMA salva e resta qui; ANNULLA torna all'ultima versione confermata; GUARDA abbassa il pannello
 * per vedere tutto lo schermo. Chiudendo senza confermare, le prove vengono annullate.
 */
class PannelloPersonalizza(
    private val a: Activity,
    private val prefs: SharedPreferences,
    private val tema: Tema.Palette,
    private val altezzaAperto: Int,
    private val aggiorna: () -> Unit,
    private val anteprimaColore: () -> Unit,
    private val scegliFoto: () -> Unit,
    private val cambiaNome: (String) -> String,
    private val provaNome: () -> Unit,
    private val alChiudere: () -> Unit,
    confermatoIniziale: Map<String, Any?>? = null
) {

    val SCHEDE = listOf("nome" to "NOME", "3d" to "3D E COLORI", "menu" to "MENÙ", "home" to "HOME", "pulsanti" to "PULSANTI", "sfondo" to "SFONDO", "suoni" to "SUONI")

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private var confermato = confermatoIniziale ?: Personalizza.istantanea(prefs)
    private var scheda = "nome"

    /** Per ricreare il pannello con i nuovi colori senza perdere la prova in corso. */
    val schedaAttuale get() = scheda
    val confermatoAttuale get() = confermato
    private var ridotto = false

    val vista = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            setColor(Tema.conAlfa(tema.superficie, 248))
            setStroke(dp(1), Tema.conAlfa(tema.accento, 150))
        }
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    private val stato = TextView(a)
    private val linguette = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private val contenuto = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val scorri = ScrollView(a).apply { addView(contenuto) }
    private val piede = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bottoneGuarda = pulsante("GUARDA", false) { riduci(!ridotto) }

    init {
        val testa = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        testa.addView(hud("PERSONALIZZA", 13f, tema.accento), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        testa.addView(hud("✕", 18f, tema.accento).apply {
            setPadding(dp(14), dp(6), dp(4), dp(6))
            contentDescription = "Chiudi personalizza"
            setOnClickListener { chiudi() }
        })
        testa.setOnClickListener { if (ridotto) riduci(false) }
        vista.addView(testa)
        stato.apply {
            textSize = 12f
            setTextColor(tema.tenue)
            text = "Prova dal vivo: ogni scelta si vede subito sullo schermo. Poi CONFERMA o ANNULLA."
            setPadding(0, dp(2), 0, dp(6))
            setOnClickListener { if (ridotto) riduci(false) }
        }
        vista.addView(stato)
        vista.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(linguette)
        })
        vista.addView(scorri, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        piede.addView(bottoneGuarda, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        piede.addView(pulsante("ANNULLA", false) { annulla() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        piede.addView(pulsante("CONFERMA", true) { conferma() }, LinearLayout.LayoutParams(0, dp(46), 1.2f))
        vista.addView(piede, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        mostraScheda(scheda)
    }

    // ================================================================
    //  AZIONI
    // ================================================================

    fun haModifiche() = Personalizza.istantanea(prefs) != confermato

    private fun conferma() {
        campoNome?.let { c -> if (Nome.normalizza(c.text.toString()) != Nome.attuale) usaNome() }
        confermato = Personalizza.istantanea(prefs)
        stato.text = "Salvato ✓  Resti qui: puoi continuare a cambiare."
        stato.setTextColor(tema.accento)
        Thread { Trofei.evento(a, "personalizza", 10) }.start()
    }

    private fun annulla() {
        Personalizza.ripristina(prefs, confermato)
        aggiorna()
        mostraScheda(scheda)
        stato.text = "Annullato: torna com'era all'ultima conferma."
        stato.setTextColor(tema.tenue)
    }

    fun chiudi() {
        if (haModifiche()) {
            Personalizza.ripristina(prefs, confermato)
            aggiorna()
            Toast.makeText(a, "Le prove non confermate sono state annullate.", Toast.LENGTH_SHORT).show()
        }
        alChiudere()
    }

    /** Abbassa il pannello per guardare tutto lo schermo, oppure lo riapre. */
    private fun riduci(si: Boolean) {
        ridotto = si
        linguette.visibility = if (si) View.GONE else View.VISIBLE
        (linguette.parent as? View)?.visibility = if (si) View.GONE else View.VISIBLE
        scorri.visibility = if (si) View.GONE else View.VISIBLE
        bottoneGuarda.text = if (si) "OPZIONI" else "GUARDA"
        stato.text = if (si) "ANTEPRIMA · così sarà la schermata. Tocca OPZIONI per tornare." else
            (if (haModifiche()) "Prove in corso: CONFERMA per salvarle." else "Nessuna modifica da confermare.")
        (vista.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.height = if (si) FrameLayout.LayoutParams.WRAP_CONTENT else altezzaAperto
            vista.layoutParams = it
        }
    }

    /** Dopo aver scelto una foto, ridisegna la scheda. */
    fun ricarica() = mostraScheda(scheda)

    private fun cambiato() {
        aggiorna()
        stato.text = "Prova in corso: guarda lo schermo, poi CONFERMA o ANNULLA."
        stato.setTextColor(tema.tenue)
    }

    // ================================================================
    //  SCHEDE
    // ================================================================

    fun mostraScheda(id: String) {
        val stessa = id == scheda
        val y = scorri.scrollY
        scheda = if (SCHEDE.any { it.first == id }) id else "menu"
        linguette.removeAllViews()
        for ((s, nome) in SCHEDE) {
            val attiva = s == scheda
            linguette.addView(hud(nome, 11f, if (attiva) tema.sfondo else tema.testo).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    if (attiva) setColor(tema.accento) else setStroke(dp(1), Tema.conAlfa(tema.accento, 110))
                }
                setOnClickListener { mostraScheda(s) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
        contenuto.removeAllViews()
        campoNome = null
        when (scheda) {
            "nome" -> schedaNome()
            "3d" -> scheda3D()
            "menu" -> schedaMenu()
            "home" -> schedaHome()
            "pulsanti" -> schedaPulsanti()
            "sfondo" -> schedaSfondo()
            else -> schedaSuoni()
        }
        if (stessa) scorri.post { scorri.scrollTo(0, y) } else scorri.scrollTo(0, 0)
    }

    private fun schedaNome() {
        titolo("Come si chiama il tuo assistente")
        nota("Scegli il nome che vuoi: comparirà in alto, nelle notifiche e nel widget, e sarà la parola per chiamarlo " +
            "(«Ehi ${Nome.attuale}»). Una sola parola. Meglio una parola italiana vera, di 2 o 3 sillabe, che non si dice spesso: " +
            "così il riconoscimento offline la capisce bene e non si attiva per sbaglio.")
        val riga = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val campo = EditText(a).apply {
            hint = "Nome"
            setHintTextColor(tema.tenue)
            setTextColor(tema.testo)
            textSize = 18f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(Nome.attuale)
            setOnEditorActionListener { _, azione, _ ->
                if (azione == EditorInfo.IME_ACTION_DONE) { usaNome(); true } else false
            }
        }
        campoNome = campo
        riga.addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        riga.addView(pulsante("USA", true) { usaNome() }, LinearLayout.LayoutParams(dp(72), dp(44)).apply { marginStart = dp(8) })
        contenuto.addView(riga)
        nota("Tocca USA, il tasto Fine della tastiera oppure CONFERMA in basso.")
        bottone("Prova il nome a voce (parla per 5 secondi)") {
            usaNome()
            provaNome()
        }
        titolo("Idee")
        nota("Di serie si chiama ${Nome.PREDEFINITO}: Assistente Tuttofare, Leale, Attento, Sempre presente. " +
            "Altre idee: Orion, Elias, Victor… oppure il nome che preferisci. " +
            "Sotto l'icona del telefono resta «${Nome.PREDEFINITO}»: Android non permette di cambiarlo dall'app.")
    }

    private var campoNome: EditText? = null

    /** Applica il nome scritto nel campo (se è cambiato). */
    private fun usaNome() {
        val campo = campoNome ?: return
        val n = cambiaNome(campo.text.toString())
        campo.setText(n)
        campo.setSelection(n.length)
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(campo.windowToken, 0)
        stato.text = "Da ora mi chiamo $n ✓  Per chiamarmi: «Ehi $n»."
        stato.setTextColor(tema.accento)
    }

    private fun scheda3D() {
        titolo("Modello 3D del cuore")
        if (!Grafica3D.disponibile) {
            nota("Il 3D ha bisogno di Android 13 o successivo: su questo telefono uso le forme classiche (ASPETTO).")
        } else {
            nota("Disegnati dalla scheda video: luce, riflessi e profondità veri. Reagiscono alla voce e agli umori.")
            val attuale = prefs.getString("forma", "3d_nucleo") ?: "3d_nucleo"
            val opzioni = Grafica3D.MODELLI.map { (id, nome, descrizione) -> Grafica3D.forma(id) to "$nome · $descrizione" } +
                listOf("sfera" to "Nessun 3D: sfera classica")
            scelta(opzioni, attuale) { v ->
                prefs.edit().putString("forma", v).apply()
                cambiato()
            }
            titolo("Regolazioni 3D")
            cursore("Bagliore", 0, 200, prefs.getInt("3d_alone", 100), "%") { v ->
                prefs.edit().putInt("3d_alone", v).apply(); aggiorna()
            }
            cursore("Velocità", 20, 300, prefs.getInt("3d_velocita", 100), "%") { v ->
                prefs.edit().putInt("3d_velocita", v).apply(); aggiorna()
            }
            cursore("Dimensione", 70, 170, prefs.getInt("3d_dimensione", 125), "%") { v ->
                prefs.edit().putInt("3d_dimensione", v).apply(); aggiorna()
            }
        }
        titolo("Colore del nucleo e di tutta l'app")
        nota("Il colore che scegli qui colora il nucleo e insieme tutta l'app: titoli, pulsanti, blocchi, menù.")
        val riga = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val temaAttuale = prefs.getString("tema", "oro") ?: "oro"
        for (p in Tema.PALETTE) {
            val scelto = p.id == temaAttuale && prefs.getInt("r_tonalita", -1) < 0
            val cella = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                contentDescription = p.nome
                setOnClickListener {
                    prefs.edit().putString("tema", p.id).putInt("r_tonalita", -1).apply()
                    cambiato()
                    mostraScheda("3d")
                }
            }
            cella.addView(View(a).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(p.accento)
                    setStroke(dp(if (scelto) 3 else 1), if (scelto) tema.testo else p.sfondo)
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            cella.addView(TextView(a).apply {
                text = p.nome.split(" ").first()
                textSize = 10f
                setTextColor(if (scelto) tema.testo else tema.tenue)
                gravity = Gravity.CENTER
            })
            riga.addView(cella, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        contenuto.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(riga) })
        val tonalita = prefs.getInt("r_tonalita", -1)
        cursore("Tonalità su misura (0 = colore del tema)", 0, 360, if (tonalita < 0) 0 else tonalita, "°",
            allaFine = { cambiato() }) { v ->
            prefs.edit().putInt("r_tonalita", if (v == 0) -1 else v).apply()
            anteprimaColore()
        }
    }

    private fun schedaMenu() {
        titolo("Stile del menù")
        scelta(Personalizza.STILI_MENU, Personalizza.stileMenu(prefs)) { v ->
            prefs.edit().putString("stile_menu", v).apply()
            cambiato()
            mostraScheda("menu")
        }
        if (Personalizza.stileMenu(prefs) == "gesti") {
            titolo("Cosa fa ogni gesto")
            nota("Scorri con decisione sulla sfera. Tocca una riga per cambiare l'azione.")
            for ((dir, nomeDir) in Personalizza.DIREZIONI) {
                ciclo(nomeDir, Personalizza.AZIONI_GESTO, { Personalizza.azioneGesto(prefs, dir) }) { v ->
                    prefs.edit().putString("gesto_$dir", v).apply()
                    cambiato()
                }
            }
        }
        nota("In ogni stile: tieni premuta la sfera per le opzioni, tocca SISTEMA in alto a destra per lo stesso menù.")
    }

    private fun schedaHome() {
        titolo("Dimensione della sfera")
        scelta(Personalizza.DIMENSIONI_SFERA, prefs.getString("sfera_dimensione", "grande") ?: "grande") { v ->
            prefs.edit().putString("sfera_dimensione", v).apply()
            cambiato()
        }
        titolo("Dove stanno i blocchi")
        scelta(listOf("sopra" to "Sopra la sfera", "sotto" to "Sotto la sfera"), if (prefs.getBoolean("blocchi_sopra", true)) "sopra" else "sotto") { v ->
            prefs.edit().putBoolean("blocchi_sopra", v == "sopra").apply()
            cambiato()
        }
        titolo("Blocchi della home")
        nota("Spunta quelli che vuoi vedere; con ▲ ▼ cambi l'ordine. Si toccano per aprirli.")
        val scelti = Personalizza.blocchi(prefs).toMutableList()
        val ordine = scelti + Personalizza.BLOCCHI.map { it.first }.filter { it !in scelti }
        for (id in ordine) {
            val riga = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val c = CheckBox(a).apply {
                text = Personalizza.nomeBlocco(id)
                setTextColor(tema.testo)
                isChecked = id in scelti
                setOnCheckedChangeListener { _, si ->
                    val nuovi = Personalizza.blocchi(prefs).toMutableList()
                    if (si) nuovi.add(id) else nuovi.remove(id)
                    Personalizza.scriviBlocchi(prefs.edit(), nuovi).apply()
                    cambiato()
                    scorri.post { mostraScheda("home") }
                }
            }
            riga.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (id in scelti) {
                riga.addView(freccia("▲", "Sposta su ${Personalizza.nomeBlocco(id)}") { sposta(id, -1) })
                riga.addView(freccia("▼", "Sposta giù ${Personalizza.nomeBlocco(id)}") { sposta(id, 1) })
            }
            contenuto.addView(riga)
        }
    }

    private fun sposta(id: String, verso: Int) {
        val l = Personalizza.blocchi(prefs).toMutableList()
        val i = l.indexOf(id)
        val j = i + verso
        if (i < 0 || j < 0 || j >= l.size) return
        l[i] = l[j]
        l[j] = id
        Personalizza.scriviBlocchi(prefs.edit(), l).apply()
        cambiato()
        mostraScheda("home")
    }

    private fun schedaPulsanti() {
        val scelti = Personalizza.pulsanti(prefs).map { it.id }
        titolo("Pulsanti rapidi · ${scelti.size} di ${Personalizza.MAX_PULSANTI}")
        nota("Compaiono sopra il campo di testo: un tocco e ${Nome.attuale} esegue, senza parlare.")
        for (pu in Personalizza.tuttiIPulsanti(prefs)) {
            contenuto.addView(CheckBox(a).apply {
                text = if (pu.id.startsWith("mio")) "${pu.etichetta}  ·  «${pu.frase}»" else pu.etichetta
                setTextColor(tema.testo)
                isChecked = pu.id in scelti
                setOnCheckedChangeListener { box, si ->
                    val attuali = Personalizza.pulsanti(prefs).map { it.id }.toMutableList()
                    if (si) {
                        if (attuali.size >= Personalizza.MAX_PULSANTI) {
                            box.isChecked = false
                            Toast.makeText(a, "Al massimo ${Personalizza.MAX_PULSANTI} pulsanti: togline uno.", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                        attuali.add(pu.id)
                    } else attuali.remove(pu.id)
                    Personalizza.scriviPulsanti(prefs.edit(), attuali).apply()
                    cambiato()
                    mostraScheda("pulsanti")
                }
            })
        }
        titolo("Inventa un pulsante")
        nota("Scrivi cosa compare sul pulsante e la frase che ${Nome.attuale} deve eseguire, per esempio «Buonanotte» e «spegni le luci e metti la sveglia alle 7».")
        val etichetta = campo("Testo del pulsante")
        val frase = campo("Frase da eseguire")
        bottone("Aggiungi pulsante") {
            val e = etichetta.text.toString().trim()
            val f = frase.text.toString().trim()
            if (e.isEmpty() || f.isEmpty()) {
                Toast.makeText(a, "Scrivi il testo e la frase.", Toast.LENGTH_SHORT).show()
                return@bottone
            }
            val miei = Personalizza.pulsantiMiei(prefs).map { it.etichetta to it.frase } + (e.take(24) to f.take(200))
            val ed = Personalizza.scriviPulsantiMiei(prefs.edit(), miei)
            val attuali = Personalizza.pulsanti(prefs).map { it.id }
            if (attuali.size < Personalizza.MAX_PULSANTI) Personalizza.scriviPulsanti(ed, attuali + "mio${miei.size - 1}")
            ed.apply()
            cambiato()
            mostraScheda("pulsanti")
        }
        val miei = Personalizza.pulsantiMiei(prefs)
        if (miei.isNotEmpty()) {
            titolo("I tuoi pulsanti")
            miei.forEachIndexed { i, pu ->
                bottone("Elimina «${pu.etichetta}»") {
                    val resto = miei.filterIndexed { k, _ -> k != i }
                    // gli id dei pulsanti miei sono per posizione: li riallinea
                    val scelti = Personalizza.pulsanti(prefs).map { it.id }.mapNotNull { id ->
                        if (!id.startsWith("mio")) id else {
                            val k = id.removePrefix("mio").toIntOrNull() ?: return@mapNotNull null
                            when {
                                k == i -> null
                                k > i -> "mio${k - 1}"
                                else -> id
                            }
                        }
                    }
                    val ed = Personalizza.scriviPulsantiMiei(prefs.edit(), resto.map { it.etichetta to it.frase })
                    Personalizza.scriviPulsanti(ed, scelti).apply()
                    cambiato()
                    mostraScheda("pulsanti")
                }
            }
        }
    }

    private fun schedaSfondo() {
        titolo("Sfondo")
        val haFoto = Personalizza.fileFoto(a).exists()
        val opzioni = Tema.SFONDI.filter { it.first != "foto" || haFoto }
        scelta(opzioni, prefs.getString("sfondo", "nessuno") ?: "nessuno") { v ->
            prefs.edit().putString("sfondo", v).apply()
            cambiato()
        }
        bottone(if (haFoto) "Cambia la tua foto…" else "Scegli una tua foto…") { scegliFoto() }
        titolo("Foto")
        cursore("Oscura lo sfondo", 0, 90, Personalizza.oscura(prefs), "%") { v ->
            prefs.edit().putInt("sfondo_oscura", v).apply()
            aggiorna()
        }
        cursore("Sfocatura", 0, 100, Personalizza.sfocatura(prefs), "%") { v ->
            prefs.edit().putInt("sfondo_sfoca", v).apply()
            aggiorna()
        }
        casella("Cambia sfondo ogni giorno", prefs.getBoolean("sfondo_giornaliero", false)) { si ->
            prefs.edit().putBoolean("sfondo_giornaliero", si).apply()
            cambiato()
        }
    }

    private fun schedaSuoni() {
        titolo("Quando ${Nome.attuale} si sveglia")
        scelta(Suoni.SUONI, prefs.getString("suono_sveglia", "bip") ?: "bip") { v ->
            prefs.edit().putString("suono_sveglia", v).apply()
            Suoni.suona(a, v)
            cambiato()
        }
        titolo("Vibrazione")
        scelta(Suoni.VIBRAZIONI, prefs.getString("vibrazione_sveglia", "breve") ?: "breve") { v ->
            prefs.edit().putString("vibrazione_sveglia", v).apply()
            Suoni.vibra(a, v)
            cambiato()
        }
        titolo("Altri suoni")
        for ((info, predefinito) in Suoni.INTERRUTTORI) {
            casella("${info.second} · ${info.third}", prefs.getBoolean(info.first, predefinito)) { si ->
                prefs.edit().putBoolean(info.first, si).apply()
                if (si) when (info.first) {
                    "suoni_fine" -> Suoni.suona(a, "fine")
                    "suoni_trofeo" -> Suoni.suona(a, "fanfara")
                    "suoni_pensa" -> Suoni.suona(a, "tic")
                    "suoni_conferma" -> Suoni.vibra(a, "conferma")
                }
                cambiato()
            }
        }
        cursore("Volume dei suoni di ${Nome.attuale}", 0, 100, prefs.getInt("suoni_volume", 45), "%", allaFine = { Suoni.suona(a, "bip") }) { v ->
            prefs.edit().putInt("suoni_volume", v).apply()
            cambiato()
        }
    }

    // ================================================================
    //  MATTONCINI DELL'INTERFACCIA
    // ================================================================

    private fun hud(t: String, dimensione: Float, colore: Int) = TextView(a).apply {
        text = t
        textSize = dimensione
        setTextColor(colore)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.15f
    }

    private fun pulsante(t: String, pieno: Boolean, azione: () -> Unit) = hud(t, 12f, if (pieno) tema.sfondo else tema.testo).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(23).toFloat()
            if (pieno) setColor(tema.accento) else setStroke(dp(1), Tema.conAlfa(tema.accento, 150))
        }
        setOnClickListener { azione() }
    }

    private fun titolo(t: String) = contenuto.addView(hud(t.uppercase(Locale.ITALIAN), 11f, tema.accento).apply {
        setPadding(0, dp(14), 0, dp(4))
    })

    private fun nota(t: String) = contenuto.addView(TextView(a).apply {
        text = t
        textSize = 12.5f
        setTextColor(tema.tenue)
        setPadding(0, dp(2), 0, dp(4))
    })

    private fun scelta(opzioni: List<Pair<String, String>>, attuale: String, cambia: (String) -> Unit) {
        val gruppo = RadioGroup(a)
        val bottoni = opzioni.map { (_, testo) ->
            RadioButton(a).apply {
                text = testo
                setTextColor(tema.testo)
                id = View.generateViewId()
            }
        }
        bottoni.forEach { gruppo.addView(it) }
        val i = opzioni.indexOfFirst { it.first == attuale }.coerceAtLeast(0)
        gruppo.check(bottoni[i].id)
        gruppo.setOnCheckedChangeListener { _, scelto ->
            val k = bottoni.indexOfFirst { it.id == scelto }
            if (k >= 0) cambia(opzioni[k].first)
        }
        contenuto.addView(gruppo)
    }

    private fun casella(t: String, attiva: Boolean, cambia: (Boolean) -> Unit) = contenuto.addView(CheckBox(a).apply {
        text = t
        setTextColor(tema.testo)
        isChecked = attiva
        setOnCheckedChangeListener { _, si -> cambia(si) }
    })

    private fun cursore(t: String, minimo: Int, massimo: Int, valore: Int, unita: String, allaFine: (() -> Unit)? = null, cambia: (Int) -> Unit) {
        val etichetta = TextView(a).apply {
            setTextColor(tema.testo)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            text = "$t: $valore$unita"
        }
        contenuto.addView(etichetta)
        contenuto.addView(SeekBar(a).apply {
            max = massimo - minimo
            progress = (valore - minimo).coerceIn(0, massimo - minimo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, utente: Boolean) {
                    etichetta.text = "$t: ${p + minimo}$unita"
                    if (utente) cambia(p + minimo)
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) { allaFine?.invoke() }
            })
        })
    }

    private fun ciclo(etichetta: String, opzioni: List<Pair<String, String>>, attuale: () -> String, imposta: (String) -> Unit) {
        val b = TextView(a).apply {
            setTextColor(tema.testo)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        fun scrivi() { b.text = "$etichetta  →  ${opzioni.firstOrNull { it.first == attuale() }?.second ?: attuale()}  ▸" }
        scrivi()
        b.setOnClickListener {
            val i = opzioni.indexOfFirst { it.first == attuale() }
            imposta(opzioni[(i + 1) % opzioni.size].first)
            scrivi()
        }
        contenuto.addView(b)
    }

    private fun campo(suggerimento: String) = EditText(a).apply {
        hint = suggerimento
        setHintTextColor(tema.tenue)
        setTextColor(tema.testo)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }.also { contenuto.addView(it) }

    private fun bottone(t: String, azione: () -> Unit) = contenuto.addView(pulsante(t, false, azione).apply {
        textSize = 12f
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(6)
    })

    private fun freccia(t: String, descrizione: String, azione: () -> Unit) = hud(t, 16f, tema.accento).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        contentDescription = descrizione
        setOnClickListener { azione() }
    }
}
