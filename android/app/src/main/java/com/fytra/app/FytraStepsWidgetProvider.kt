package com.fytra.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

// ============================================================================
// FytraStepsWidgetProvider
// ----------------------------------------------------------------------------
// ΝΕΟ (5/11, ζητήθηκε ρητά): home-screen widget που δείχνει τα σημερινά βήματα/kcal/km ΧΩΡΙΣ να
// χρειάζεται να ανοίξει η εφαρμογή. ΚΡΙΣΙΜΟ / ΑΠΑΡΑΒΑΤΟΣ ΟΡΟΣ (ίδιος με FytraMidnightSnapshotReceiver.kt
// και FytraStepForegroundService.kt): αυτό το αρχείο ΔΕΝ αγγίζει, ΔΕΝ εισάγει και ΔΕΝ καλεί καμία
// συνάρτηση από το God Mode/v1/v2 filter μέσα στο FytraStepCounterPlugin.kt. Διαβάζει ΜΟΝΟ ΈΝΑ δικό
// του, ξεχωριστό, καθαρό "display cache" SharedPreferences αρχείο (WIDGET_PREFS_NAME) που γεμίζει
// ΑΠΟΚΛΕΙΣΤΙΚΑ η μέθοδος updateWidgetData() του FytraStepCounterPlugin -- η οποία με τη σειρά της
// απλά αποθηκεύει τα ΗΔΗ υπολογισμένα, τελικά νούμερα που στέλνει το JS (index.html/renderSteps() ->
// FytraStepEngine.updateWidgetNative) -- άρα το widget δείχνει ΠΑΝΤΑ ακριβώς ό,τι βλέπει ο χρήστης
// στην οθόνη της εφαρμογής, καμία ξεχωριστή/παράλληλη λογική υπολογισμού βημάτων εδώ.
// ============================================================================
class FytraStepsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val WIDGET_PREFS_NAME = "FytraWidgetPrefs"
        const val KEY_WIDGET_STEPS = "widgetSteps"
        const val KEY_WIDGET_KCAL = "widgetKcal"
        const val KEY_WIDGET_KM = "widgetKm"
        const val KEY_WIDGET_UPDATED_TS = "widgetUpdatedTs"

        // Καλείται από το FytraStepCounterPlugin.updateWidgetData() αμέσως μετά την αποθήκευση των
        // νέων τιμών -> ζητάει από το Android να ξαναζωγραφίσει ΟΛΑ τα ενεργά instances του widget
        // (συνήθως ένα, αλλά ο χρήστης μπορεί θεωρητικά να προσθέσει παραπάνω από ένα).
        fun requestWidgetUpdate(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, FytraStepsWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    val intent = Intent(context, FytraStepsWidgetProvider::class.java)
                    intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) { /* μη κρίσιμο -- το widget απλά δεν ανανεώθηκε αυτή τη φορά */ }
        }

        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_steps)
            val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            val steps = prefs.getInt(KEY_WIDGET_STEPS, 0)
            val kcal = prefs.getInt(KEY_WIDGET_KCAL, 0)
            val km = prefs.getFloat(KEY_WIDGET_KM, 0f)
            views.setTextViewText(R.id.widgetStepsValue, formatThousands(steps))
            views.setTextViewText(
                R.id.widgetSubValue,
                formatThousands(kcal) + " kcal · " + String.format(Locale.US, "%.2f", km).replace(".", ",") + " km"
            )
            // Πατώντας οπουδήποτε στο widget ανοίγει κανονικά η εφαρμογή (ίδια MainActivity).
            val launchIntent = Intent(context, MainActivity::class.java)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = android.app.PendingIntent.getActivity(context, 0, launchIntent, flags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            return views
        }

        // Απλή μορφοποίηση χιλιάδων (π.χ. 12.345) -- ανεξάρτητη από το el-GR locale ρύθμισμα του JS,
        // ώστε να δουλεύει αξιόπιστα και σε συσκευές χωρίς ελληνικό locale εγκατεστημένο.
        private fun formatThousands(n: Int): String {
            return try {
                String.format(Locale.US, "%,d", n).replace(",", ".")
            } catch (e: Exception) { n.toString() }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            val views = buildRemoteViews(context)
            for (id in appWidgetIds) {
                appWidgetManager.updateAppWidget(id, views)
            }
        } catch (e: Exception) { /* μη κρίσιμο */ }
    }
}
