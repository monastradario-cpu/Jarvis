package com.example.jarvis

import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Impostazioni della voce di Jarvis, condivise tra la schermata e il servizio. */
object Voce {

    const val TONO_PREDEFINITO = 1f
    const val VELOCITA_PREDEFINITA = 1f

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
