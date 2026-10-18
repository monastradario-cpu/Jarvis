package com.example.jarvis

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Dal menu "Condividi" di qualsiasi app: "Jarvis: invia al PC". */
class CondividiAlPc : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                if (uri != null) file.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val elenco = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                elenco?.let { file.addAll(it) }
            }
        }
        val testo = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val ponte = PontePc(getSharedPreferences("jarvis", MODE_PRIVATE))
        if (file.isEmpty() && testo.isNullOrBlank()) {
            finish()
            return
        }
        Toast.makeText(this, "Invio al PC...", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val esiti = file.map { ponte.inviaFile(app, it) }.toMutableList()
            if (file.isEmpty() && !testo.isNullOrBlank()) esiti.add(ponte.comando("copia", testo))
            val messaggio = if (esiti.size == 1) esiti[0] else esiti.joinToString("\n")
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, messaggio, Toast.LENGTH_LONG).show() }
        }.start()
        finish()
    }
}
