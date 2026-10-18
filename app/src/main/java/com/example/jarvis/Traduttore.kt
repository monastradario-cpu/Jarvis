package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Traduttore simultaneo: tu parli italiano, l'altra persona la sua lingua.
 * La metà in alto è girata verso di lei, così potete mettere il telefono tra voi due.
 */
class Traduttore : Activity() {

    class Lingua(val codice: String, val nome: String, val nomeLocale: String, val locale: Locale)

    companion object {
        val LINGUE = listOf(
            Lingua("en-US", "inglese", "English", Locale.US),
            Lingua("es-ES", "spagnolo", "Español", Locale("es", "ES")),
            Lingua("fr-FR", "francese", "Français", Locale.FRANCE),
            Lingua("de-DE", "tedesco", "Deutsch", Locale.GERMANY),
            Lingua("pt-PT", "portoghese", "Português", Locale("pt", "PT")),
            Lingua("ro-RO", "rumeno", "Română", Locale("ro", "RO")),
            Lingua("sq-AL", "albanese", "Shqip", Locale("sq", "AL")),
            Lingua("uk-UA", "ucraino", "Українська", Locale("uk", "UA")),
            Lingua("ru-RU", "russo", "Русский", Locale("ru", "RU")),
            Lingua("ar-EG", "arabo", "العربية", Locale("ar", "EG")),
            Lingua("zh-CN", "cinese", "中文", Locale.SIMPLIFIED_CHINESE),
            Lingua("ja-JP", "giapponese", "日本語", Locale.JAPAN),
            Lingua("hi-IN", "hindi", "हिन्दी", Locale("hi", "IN")),
            Lingua("pl-PL", "polacco", "Polski", Locale("pl", "PL")),
            Lingua("nl-NL", "olandese", "Nederlands", Locale("nl", "NL")),
            Lingua("tr-TR", "turco", "Türkçe", Locale("tr", "TR"))
        )
        private val ITALIANO = Lingua("it-IT", "italiano", "Italiano", Locale.ITALY)
    }

