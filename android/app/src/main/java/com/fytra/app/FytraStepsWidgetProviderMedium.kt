package com.fytra.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

// ============================================================================================
// FytraStepsWidgetProviderMedium
// --------------------------------------------------------------------------------------------
// ΝΕΟ (ζητήθηκε ρητά, 6/11, v2): τρίτη επιλογή μεγέθους στον επιλογέα widget -- 3x1 (3 στήλες πλάτος,
// 1 σειρά ύψος).
//
// ΑΝΑΘΕΩΡΗΣΗ (ζητήθηκε ρητά): η πρώτη εκδοχή αυτού του αρχείου έκανε το 3x1 να κρατάει ΑΚΡΙΒΩΣ το
// περιεχόμενο του 4x2, αλλά αυτό ΑΠΑΙΤΟΥΣΕ 2 σειρές ύψος (το περιεχόμενο του 4x2 δεν χωράει σε 1 σειρά
// χωρίς να κοπεί/βγει εκτός ορίων, ίδιο πρόβλημα με το παλιό bug report). Μετά από ρητή επιλογή,
// χρησιμοποιείται ΤΩΡΑ το ΙΔΙΟ, μειωμένο περιεχόμενο με το 2x1 (δακτύλιος+βήματα+kcal/km+"i", το άνοιγμα
// εφαρμογής μέσα στο popup) -- ΤΟ ΙΔΙΟ ακριβώς R.layout.widget_steps_compact και η ΙΔΙΑ
// FytraStepsWidgetProviderCompact.buildRemoteViewsCompact() λογική, ΑΥΤΟΥΣΙΑ. Μόνο το πλάτος είναι
// μεγαλύτερο (3 στήλες αντί για 2) -- λίγο παραπάνω "ανάσα" γύρω από το ίδιο περιεχόμενο, αφού το
// περιεχόμενο είναι κεντραρισμένο (wrap_content) μέσα στο FrameLayout.
//
// Μοιράζεται το ΙΔΙΟ WIDGET_PREFS_NAME "display cache" με τα άλλα δύο μεγέθη -- ΔΕΝ έχει δική της πηγή
// δεδομένων. Τα PendingIntent μέσα στο RemoteViews (από το κοινό buildRemoteViewsCompact()) στοχεύουν το
// FytraStepsWidgetProvider (ΟΧΙ αυτή την κλάση) για refresh/info/close, που τα χειρίζεται ήδη στο δικό
// του onReceive() και καλεί requestWidgetUpdate() -- η οποία πλέον ανανεώνει ΚΑΙ τα δικά της ids (βλ.
// εκεί). ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 filter.
// ============================================================================================
class FytraStepsWidgetProviderMedium : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            val views = FytraStepsWidgetProviderCompact.buildRemoteViewsCompact(context)
            for (id in appWidgetIds) {
                appWidgetManager.updateAppWidget(id, views)
            }
        } catch (e: Exception) { /* μη κρίσιμο */ }
        // ΝΕΟ: ίδιο δίχτυ ασφαλείας (30') με τα άλλα δύο μεγέθη -- goAsync() πρέπει να κληθεί ΕΔΩ, από το
        // ΔΙΚΟ ΤΗΣ instance αυτής της κλάσης.
        val pendingResult = try { goAsync() } catch (e: Exception) { return }
        FytraStepsWidgetProvider.hardwareCatchUpCore(context) {
            try { pendingResult.finish() } catch (e: Exception) {}
        }
    }
}
