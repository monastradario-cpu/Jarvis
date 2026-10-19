package com.example.jarvis

import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Impostazioni della voce di Jarvis, condivise tra la schermata e il servizio. */
object Voce {

    const val TONO_PREDEFINITO = 1f
    const val VELOCITA_PREDEFINITA = 1f

    /** La sintesi vocale di Android in uso (per elencare le voci quando le cambi a voce). */
    @Volatile var ttsAttivo: TextToSpeech? = null

    /**
     * Cambia voce, velocità o tono su richiesta ("usa la voce di Paola", "voce maschile",
     * "cambia voce", "parla più lentamente", "voce più grave"). Restituisce cosa dire.
     */
    fun cambia(ctx: android.content.Context, prefs: SharedPreferences, voce: String, velocita: String, tono: String): String {
        val esiti = mutableListOf<String>()
        val v = voce.lowercase(Locale.ITALIAN).trim()
        if (v.isNotEmpty()) esiti.add(cambiaVoce(ctx, prefs, v))
        val vel = velocita.lowercase(Locale.ITALIAN)
        if (vel.isNotBlank()) {
            val attuale = prefs.getFloat("voce_velocita", VELOCITA_PREDEFINITA)
            val nuova = when {
                vel.contains("lent") || vel.contains("pian") || vel.contains("meno") -> attuale - 0.15f
                vel.contains("veloc") || vel.contains("rapid") || vel.contains("più") -> attuale + 0.15f
                else -> VELOCITA_PREDEFINITA
            }.coerceIn(0.5f, 1.8f)
            prefs.edit().putFloat("voce_velocita", nuova).apply()
            esiti.add("Velocità al ${(nuova * 100).toInt()} per cento.")
        }
        val t = tono.lowercase(Locale.ITALIAN)
        if (t.isNotBlank()) {
            val attuale = prefs.getFloat("voce_tono", TONO_PREDEFINITO)
            val nuovo = when {
                t.contains("grav") || t.contains("bass") || t.contains("profond") -> attuale - 0.1f
                t.contains("acut") || t.contains("alt") -> attuale + 0.1f
                else -> TONO_PREDEFINITO
            }.coerceIn(0.5f, 1.6f)
            prefs.edit().putFloat("voce_tono", nuovo).apply()
            esiti.add(if (prefs.getString("motore_voce", "android") == "neurale") "Il tono si regola solo con la voce del telefono, ma l'ho salvato."
                else "Tono al ${(nuovo * 100).toInt()} per cento.")
        }
        return esiti.joinToString(" ").ifBlank { "Dimmi quale voce vuoi: Miro, Paola, Riccardo, Dii, oppure una voce maschile o femminile del telefono." }
    }

    private fun cambiaVoce(ctx: android.content.Context, prefs: SharedPreferences, v: String): String {
        // voci neurali per nome
        val neurale = VoceNeurale.VOCI.firstOrNull { m -> v.contains(m.nome.substringBefore(":").lowercase(Locale.ITALIAN)) }
        if (neurale != null) return usaNeurale(ctx, prefs, neurale)
        val voci = ttsAttivo?.let { vociItaliane(it) } ?: emptyList()
        val etichette = voci.mapIndexed { i, x -> etichetta(x, i + 1) }
        val voluta = when {
            v.contains("femmin") || v.contains("donna") -> etichette.indexOfFirst { it.contains("femminile") }
            v.contains("maschil") || v.contains("uomo") -> etichette.indexOfFirst { it.contains("maschile") }
            v.contains("google") || v.contains("telefono") || v.contains("normale") || v.contains("predefinit") -> -2
            Regex("\\d+").containsMatchIn(v) -> (Regex("\\d+").find(v)!!.value.toInt() - 1).coerceIn(0, (voci.size - 1).coerceAtLeast(0))
            else -> -1     // "cambia voce", "un'altra": la prossima
        }
        if (voluta == -2 || (voluta >= 0 && voluta < voci.size)) {
            val e = prefs.edit().putString("motore_voce", "android")
            if (voluta >= 0) e.putString("voce_nome", voci[voluta].name)
            e.apply()
            return "Ecco, adesso parlo con " + (if (voluta >= 0) etichette[voluta].lowercase(Locale.ITALIAN) else "la voce del telefono") + "."
        }
        // la prossima voce disponibile: prima quelle del telefono, poi le neurali scaricate
        val scelte = voci.map { "a:" + it.name } + VoceNeurale.VOCI.filter { VoceNeurale.scaricata(ctx, it.id) }.map { "n:" + it.id }
        if (scelte.isEmpty()) return "Non trovo altre voci: scaricane una neurale dalla pagina VOCE, oppure installa le voci italiane di Google."
        val attuale = if (prefs.getString("motore_voce", "android") == "neurale") "n:" + VoceNeurale.scelta(ctx)
            else "a:" + (prefs.getString("voce_nome", "") ?: "")
        val prossima = scelte[(scelte.indexOf(attuale) + 1) % scelte.size]
        return if (prossima.startsWith("n:")) {
            usaNeurale(ctx, prefs, VoceNeurale.VOCI.first { it.id == prossima.removePrefix("n:") })
        } else {
            val i = voci.indexOfFirst { it.name == prossima.removePrefix("a:") }
            prefs.edit().putString("motore_voce", "android").putString("voce_nome", voci[i].name).apply()
            "Ecco, adesso parlo con la ${etichette[i].lowercase(Locale.ITALIAN)}."
        }
    }