    private lateinit var tema: Tema.Palette
    private lateinit var testoAltro: TextView
    private lateinit var testoMio: TextView
    private lateinit var pulsanteAltro: TextView
    private lateinit var stato: TextView
    private var lingua = LINGUE[0]
    private var tts: TextToSpeech? = null
    private var riconoscitore: SpeechRecognizer? = null
    private val principale = Handler(Looper.getMainLooper())
    @Volatile private var occupato = false
    private var automatico = false
    private var ultimoParlante = ITALIANO

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        tema = Tema.attuale(prefs)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = tema.sfondo
        window.navigationBarColor = tema.sfondo
        val richiesta = intent?.getStringExtra("lingua")?.lowercase(Locale.ITALIAN).orEmpty()
        lingua = LINGUE.firstOrNull { richiesta.isNotEmpty() && (it.nome.startsWith(richiesta.take(4)) || it.codice.startsWith(richiesta)) }
            ?: LINGUE.firstOrNull { it.codice == prefs.getString("traduttore_lingua", "en-US") } ?: LINGUE[0]

        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tema.sfondo)
            setPadding(dp(16), dp(30), dp(16), dp(20))
        }
        // metà dell'altra persona, capovolta
        val alto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            rotation = 180f
        }
        testoAltro = grande("")
        pulsanteAltro = pulsante("") { ascolta(lingua) }
        alto.addView(testoAltro, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        alto.addView(pulsanteAltro)
        radice.addView(alto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        centro.addView(hud("⇅ TRADUTTORE", 12f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        centro.addView(hud("LINGUA ▾", 11f).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { scegliLingua() } })
        centro.addView(hud("AUTO", 11f).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                automatico = !automatico
                text = if (automatico) "■ AUTO" else "AUTO"
                stato.text = if (automatico) "Automatico: dopo ogni traduzione ascolta l'altra persona." else ""
            }
        })
        centro.addView(hud("CHIUDI", 11f).apply { setPadding(dp(10), dp(6), 0, dp(6)); setOnClickListener { finish() } })
        radice.addView(centro)
        radice.addView(android.view.View(this).apply { setBackgroundColor(Tema.conAlfa(tema.accento, 120)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        stato = TextView(this).apply {
            setTextColor(tema.tenue)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        radice.addView(stato)

        val basso = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        testoMio = grande("Tocca ITALIANO e parla: traduco per l'altra persona. Lei tocca il suo pulsante, capovolto in alto.")
        basso.addView(testoMio, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        basso.addView(pulsante("🎙 ITALIANO") { ascolta(ITALIANO) })
        radice.addView(basso, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)
        aggiornaLingua()

        tts = TextToSpeech(this) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        principale.post {
                            // in automatico, dopo aver tradotto ascolta l'altra persona
                            if (automatico) principale.postDelayed({ ascolta(if (ultimoParlante == ITALIANO) lingua else ITALIANO) }, 300)
                        }
                    }
                    @Deprecated("Richiesto dalle versioni vecchie di Android")
                    override fun onError(id: String?) {}
                })
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PAUSA)
    }

    override fun onPause() {
        automatico = false
        try { riconoscitore?.cancel() } catch (e: Exception) { }
        if (!MainActivity.visibile) ServizioJarvis.invia(this, ServizioJarvis.AZIONE_RIPRENDI)
        super.onPause()
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) { }
        try { riconoscitore?.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun hud(t: String, dimensione: Float) = TextView(this).apply {
        text = t
        setTextColor(tema.accento)
        textSize = dimensione
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.2f
    }

    private fun grande(t: String) = TextView(this).apply {
        text = t
        setTextColor(tema.testo)
        textSize = 22f
        typeface = Typeface.create("serif", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }

    private fun pulsante(t: String, azione: () -> Unit) = hud(t, 14f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(2), Tema.conAlfa(tema.accento, 200))
            setColor(tema.superficie)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener { azione() }
    }

    private fun aggiornaLingua() {
        pulsanteAltro.text = "🎙 ${lingua.nomeLocale.uppercase(lingua.locale)}"
        if (testoAltro.text.isNullOrBlank()) testoAltro.text = "${lingua.nomeLocale}: tap the button and speak."
    }

    private fun scegliLingua() {
        AlertDialog.Builder(this)
            .setTitle("Lingua dell'altra persona")
            .setItems(LINGUE.map { "${it.nome.replaceFirstChar { c -> c.uppercase() }} · ${it.nomeLocale}" }.toTypedArray()) { _, i ->
                lingua = LINGUE[i]
                getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("traduttore_lingua", lingua.codice).apply()
                testoAltro.text = ""
                aggiornaLingua()
            }
            .show()
    }

    private fun ascolta(chi: Lingua) {
        if (occupato) return
        try { tts?.stop() } catch (e: Exception) { }
        if (riconoscitore == null) riconoscitore = Orecchie.crea(this)
        riconoscitore?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { stato.text = "Ascolto (${chi.nome})..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { stato.text = "Traduco..." }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (chi == ITALIANO) testoMio.text = p else testoAltro.text = p
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) { stato.text = ""; return }
                traduci(t, chi)
            }
            override fun onError(error: Int) { stato.text = "Non ho sentito, riprova." }
        })
        val richiesta = Orecchie.richiesta(this, true).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, chi.codice)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, chi.codice)
        }
        riconoscitore?.startListening(richiesta)
    }

    private fun traduci(testo: String, da: Lingua) {
        val a = if (da == ITALIANO) lingua else ITALIANO
        ultimoParlante = da
        occupato = true
        Thread {
            val tradotto = try {
                Cervello.prendi(this).traduci(testo, da.nome, a.nome)
            } catch (e: Exception) {
                "(${e.message})"
            }
            principale.post {
                occupato = false
                stato.text = ""
                // chi parla vede il proprio testo, l'altro la traduzione
                if (da == ITALIANO) {
                    testoMio.text = testo
                    testoAltro.text = tradotto
                } else {
                    testoAltro.text = testo
                    testoMio.text = tradotto
                }
                tts?.let { voce ->
                    voce.setLanguage(a.locale)
                    voce.setSpeechRate(1f)
                    voce.speak(tradotto, TextToSpeech.QUEUE_FLUSH, null, "traduzione")
                }
            }
        }.start()
    }
}
