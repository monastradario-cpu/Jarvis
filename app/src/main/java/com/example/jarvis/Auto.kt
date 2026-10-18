package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

/**
 * Modalità auto e cuffie.
 * - Quando il telefono si collega al Bluetooth dell'auto, Jarvis si mette in ascolto e risponde in modo più breve.
 * - Con le cuffie collegate ascolta anche a schermo spento e può avvisarti a voce.
 */
object Auto {

    private const val CANALE = "jarvis_auto"
    private const val ID_NOTIFICA = 950_001

    private val MARCHE_AUTO = listOf(
        "car", "auto", "vw", "volkswagen", "fiat", "ford", "toyota", "bmw", "audi", "renault", "peugeot", "opel",
        "mercedes", "seat", "skoda", "kia", "hyundai", "tesla", "citroen", "dacia", "nissan", "mazda", "volvo",
        "jeep", "alfa", "lancia", "suzuki", "honda", "mini", "uconnect", "sync", "mylink", "mmi", "carplay",
        "android auto", "my car", "mazda", "lexus", "porsche", "smart", "cupra", "ds "
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun nome(d: BluetoothDevice): String = try { d.name ?: "" } catch (e: SecurityException) { "" }

    private fun classe(d: BluetoothDevice): Int = try {
        d.bluetoothClass?.deviceClass ?: -1
    } catch (e: SecurityException) {
        -1
    }

    fun eAuto(ctx: Context, d: BluetoothDevice): Boolean {
        val n = nome(d).lowercase(Locale.ROOT)
        val scelti = (prefs(ctx).getString("auto_nomi", "") ?: "").split(",").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (scelti.isNotEmpty() && n.isNotEmpty()) return scelti.any { n.contains(it) }
        if (classe(d) == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) return true
        return n.isNotEmpty() && MARCHE_AUTO.any { Regex("(^|[^a-z])${Regex.escape(it.trim())}([^a-z]|$)").containsMatchIn(n) }
    }

    fun eCuffie(d: BluetoothDevice): Boolean = when (classe(d)) {
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> true
        else -> nome(d).lowercase(Locale.ROOT).let {
            it.contains("buds") || it.contains("airpods") || it.contains("headphone") || it.contains("cuffi") ||
                it.contains("earbud") || it.contains("headset") || it.contains("wh-") || it.contains("wf-")
        }
    }

    fun collegato(ctx: Context, d: BluetoothDevice) {
        val p = prefs(ctx)
        val n = nome(d)
        if (n.isNotEmpty()) p.edit().putString("ultimo_bluetooth", n).apply()
        if (!p.getBoolean("auto_attiva", true)) return
        when {
            eAuto(ctx, d) -> {
                p.edit().putBoolean("in_auto", true).putString("dispositivo_auto", n).apply()
                Automazioni.evento(ctx, "auto")
                if (ServizioJarvis.attivo) ServizioJarvis.invia(ctx, ServizioJarvis.AZIONE_AUTO)
                else notificaAuto(ctx)
            }
            eCuffie(d) -> {
                p.edit().putBoolean("cuffie", true).apply()
                Automazioni.evento(ctx, "cuffie")
                ServizioJarvis.invia(ctx, ServizioJarvis.AZIONE_STATO)
            }
        }
    }

    fun scollegato(ctx: Context, d: BluetoothDevice) {
        val p = prefs(ctx)
        val n = nome(d)
        if (p.getBoolean("in_auto", false) && (n.isEmpty() || n == p.getString("dispositivo_auto", "") || eAuto(ctx, d))) {
            p.edit().putBoolean("in_auto", false).apply()
            Automazioni.evento(ctx, "fine_auto")
            try { ctx.getSystemService(NotificationManager::class.java).cancel(ID_NOTIFICA) } catch (e: Exception) { }
        }
        if (eCuffie(d)) p.edit().putBoolean("cuffie", false).apply()
        ServizioJarvis.invia(ctx, ServizioJarvis.AZIONE_STATO)
    }

    /** Jarvis non è sempre attivo: una notifica per accenderlo con un tocco. */
    private fun notificaAuto(ctx: Context) {
        val gestore = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            gestore.createNotificationChannel(NotificationChannel(CANALE, "Jarvis in auto", NotificationManager.IMPORTANCE_HIGH))
        }
        val apri = PendingIntent.getActivity(
            ctx, ID_NOTIFICA, Intent(ctx, MainActivity::class.java).putExtra("attiva_auto", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val costruttore = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANALE) else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        }
        try {
            gestore.notify(
                ID_NOTIFICA, costruttore
                    .setSmallIcon(R.drawable.ic_notifica)
                    .setContentTitle("Sei in auto")
                    .setContentText("Tocca per attivare Jarvis: poi basta dire \"Ehi Jarvis\".")
                    .setContentIntent(apri)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) { }
    }

    /** Collegamenti e scollegamenti Bluetooth, anche quando Jarvis è chiuso. */
    class RicevitoreBluetooth : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            }) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> collegato(ctx, d)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> scollegato(ctx, d)
            }
        }
    }
}
