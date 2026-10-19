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

    // ---------- giochi a voce ----------

    @Test fun f01_impiccato() {
        Giochi.termina(ctx)
        assertTrue(chiedi("giochiamo all'impiccato").contains("impiccato"))
        Giochi.avviaImpiccato(ctx, "", "CASA")
        var r = chiedi("la c")
        assertTrue(r, r.contains("c'è"))
        r = chiedi("a")
        assertTrue(r, r.contains("2 A"))
        r = chiedi("erre")
        assertTrue(r, r.contains("non c'è") && r.contains("1 di"))
        r = chiedi("esse")
        assertTrue(r, r.contains("Bravissimo"))
        r = chiedi("no")
        assertTrue(r, r.startsWith("Va bene"))
        assertTrue(Giochi.attivo(ctx) == null)
    }

    @Test fun f02_letteraDetta() {
        assertEquals('R', Giochi.estraiLettera("erre"))
        assertEquals('B', Giochi.estraiLettera("la lettera b"))
        assertEquals('T', Giochi.estraiLettera("provo con la t"))
        assertEquals('R', Giochi.estraiLettera("r come roma"))
        assertEquals('E', Giochi.estraiLettera(Giochi.norm("è")))
    }

    @Test fun f03_indovinello() {
        Giochi.termina(ctx)
        val primo = Giochi.avviaIndovinello(ctx, 0)
        assertTrue(primo.contains("denti"))
        assertTrue(chiedi("una forchetta").startsWith("No"))
        assertTrue(chiedi("il pettine").startsWith("Esatto"))
        assertTrue(chiedi("basta giocare").contains("fine del gioco"))
        assertTrue(Giochi.attivo(ctx) == null)
    }

    @Test fun f04_quizSenzaInternet() {
        Giochi.termina(ctx)
        var r = chiedi("facciamo un quiz")
        assertTrue(r, r.contains("Domanda 1 di"))
        var giri = 0
        while (Giochi.attivo(ctx)?.optString("tipo") == "quiz" && giri < 10) { r = chiedi("a"); giri++ }
        assertTrue(r, r.contains("Quiz finito"))
        chiedi("no")
    }

    @Test fun f05_comandiDuranteIlGioco() {
        Giochi.termina(ctx)
        Giochi.avviaImpiccato(ctx, "", "MARE")
        assertTrue(chiedi("che ore sono").startsWith("Sono le"))
        assertEquals("impiccato", Giochi.attivo(ctx)?.optString("tipo"))
        assertTrue(chiedi("ripeti").isNotBlank())
        assertTrue(chiedi("basta giocare").contains("MARE"))
    }

    @Test fun f06_trofeiELivelli() {
        Trofei.azzera(ctx)
        assertEquals(Triple(1, 0, 100), Trofei.livello(0))
        assertEquals(Triple(2, 0, 150), Trofei.livello(100))
        assertEquals(Triple(3, 0, 200), Trofei.livello(250))
        Trofei.evento(ctx, "richieste", 2)
        assertTrue(Trofei.sbloccati(ctx).contains("prima"))
        assertTrue(Trofei.punti(ctx) >= 27)
        assertTrue(Trofei.sfida(ctx).isNotBlank())
        assertEquals(1, Trofei.serie(ctx))
    }

    @Test fun f07_pulsantiRapidi() {
        val e = prefs.edit()
        Personalizza.scriviPulsantiMiei(e, listOf("Buonanotte" to "spegni la torcia"))
        e.putString("pulsanti", "torcia,meteo,zitto,timer,agenda,ripeti,dove,mio0").commit()
        assertEquals(6, Personalizza.pulsanti(prefs).size)
        prefs.edit().putString("pulsanti", "mio0,meteo").commit()
        assertEquals("Buonanotte", Personalizza.pulsanti(prefs)[0].etichetta)
        prefs.edit().remove("pulsanti").remove("pulsanti_miei").commit()
    }

    @Test fun f08_provaEAnnulla() {
        prefs.edit().putString("stile_menu", "radiale").remove("blocchi").commit()
        val foto = Personalizza.istantanea(prefs)
        prefs.edit().putString("stile_menu", "dock").putString("blocchi", "meteo,batteria").commit()
        assertEquals("dock", Personalizza.stileMenu(prefs))
        Personalizza.ripristina(prefs, foto)
        assertEquals("radiale", Personalizza.stileMenu(prefs))
        assertTrue(Personalizza.blocchi(prefs).isEmpty())
    }

    @Test fun f09_suoni() {
        for (n in listOf("bip", "campana", "ronzio", "fine", "tic", "fanfara", "errore", "vittoria")) {
            assertTrue(n, (Suoni.genera(n)?.size ?: 0) > 100)
        }
        Suoni.suona(ctx, "bip")
        Suoni.vibra(ctx, "doppia")
        Suoni.nota(ctx, 440.0, 80)
        Thread.sleep(500)
    }

    @Test fun f10_umore() {
        prefs.edit().putBoolean("vivo_umori", true).putString("umore_fisso", "auto").commit()
        Umore.scossa(ctx)
        Umore.scossa(ctx)
        val terza = Umore.scossa(ctx)
        assertTrue(terza, terza.contains("offes") || terza.contains("broncio") || terza.contains("muso"))
        assertEquals("offeso", Umore.attuale(ctx))
        Umore.imposta("calmo", 0)
    }

    // ---------- schermata ----------

    @Test fun e01_schermataSiApreSenzaErrori() {
        JarvisApp.errore(ctx).delete()
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4000)
        }
        assertFalse("errore registrato: " + (if (JarvisApp.errore(ctx).exists()) JarvisApp.errore(ctx).readText() else ""), JarvisApp.errore(ctx).exists())
    }

    private fun senzaErrori() =
        assertFalse("errore registrato: " + (if (JarvisApp.errore(ctx).exists()) JarvisApp.errore(ctx).readText() else ""), JarvisApp.errore(ctx).exists())

    @Test fun e02_stiliDiMenuEBlocchi() {
        JarvisApp.errore(ctx).delete()
        for (stile in listOf("dock", "tessere", "gesti", "radiale")) {
            prefs.edit().putString("stile_menu", stile)
                .putString("blocchi", "meteo,batteria,agenda,promemoria,gioco,trofei,sfida,spesa")
                .putBoolean("blocchi_sopra", stile != "tessere")
                .putString("sfera_dimensione", if (stile == "dock") "piccola" else "media")
                .putString("pulsanti", "torcia,meteo,zitto,gioco")
                .putString("sfondo", "onde")
                .commit()
            ActivityScenario.launch(MainActivity::class.java).use { Thread.sleep(2500) }
        }
        prefs.edit().remove("stile_menu").remove("blocchi").remove("pulsanti").remove("sfondo").remove("sfera_dimensione").commit()
        senzaErrori()
    }

    @Test fun e03_pagineNuove() {
        JarvisApp.errore(ctx).delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            for (scheda in listOf("menu", "home", "pulsanti", "sfondo", "suoni")) {
                scenario.onActivity { a ->
                    val m = MainActivity::class.java.getDeclaredMethod("mostraPersonalizza", String::class.java)
                    m.isAccessible = true
                    m.invoke(a, scheda)
                }
                Thread.sleep(400)
            }
            for (nome in listOf("mostraGiochi", "mostraTrofei", "mostraTessere", "mostraImpostazioni", "mostraAspetto")) {
                scenario.onActivity { a ->
                    val m = MainActivity::class.java.getDeclaredMethod(nome)
                    m.isAccessible = true
                    m.invoke(a)
                }
                Thread.sleep(400)
            }
        }
        senzaErrori()
    }

    @Test fun e04_minigiochi() {
        JarvisApp.errore(ctx).delete()
        for ((id, _, _) in MinigiochiActivity.GIOCHI) {
            val intento = android.content.Intent(ctx, MinigiochiActivity::class.java).putExtra("gioco", id)
            ActivityScenario.launch<MinigiochiActivity>(intento).use { Thread.sleep(1500) }
        }
        ActivityScenario.launch(MinigiochiActivity::class.java).use { Thread.sleep(800) }
        senzaErrori()
    }
}
