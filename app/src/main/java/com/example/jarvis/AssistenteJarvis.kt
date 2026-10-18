package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Jarvis come assistente predefinito del telefono:
 * tenendo premuto il tasto di accensione (o il gesto dell'assistente) si apre Jarvis, già in ascolto.
 * Si sceglie in Impostazioni > App > App predefinite > Assistente digitale.
 */
class AssistenteJarvis : VoiceInteractionService()

class SessioneServizioJarvis : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SessioneJarvis(this)
}

class SessioneJarvis(contesto: Context) : VoiceInteractionSession(contesto) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intento = Intent(context, MainActivity::class.java)
            .putExtra("ascolta", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            if (Build.VERSION.SDK_INT >= 23) startAssistantActivity(intento) else context.startActivity(intento)
        } catch (e: Exception) {
            try { context.startActivity(intento) } catch (e2: Exception) { }
        }
        hide()
    }
}

/**
 * Un assistente deve avere anche un servizio di riconoscimento vocale.
 * Questo passa tutto al riconoscimento di Google, così le altre app (tastiere, ricerca vocale) continuano a funzionare.
 */
class RiconoscimentoJarvis : RecognitionService() {

    private var interno: SpeechRecognizer? = null

    override fun onStartListening(richiesta: Intent?, chi: RecognitionService.Callback?) {
        val callback = chi ?: return
        val componente = Orecchie.componente(this)
        if (componente == null) {
            try { callback.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) { }
            return
        }
        try { interno?.destroy() } catch (e: Exception) { }
        interno = SpeechRecognizer.createSpeechRecognizer(this, componente).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { try { callback.readyForSpeech(params ?: Bundle()) } catch (e: Exception) { } }
                override fun onBeginningOfSpeech() { try { callback.beginningOfSpeech() } catch (e: Exception) { } }
                override fun onRmsChanged(rmsdB: Float) { try { callback.rmsChanged(rmsdB) } catch (e: Exception) { } }
                override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) try { callback.bufferReceived(buffer) } catch (e: Exception) { } }
                override fun onEndOfSpeech() { try { callback.endOfSpeech() } catch (e: Exception) { } }
                override fun onError(error: Int) { try { callback.error(error) } catch (e: Exception) { } }
                override fun onResults(results: Bundle?) { try { callback.results(results ?: Bundle()) } catch (e: Exception) { } }
                override fun onPartialResults(partialResults: Bundle?) { try { callback.partialResults(partialResults ?: Bundle()) } catch (e: Exception) { } }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(richiesta ?: Orecchie.richiesta(this@RiconoscimentoJarvis, true))
        }
    }

    override fun onStopListening(chi: RecognitionService.Callback?) {
        try { interno?.stopListening() } catch (e: Exception) { }
    }

    override fun onCancel(chi: RecognitionService.Callback?) {
        try { interno?.cancel() } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { interno?.destroy() } catch (e: Exception) { }
        interno = null
        super.onDestroy()
    }
}
