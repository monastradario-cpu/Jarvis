package com.example.jarvis

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.widget.RemoteViews
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Widget sulla schermata home: la sfera per parlare e il prossimo impegno o promemoria. */
class WidgetJarvis : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, gestore: AppWidgetManager, ids: IntArray) {
        aggiorna(ctx)
    }

    companion object {
        fun aggiorna(ctx: Context) {
            val gestore = AppWidgetManager.getInstance(ctx) ?: return
            val ids = gestore.getAppWidgetIds(ComponentName(ctx, WidgetJarvis::class.java))
            if (ids.isEmpty()) return
            val vista = RemoteViews(ctx.packageName, R.layout.widget_jarvis)
            vista.setTextViewText(R.id.widget_prossimo, prossimo(ctx))
            val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val parla = PendingIntent.getActivity(
                ctx, 41, Intent(ctx, MainActivity::class.java).putExtra("ascolta", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flag
            )
            val apri = PendingIntent.getActivity(ctx, 42, Intent(ctx, MainActivity::class.java), flag)
            vista.setOnClickPendingIntent(R.id.widget_sfera, parla)
            vista.setOnClickPendingIntent(R.id.widget_testi, apri)
            gestore.updateAppWidget(ids, vista)
        }

        /** Il prossimo impegno di oggi o il prossimo promemoria, in una riga. */
        private fun prossimo(ctx: Context): String {
            val adesso = System.currentTimeMillis()
            val fine = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            }.timeInMillis
            val ora = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            var miglior: Pair<Long, String>? = null

            val puoLeggere = Build.VERSION.SDK_INT < 23 ||
                ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            if (puoLeggere) {
                try {
                    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    ContentUris.appendId(uri, adesso)
                    ContentUris.appendId(uri, fine)
                    ctx.contentResolver.query(
                        uri.build(),
                        arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY),
                        null, null, CalendarContract.Instances.BEGIN + " ASC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            if (c.getInt(2) == 1) continue
                            miglior = c.getLong(0) to "${ora.format(Date(c.getLong(0)))} ${c.getString(1) ?: "Impegno"}"
                            break
                        }
                    }
                } catch (e: Exception) { }
            }
            try {
                val lista = JSONArray(ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("promemoria", "[]") ?: "[]")
                for (i in 0 until lista.length()) {
                    val v = lista.optJSONObject(i) ?: continue
                    if (v.optString("tipo") != "ora") continue
                    val quando = v.optLong("quando")
                    if (quando > adesso && quando <= fine && (miglior == null || quando < miglior!!.first)) {
                        miglior = quando to "${ora.format(Date(quando))} ⏰ ${v.optString("testo")}"
                    }
                }
            } catch (e: Exception) { }
            return miglior?.second ?: "Nessun impegno per oggi · tocca la sfera per parlare"
        }
    }
}
