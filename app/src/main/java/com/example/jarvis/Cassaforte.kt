package com.example.jarvis

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cassaforte: chiavi API, password e token sono salvati cifrati con una chiave
 * custodita dal chip di sicurezza del telefono (Android Keystore). Non escono mai dal telefono.
 */
object Cassaforte {

    private const val ALIAS = "jarvis_cassaforte"
    private const val PREFISSO = "enc1:"

    val SEGRETE = setOf("api_key", "chiave_claude", "posta_password", "casa_token", "pc_token")

    @Synchronized
    private fun chiave(): SecretKey {
        val archivio = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (archivio.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generatore = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generatore.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generatore.generateKey()
    }

    fun cifra(testo: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chiave())
        val cifrato = c.doFinal(testo.toByteArray(Charsets.UTF_8))
        return PREFISSO + Base64.encodeToString(c.iv + cifrato, Base64.NO_WRAP)
    }

    fun decifra(valore: String): String {
        val dati = Base64.decode(valore.removePrefix(PREFISSO), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, dati, 0, 12))
        return String(c.doFinal(dati, 12, dati.size - 12), Charsets.UTF_8)
    }

    /** Legge un segreto. I valori vecchi, ancora in chiaro, vengono cifrati alla prima lettura. */
    fun leggi(prefs: SharedPreferences, chiave: String): String {
        val v = prefs.getString(chiave, "") ?: ""
        if (v.isEmpty()) return ""
        if (!v.startsWith(PREFISSO)) {
            try { prefs.edit().putString(chiave, cifra(v)).apply() } catch (e: Exception) { }
            return v
        }
        return try {
            decifra(v)
        } catch (e: Exception) {
            ""      // per esempio dopo un ripristino su un altro telefono: va reinserita
        }
    }

    fun scrivi(editor: SharedPreferences.Editor, chiave: String, valore: String): SharedPreferences.Editor {
        val v = valore.trim()
        return editor.putString(chiave, if (v.isEmpty()) "" else try { cifra(v) } catch (e: Exception) { v })
    }
}
