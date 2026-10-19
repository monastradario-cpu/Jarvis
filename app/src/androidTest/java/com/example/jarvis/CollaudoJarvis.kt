package com.example.jarvis

import android.Manifest
import android.content.Context
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Collaudo automatico di Jarvis su un telefono Android simulato.
 * Gira prima di ogni pubblicazione: se un test fallisce, l'APK non viene pubblicato.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CollaudoJarvis {

    @get:Rule
    val permessi: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private fun cervello() = Cervello.prendi(ctx)
    private fun chiedi(t: String) = cervello().rispondi(t).testo

    @Before
    fun prepara() {
        prefs.edit().putBoolean("comandi_rapidi", true).putBoolean("interrompibile", false).commit()
    }

    // ---------- comandi istantanei ----------

    @Test fun a01_ora() = assertTrue(chiedi("che ore sono").startsWith("Sono le"))

    @Test fun a02_data() = assertTrue(chiedi("che giorno è oggi").startsWith("Oggi è"))

    @Test fun a03_volume() = assertTrue(chiedi("volume al 50").contains("Volume al"))

    @Test fun a04_batteria() = assertTrue(chiedi("quanta batteria ho").startsWith("Batteria al"))

    @Test fun a05_grazie() = assertTrue(chiedi("grazie").isNotBlank())

    @Test fun a06_ripeti() {
        val prima = chiedi("che ore sono")
        assertEquals(prima, chiedi("ripeti"))
    }

    // ---------- liste e catene ----------

    @Test fun b01_lista() {
        chiedi("svuota la lista della spesa")
        chiedi("aggiungi il latte alla lista della spesa")
        assertTrue(Liste.leggi(ctx, "spesa").contains("latte"))
        chiedi("togli il latte dalla lista della spesa")
        assertFalse(Liste.leggi(ctx, "spesa").contains("latte"))
    }

    @Test fun b02_catena() {
        val r = chiedi("volume al 30 e che ore sono")
        assertTrue(r, r.contains("Volume al") && r.contains("Sono le"))
    }

    @Test fun b03_catenaNonCapitaNonEsegueNiente() {
        chiedi("svuota la lista della spesa")
        // la seconda parte non è un comando: non deve eseguire nemmeno la prima
        chiedi("aggiungi il pane alla lista della spesa e raccontami una barzelletta sui gatti")
        assertFalse(Liste.leggi(ctx, "spesa").contains("pane"))
    }

    // ---------- memoria e privacy ----------

    @Test fun c01_imparaDaSolo() {
        chiedi("mi chiamo Dario")
        assertTrue(cervello().leggiLista("memoria").any { it.contains("Dario") })
    }

    @Test fun c02_modalitaPrivata() {
        chiedi("modalità privata")
        assertTrue(cervello().privata)
        chiedi("esci dalla modalità privata")
        chiedi("che ore sono")
        assertFalse(cervello().privata)
    }

    // ---------- voce ----------

    @Test fun d01_velocita() {
        prefs.edit().putFloat("voce_velocita", 1f).commit()
        chiedi("parla più lentamente")
        assertTrue(prefs.getFloat("voce_velocita", 1f) < 1f)
        chiedi("parla normale")
        assertEquals(1f, prefs.getFloat("voce_velocita", 0f), 0.01f)
    }

    @Test fun d02_voceDelTelefono() {
        prefs.edit().putString("motore_voce", "neurale").commit()
        chiedi("voce del telefono")
        assertEquals("android", prefs.getString("motore_voce", ""))
    }

    @Test fun d03_scaricaVoceNeurale() {
        val paola = VoceNeurale.VOCI.first { it.nome.startsWith("Paola") }
        if (!VoceNeurale.scaricata(ctx, paola.id)) {
            val esito = VoceNeurale.scarica(ctx, paola.id) { }
            assertTrue(esito, VoceNeurale.scaricata(ctx, paola.id))
        }
    }

    @Test fun d04_chiediVoceNeurale() {
        val r = chiedi("usa la voce di paola")
        assertTrue(r, r.contains("Paola"))
        assertEquals("neurale", prefs.getString("motore_voce", ""))
        assertTrue(VoceNeurale.attiva(ctx))
    }

    @Test fun d05_voceNeuraleParla() {
        prefs.edit().putString("motore_voce", "neurale").putString("voce_neurale", VoceNeurale.VOCI.first { it.nome.startsWith("Paola") }.id).commit()
        val fine = CountDownLatch(1)
        var errore = false
        val ascolto = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { fine.countDown() }
            @Deprecated("vecchie versioni") override fun onError(id: String?) { errore = true; fine.countDown() }
        }
        VoceNeurale.parla(ctx, "Buongiorno. Questa è una prova della voce neurale.", true, "prova", ascolto)
        assertTrue("la voce neurale non ha finito di parlare", fine.await(90, TimeUnit.SECONDS))
        assertFalse("errore della voce neurale: ${VoceNeurale.ultimoErrore}", errore)
        assertFalse(prefs.getBoolean("neurale_guasta", false))
    }

    @Test fun d06_parlatoreConVoceNeurale() {
        prefs.edit().putString("motore_voce", "neurale").commit()
        val fine = CountDownLatch(1)
        lateinit var parlatore: Parlatore
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            parlatore = Parlatore(ctx, prefs, alTermine = { fine.countDown() }, alInterrotto = { fine.countDown() })
            parlatore.ascoltatore = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { parlatore.pezzoFinito(id) }
                @Deprecated("vecchie versioni") override fun onError(id: String?) { parlatore.pezzoFinito(id) }
            }
            parlatore.di("Prima frase della prova. Seconda frase, un po' più lunga, per vedere la coda.")
        }
        assertTrue("il Parlatore non ha finito", fine.await(120, TimeUnit.SECONDS))
    }

    @Test fun d07_cambiaVoceProssima() {
        val r = chiedi("cambia voce")
        assertTrue(r, r.isNotBlank())
    }

    // ---------- schermata ----------

    @Test fun e01_schermataSiApreSenzaErrori() {
        JarvisApp.errore(ctx).delete()
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4000)
        }
        assertFalse("errore registrato: " + (if (JarvisApp.errore(ctx).exists()) JarvisApp.errore(ctx).readText() else ""), JarvisApp.errore(ctx).exists())
    }
}
