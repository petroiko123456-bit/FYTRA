package com.fytra.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

// ============================================================================================
// FytraStepsWidgetProviderCompact
// --------------------------------------------------------------------------------------------
// ΝΕΟ (ζητήθηκε ρητά, 6/11): "μικρή" εκδοχή (2x1) του widget βημάτων, ώστε να εμφανίζεται ΩΣ
// ΞΕΧΩΡΙΣΤΗ επιλογή μεγέθους στον επιλογέα widget του Android (δίπλα στο κανονικό 4x2 του
// FytraStepsWidgetProvider) -- βλ. εκτενές σχόλιο στο widget_steps_info_compact.xml για το γιατί
// χρειάζεται δικό της receiver class αντί για ένα απλό δεύτερο μέγεθος πάνω στο ίδιο provider.
//
// Διαβάζει ΑΠΟΚΛΕΙΣΤΙΚΑ το ΙΔΙΟ, ήδη υπάρχον "display cache" (FytraStepsWidgetProvider.WIDGET_PREFS_NAME)
// -- ΔΕΝ έχει δικό της ξεχωριστή πηγή δεδομένων, άρα δείχνει ΠΑΝΤΑ ακριβώς το ίδιο αριθμό βημάτων με το
// κανονικό widget, μόνο σε μικρότερη μορφή.
//
// ΑΝΑΘΕΩΡΗΣΗ v2 (ζητήθηκε ρητά): προστέθηκαν kcal/km (μικρότερη γραμματοσειρά) και η κουκκίδα "i". Το
// πάτημα στο ΚΥΡΙΟ σώμα κάνει πλέον REFRESH (όχι άνοιγμα εφαρμογής, όπως και στο κανονικό 4x2) -- το
// άνοιγμα της εφαρμογής μετακόμισε μέσα στο popup του "i" ως μικρός σύνδεσμος, αφού δεν χωράει ξεχωριστό
// κουμπί "Open App" σε τόσο στενό χώρο. ΤΟ ΚΟΛΠΟ: τα PendingIntent του refresh/info/close ΔΕΝ στοχεύουν
// αυτή την κλάση -- στοχεύουν ΑΠΕΥΘΕΙΑΣ το FytraStepsWidgetProvider (ίδια actions,
// ACTION_WIDGET_REFRESH/ACTION_WIDGET_INFO/ACTION_WIDGET_INFO_HIDE), που ήδη τα χειρίζεται στο δικό του
// onReceive() και γράφει στο ΚΟΙΝΟ WIDGET_PREFS_NAME -- έτσι ΔΕΝ χρειάζεται να ξαναγραφτεί η ίδια λογική
// εδώ. Το FytraStepsWidgetProvider.requestWidgetUpdate() φροντίζει ήδη να ανανεώνει ΚΑΙ τα compact ids.
//
// Το αυτόματο "κόλλημα" με τον αισθητήρα κάθε ~30' (βλ. FytraStepsWidgetProvider.hardwareCatchUpCore)
// δουλεύει ΚΑΙ εδώ, ανεξάρτητα -- ΔΕΝ χρειάζεται να υπάρχει ΚΑΙ το κανονικό widget για να δουλέψει.
// ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 filter -- μόνο ανάγνωση/προβολή του ήδη υπολογισμένου "display cache".
// ============================================================================================
class FytraStepsWidgetProviderCompact : AppWidgetProvider() {

