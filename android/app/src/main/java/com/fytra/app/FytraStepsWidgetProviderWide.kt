package com.fytra.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

// ============================================================================================
// FytraStepsWidgetProviderWide
// --------------------------------------------------------------------------------------------
// ΝΕΟ (ζητήθηκε ρητά, 6/11): τέταρτη επιλογή μεγέθους στον επιλογέα widget -- 3x2 (3 στήλες πλάτος,
// 2 σειρές ύψος). Σε αντίθεση με το 3x1 (FytraStepsWidgetProviderMedium, μειωμένο περιεχόμενο όπως το
// 2x1), αυτό εδώ κρατάει ΑΚΡΙΒΩΣ το ΙΔΙΟ πλήρες περιεχόμενο με το κανονικό 4x2 (δακτύλιος, kcal/km
// κάρτες, κουμπί θέματος, "Open App", "i"/popup) -- εφικτό γιατί έχει 2 σειρές ύψος (το πλήρες
// περιεχόμενο χρειάζεται ~128dp/2 σειρές για να χωρέσει χωρίς να κοπεί, το ΙΔΙΟ constraint με το παλιό
// "Open App εκτός ορίων" bug). Χρησιμοποιεί ΑΥΤΟΥΣΙΑ το R.layout.widget_steps (ΟΧΙ ξεχωριστό layout) και
// την ΙΔΙΑ FytraStepsWidgetProvider.buildRemoteViews() -- μόνο το requested πλάτος είναι μικρότερο (3
// στήλες αντί για 4). Μοιράζεται το ΙΔΙΟ WIDGET_PREFS_NAME "display cache" -- ΔΕΝ έχει δική της πηγή
// δεδομένων. ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 filter.
// ============================================================================================
class FytraStepsWidgetProviderWide : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            val views = FytraStepsWidgetProvider.buildRemoteViews(context)
            for (id in appWidgetIds) {
                appWidgetManager.updateAppWidget(id, views)
            }
        } catch (e: Exception) { /* μη κρίσιμο */ }
        // ΝΕΟ: ίδιο δίχτυ ασφαλείας (30') με τα άλλα μεγέθη -- goAsync() πρέπει να κληθεί ΕΔΩ, από το
        // ΔΙΚΟ ΤΗΣ instance αυτής της κλάσης.
        val pendingResult = try { goAsync() } catch (e: Exception) { return }
        FytraStepsWidgetProvider.hardwareCatchUpCore(context) {
            try { pendingResult.finish() } catch (e: Exception) {}
        }
    }
}
