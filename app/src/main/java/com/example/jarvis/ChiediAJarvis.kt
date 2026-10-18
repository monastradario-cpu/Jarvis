package com.example.jarvis

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Condividi → "Jarvis: chiedi". Da qualsiasi app si manda a Jarvis un testo, un link,
 * una foto, un PDF o un messaggio vocale: lui lo riassume, lo traduce o risponde alle domande.
 */
class ChiediAJarvis : Activity() {

    private lateinit var tema: Tema.Palette
    private lateinit var risultato: TextView
    private lateinit var stato: TextView
    private var tts: TextToSpeech? = null
    private var contenuto: Cervello.Contenuto? = null
    @Volatile private var occupato = false
    private var ultimaRisposta = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tema = Tema.attuale(getSharedPreferences("jarvis", MODE_PRIVATE))
        window.statusBarColor = tema.sfondo
        window.navigationBarColor = tema.sfondo

        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tema.sfondo)
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        radice.addView(hud("JARVIS · CONDIVISO", 14f))
        stato = TextView(this).apply {
            setTextColor(tema.tenue)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        radice.addView(stato)

        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        riga.addView(pulsante("RIASSUMI") { chiedi("Riassumi in modo chiaro i punti principali, in italiano.") })
        riga.addView(pulsante("TRADUCI") { chiedi("Traduci in italiano. Se è già in italiano, traducilo in inglese.") })
        riga.addView(pulsante("SPIEGA") { chiedi("Spiegami in parole semplici di cosa si tratta e cosa è importante sapere.") })
        radice.addView(riga)

        val campo = EditText(this).apply {
            hint = "Chiedi qualcosa su questo contenuto…"
            setHintTextColor(tema.tenue)
            setTextColor(tema.testo)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Tema.conAlfa(tema.accento, 160))
                setColor(tema.superficie)
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        campo.setOnEditorActionListener { _, azione, _ ->
            if (azione == EditorInfo.IME_ACTION_SEND && campo.text.isNotBlank()) {
                chiedi(campo.text.toString())
                campo.setText("")
                true
            } else false
        }
        radice.addView(campo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        risultato = TextView(this).apply {
            setTextColor(tema.testo)
            textSize = 16f
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setTextIsSelectable(true)
            setPadding(0, dp(14), 0, dp(14))
        }
        radice.addView(ScrollView(this).apply { addView(risultato) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val sotto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sotto.addView(pulsante("COPIA") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis", ultimaRisposta))
            stato.text = "Copiato."
        })
        sotto.addView(pulsante("CONDIVIDI") {
            if (ultimaRisposta.isNotBlank()) startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ultimaRisposta), "Condividi"
            ))
        })
        sotto.addView(pulsante("ZITTO") { tts?.stop() })
        sotto.addView(pulsante("CHIUDI") { finish() })
        radice.addView(sotto)
        setContentView(radice)

        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) tts?.let { Voce.applica(it, getSharedPreferences("jarvis", MODE_PRIVATE)) }
        }
        leggiIntento()
    }

    private fun hud(t: String, dimensione: Float) = TextView(this).apply {
        text = t
        setTextColor(tema.accento)
        textSize = dimensione
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.25f
    }

    private fun pulsante(t: String, azione: () -> Unit) = hud(t, 11f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(12), dp(4), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Tema.conAlfa(tema.accento, 160))
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(6)
        }
        setOnClickListener { azione() }
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ================================================================
    //  COSA È STATO CONDIVISO
    // ================================================================

    private fun nomeFile(uri: Uri): String {
        var nome = "file"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) nome = it.getString(0) ?: nome
            }
        } catch (e: Exception) { }
        return nome
    }

    @Suppress("DEPRECATION")
    private fun leggiIntento() {
        val i = intent ?: return finish()
        val testo = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        stato.text = "Preparo il contenuto..."
        Thread {
            val c: Cervello.Contenuto? = try {
                when {
                    uri != null -> {
                        val tipo = contentResolver.getType(uri) ?: i.type ?: "application/octet-stream"
                        val dati = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        when {
                            dati.size > 19_000_000 -> null
                            tipo.startsWith("text/") -> Cervello.Contenuto("testo", nomeFile(uri), String(dati, Charsets.UTF_8), null, tipo)
                            else -> Cervello.Contenuto(
                                when {
                                    tipo.startsWith("image/") -> "immagine"
                                    tipo.startsWith("audio/") -> "audio"
                                    tipo == "application/pdf" -> "pdf"
                                    tipo.startsWith("video/") -> "video"
                                    else -> "file"
                                },
                                nomeFile(uri), "", Base64.encodeToString(dati, Base64.NO_WRAP), tipo
                            )
                        }
                    }
                    Regex("^https?://\\S+$").matches(testo) || (testo.length < 400 && Regex("https?://\\S+").containsMatchIn(testo)) -> {
                        val link = Regex("https?://\\S+").find(testo)!!.value
                        Cervello.Contenuto("link", link, Cervello.prendi(this).testoDellaPagina(link), null, "text/html")
                    }
                    testo.isNotEmpty() -> Cervello.Contenuto("testo", "testo", testo, null, "text/plain")
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                contenuto = c
                stato.text = when (c?.tipo) {
                    null -> "Non riesco a leggere questo contenuto (troppo grande o non supportato)."
                    "link" -> "Pagina web: ${c.titolo}"
                    "immagine" -> "Immagine: ${c.titolo}"
                    "pdf" -> "PDF: ${c.titolo}"
                    "audio" -> "Audio: ${c.titolo}"
                    "video" -> "Video: ${c.titolo}"
                    else -> "Testo: ${c.testo.take(80)}${if (c.testo.length > 80) "…" else ""}"
                }
                if (c != null) {
                    // un messaggio vocale si trascrive e riassume subito; per il resto si sceglie
                    if (c.tipo == "audio") chiedi("Trascrivi questo messaggio vocale e poi riassumilo in una frase.")
                    else risultato.text = "Cosa vuoi che faccia? Tocca RIASSUMI, TRADUCI, SPIEGA oppure fai una domanda."
                }
            }
        }.start()
    }

    private fun chiedi(istruzione: String) {
        val c = contenuto ?: return
        if (occupato) return
        occupato = true
        stato.text = "Ci penso..."
        Thread {
            val r = try {
                Cervello.prendi(this).analizzaContenuto(c, istruzione)
            } catch (e: Exception) {
                "Non ci sono riuscito: ${e.message}"
            }
            runOnUiThread {
                occupato = false
                stato.text = ""
                ultimaRisposta = r
                risultato.text = r
                tts?.speak(Parlatore.pulisci(r).take(3900), TextToSpeech.QUEUE_FLUSH, null, "condiviso")
            }
        }.start()
    }
}