    companion object {
        fun buildRemoteViewsCompact(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_steps_compact)
            val prefs = context.getSharedPreferences(FytraStepsWidgetProvider.WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            val steps = prefs.getInt(FytraStepsWidgetProvider.KEY_WIDGET_STEPS, 0)
            val kcal = prefs.getInt(FytraStepsWidgetProvider.KEY_WIDGET_KCAL, 0)
            val km = prefs.getFloat(FytraStepsWidgetProvider.KEY_WIDGET_KM, 0f)
            val goal = prefs.getInt(FytraStepsWidgetProvider.KEY_WIDGET_GOAL, 6000)
            val themeIndex = prefs.getInt(FytraStepsWidgetProvider.KEY_WIDGET_THEME_INDEX, 0)
            val theme = FytraStepsWidgetProvider.themeAt(themeIndex)
            val infoVisible = prefs.getBoolean(FytraStepsWidgetProvider.KEY_WIDGET_INFO_VISIBLE, false)

            // ΔΙΟΡΘΩΣΗ (ζητήθηκε ρητά, 6/11): pressableBgRes αντί για bgRes -- ripple/highlight εφέ
            // "πατήματος" στο tap-to-refresh (βλ. ίδιο σχόλιο στο FytraStepsWidgetProvider.kt). Ισχύει
            // ΚΑΙ για το 3x1, αφού το medium delegate-άρει σε αυτή την ίδια buildRemoteViewsCompact().
            views.setInt(R.id.widgetCompactRoot, "setBackgroundResource", theme.pressableBgRes)
            views.setTextColor(R.id.widgetCompactStepsNum, theme.accentColor)
            views.setTextViewText(R.id.widgetCompactStepsNum, FytraStepsWidgetProvider.formatThousands(steps))
            views.setTextViewText(R.id.widgetCompactKcalVal, FytraStepsWidgetProvider.formatThousands(kcal) + " kcal")
            views.setTextViewText(
                R.id.widgetCompactKmVal,
                String.format(Locale.US, "%.2f", km).replace(".", ",") + " km"
            )

            // Μικρότερο bitmap δακτυλίου (ταιριάζει στο 34dp του layout) -- ίδια μέθοδος σχεδίασης με το
            // κανονικό widget (FytraStepsWidgetProvider.drawProgressRingBitmap, έγινε internal ακριβώς
            // για να το μοιράζεται και αυτή η κλάση).
            val progressFraction = if (goal > 0) steps.toFloat() / goal.toFloat() else 0f
            val ringBitmap = FytraStepsWidgetProvider.drawProgressRingBitmap(88, 9f, progressFraction, theme)
            views.setImageViewBitmap(R.id.widgetCompactRingImage, ringBitmap)

            val launchFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT

            // ΝΕΟ: πάτημα στο κύριο σώμα -- REFRESH, ΟΧΙ άνοιγμα εφαρμογής (ίδιο action, ίδιος στόχος
            // class με το κανονικό 4x2, βλ. σχόλιο στην κορυφή του αρχείου).
            val refreshIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            refreshIntent.action = FytraStepsWidgetProvider.ACTION_WIDGET_REFRESH
            val refreshPendingIntent = android.app.PendingIntent.getBroadcast(context, 20, refreshIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetCompactRoot, refreshPendingIntent)

            // ΝΕΟ: κουκκίδα "i" -- ανοίγει το popup (ίδιο κοινό KEY_WIDGET_INFO_VISIBLE με το 4x2).
            val infoIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            infoIntent.action = FytraStepsWidgetProvider.ACTION_WIDGET_INFO
            val infoPendingIntent = android.app.PendingIntent.getBroadcast(context, 21, infoIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetCompactInfoBtn, infoPendingIntent)

            // ΝΕΟ: "✕" μέσα στο popup -- κλείνει το popup.
            val infoHideIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            infoHideIntent.action = FytraStepsWidgetProvider.ACTION_WIDGET_INFO_HIDE
            val infoHidePendingIntent = android.app.PendingIntent.getBroadcast(context, 22, infoHideIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetCompactInfoCloseBtn, infoHidePendingIntent)

            // ΔΙΟΡΘΩΣΗ (ζητήθηκε ρητά, 6/11, v3): τώρα υπάρχει ΚΑΙ ορατό κουμπί "Open App" πάνω στο ίδιο
            // το widget (widgetCompactOpenAppBtn, κάτω δεξιά, βλ. layout) -- το ΙΔΙΟ PendingIntent δίνεται
            // ΚΑΙ στον σύνδεσμο μέσα στο popup (widgetCompactOpenAppLink), που παραμένει ως δεύτερος,
            // εναλλακτικός τρόπος ανοίγματος.
            val launchIntent = Intent(context, MainActivity::class.java)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val openAppPendingIntent = android.app.PendingIntent.getActivity(context, 23, launchIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetCompactOpenAppLink, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widgetCompactOpenAppBtn, openAppPendingIntent)

            // Ίδιο, κοινό flag με το κανονικό 4x2 widget (KEY_WIDGET_INFO_VISIBLE) -- βλ. σχόλιο στην
            // κορυφή του αρχείου για τον λόγο.
            views.setViewVisibility(R.id.widgetCompactInfoBubble, if (infoVisible) android.view.View.VISIBLE else android.view.View.GONE)

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            val views = buildRemoteViewsCompact(context)
            for (id in appWidgetIds) {
                appWidgetManager.updateAppWidget(id, views)
            }
        } catch (e: Exception) { /* μη κρίσιμο */ }
        // ΝΕΟ: ίδιο δίχτυ ασφαλείας με το κανονικό widget -- goAsync() πρέπει να κληθεί ΕΔΩ, από το ΔΙΚΟ
        // ΤΗΣ instance αυτής της κλάσης (βλ. σχόλιο στο FytraStepsWidgetProvider.tryHardwareCatchUp).
        val pendingResult = try { goAsync() } catch (e: Exception) { return }
        FytraStepsWidgetProvider.hardwareCatchUpCore(context) {
            try { pendingResult.finish() } catch (e: Exception) {}
        }
    }
}
