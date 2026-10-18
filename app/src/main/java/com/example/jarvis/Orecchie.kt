package com.example.jarvis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Le orecchie di Jarvis: il riconoscimento vocale di Google, usato direttamente.
 * Quando Jarvis è l'assistente predefinito, il telefono indica Jarvis come riconoscimento vocale:
 * qui scegliamo sempre quello di Google, così non si crea un giro a vuoto.
 */
object Orecchie {

    fun componente(ctx: Context): ComponentName? = try {
        val servizi = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.packageName != ctx.packageName }
        val scelto = servizi.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: servizi.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
            ?: servizi.firstOrNull { it.serviceInfo.packageName.startsWith("com.google") }
            ?: servizi.firstOrNull()
        scelto?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (e: Exception) {
        null
    }

    fun disponibile(ctx: Context) = componente(ctx) != null || SpeechRecognizer.isRecognitionAvailable(ctx)

    fun crea(ctx: Context): SpeechRecognizer {
        val c = componente(ctx)
        return if (c != null) SpeechRecognizer.createSpeechRecognizer(ctx, c) else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    /** Richiesta di riconoscimento in italiano; senza internet chiede il riconoscimento sul telefono. */
    fun richiesta(ctx: Context, parziali: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (parziali) putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (Build.VERSION.SDK_INT >= 23 && !online(ctx)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    fun online(ctx: Context): Boolean = try {
        val gestore = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val rete = gestore.activeNetwork
            val capacita = if (rete != null) gestore.getNetworkCapabilities(rete) else null
            capacita?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            gestore.activeNetworkInfo?.isConnected == true
        }
    } catch (e: Exception) {
        true    // nel dubbio si prova
    }
}