    private fun usaNeurale(ctx: android.content.Context, prefs: SharedPreferences, m: VoceNeurale.Modello): String {
        val nome = m.nome.substringBefore(":")
        prefs.edit().putString("voce_neurale", m.id).apply()
        if (VoceNeurale.scaricata(ctx, m.id)) {
            prefs.edit().putString("motore_voce", "neurale").putBoolean("neurale_guasta", false).apply()
            return "Ecco, adesso parlo con la voce di $nome."
        }
        val app = ctx.applicationContext
        Thread {
            val esito = VoceNeurale.scarica(app, m.id) { }
            if (VoceNeurale.scaricata(app, m.id)) {
                prefs.edit().putString("motore_voce", "neurale").apply()
                ServizioJarvis.dici(app, "Ecco la voce di $nome. Da ora parlo così.")
            } else {
                Proattivo.avvisa(app, "voce_${m.id}", esito)
            }
        }.start()
        return "Scarico la voce di $nome, circa ${m.dimensioneMb} megabyte: tra un minuto parlerò con lei."
    }

    /** Applica voce, tono e velocità scelti nelle impostazioni. */
    fun applica(tts: TextToSpeech, prefs: SharedPreferences) {
        tts.setLanguage(Locale("it", "IT"))
        val nome = prefs.getString("voce_nome", "") ?: ""
        if (nome.isNotEmpty()) {
            try {
                tts.voices?.firstOrNull { it.name == nome }?.let { tts.setVoice(it) }
            } catch (e: Exception) {
                // voce non più disponibile: resta quella predefinita
            }
        }
        tts.setPitch(prefs.getFloat("voce_tono", TONO_PREDEFINITO))
        tts.setSpeechRate(prefs.getFloat("voce_velocita", VELOCITA_PREDEFINITA))
    }

    /** Tutte le voci italiane installate sul telefono. */
    fun vociItaliane(tts: TextToSpeech): List<Voice> = try {
        tts.voices
            ?.filter { it.locale.language == "it" && !it.features.contains("notInstalled") }
            ?.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Nome leggibile: le voci di Android hanno nomi tecnici come it-it-x-itd-local. */
    fun etichetta(voce: Voice, numero: Int): String {
        val n = voce.name.lowercase(Locale.ROOT)
        val genere = when {
            n.contains("male") && !n.contains("female") -> ", maschile"
            n.contains("female") -> ", femminile"
            n.contains("itc") || n.contains("itd") -> ", maschile"
            n.contains("itb") || n.contains("ita") -> ", femminile"
            else -> ""
        }
        val tipo = if (voce.isNetworkConnectionRequired) "online, più naturale" else "offline"
        return "Voce $numero$genere ($tipo)"
    }
}
