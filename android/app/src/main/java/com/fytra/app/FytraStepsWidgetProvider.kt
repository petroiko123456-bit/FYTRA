package com.fytra.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.widget.RemoteViews
import java.util.Locale

// ============================================================================
// FytraStepsWidgetProvider
// ----------------------------------------------------------------------------
// ΝΕΟ (5/11, ζητήθηκε ρητά): home-screen widget που δείχνει τα σημερινά βήματα/kcal/km ΧΩΡΙΣ να
// χρειάζεται να ανοίξει η εφαρμογή. ΚΡΙΣΙΜΟ / ΑΠΑΡΑΒΑΤΟΣ ΟΡΟΣ (ίδιος με FytraMidnightSnapshotReceiver.kt
// και FytraStepForegroundService.kt): αυτό το αρχείο ΔΕΝ αγγίζει, ΔΕΝ εισάγει και ΔΕΝ καλεί καμία
// συνάρτηση από το God Mode/v1/v2 filter μέσα στο FytraStepCounterPlugin.kt. Διαβάζει ΜΟΝΟ ΈΝΑ δικό
// του, ξεχωριστό, καθαρό "display cache" SharedPreferences αρχείο (WIDGET_PREFS_NAME) που γεμίζουν
// ΑΠΟΚΛΕΙΣΤΙΚΑ οι μέθοδοι updateWidgetData()/pushWidgetUpdateFromNativeCredit() του
// FytraStepCounterPlugin.kt -- οι οποίες με τη σειρά τους απλά αποθηκεύουν τα ΗΔΗ υπολογισμένα, τελικά
// νούμερα (είτε από το JS/index.html, είτε από το ΗΔΗ εγκεκριμένο αποτέλεσμα ενός creditOneStep() -- ποτέ
// δική του παράλληλη λογική υπολογισμού βημάτων).
//
// ΝΕΟ (redesign, ζητήθηκε ρητά): προστέθηκαν 6 θέματα χρώματος (Χρυσό/Ocean/Emerald/Crimson/Violet/
// Orange), αλλαγή είτε από την εφαρμογή είτε απευθείας πατώντας το κουμπί παλέτας πάνω στο ίδιο το
// widget (βλ. onReceive/ACTION_CYCLE_THEME παρακάτω -- ΔΕΝ ανοίγει την εφαρμογή, μόνο αλλάζει δείκτη
// θέματος και ξαναζωγραφίζει). Ο κυκλικός δακτύλιος προόδου ΔΕΝ μπορεί να γίνει με RemoteViews XML
// (καμία υποστήριξη για dynamic SVG/vector progress arc) -> ζωγραφίζεται προγραμματιστικά σε Bitmap
// (βλ. drawProgressRingBitmap) και περνάει στο ImageView μέσω setImageViewBitmap().
// ============================================================================
class FytraStepsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val WIDGET_PREFS_NAME = "FytraWidgetPrefs"
        const val KEY_WIDGET_STEPS = "widgetSteps"
        const val KEY_WIDGET_KCAL = "widgetKcal"
        const val KEY_WIDGET_KM = "widgetKm"
        const val KEY_WIDGET_UPDATED_TS = "widgetUpdatedTs"
        // ΝΕΟ (ΔΙΟΡΘΩΣΗ BUG, "μηδενίζει το widget μόλις ανοίξει η εφαρμογή"): ημερολογιακό κλειδί (yyyy-MM-dd)
        // της μέρας στην οποία ανήκει το τελευταίο αποθηκευμένο KEY_WIDGET_STEPS. Χρησιμοποιείται από το
        // FytraStepCounterPlugin (βλ. writeWidgetDisplayCacheGuarded) ώστε, ΜΕΣΑ στην ΙΔΙΑ μέρα, μια νέα
        // τιμή βημάτων να ΜΗΝ επιτρέπεται ποτέ να είναι ΜΙΚΡΟΤΕΡΗ από την ήδη αποθηκευμένη -- ίδιο ακριβώς
        // πνεύμα με το "ΠΟΤΕ δεν μειώνει" self-heal που υπάρχει ήδη στο index.html. Διορθώνει ένα race: όταν
        // ανοίγει η εφαρμογή, το ΠΡΩΤΟ renderSteps() τρέχει ΠΡΙΝ ολοκληρωθεί η ανάκτηση βημάτων κλειστής
        // εφαρμογής (checkClosedAppSteps, async) -> χωρίς αυτό το guard, θα έγραφε προσωρινά ένα χαμηλότερο/
        // μηδενικό νούμερο πάνω στο ήδη σωστό που είχε ήδη γράψει το native creditOneStep() ενώ ήταν κλειστή.
        const val KEY_WIDGET_DAYKEY = "widgetDayKey"
        // ΝΕΟ (live update όσο η εφαρμογή είναι ΕΝΤΕΛΩΣ κλειστή, βλ. FytraStepCounterPlugin.creditOneStep):
        // ο εξατομικευμένος διασκελισμός του χρήστη (STEP_LENGTH_M στο index.html), cache-αρισμένος εδώ από
        // το updateWidgetData() ΚΑΘΕ φορά που το JS είναι ζωντανό, ώστε το native side να μπορεί να
        // υπολογίσει σωστά km για βήματα που πιστώνονται ΧΩΡΙΣ κανένα ζωντανό JS/WebView.
        const val KEY_WIDGET_STRIDE_M = "widgetStrideM"
        // ΝΕΟ: ο προσωπικός στόχος βημάτων (GOAL στο index.html) -> cache-αρισμένος εδώ για να μπορεί το
        // widget να υπολογίσει το ποσοστό του δακτυλίου ΚΑΙ να ανιχνεύσει "πιάστηκε ο στόχος" ακόμα κι όταν
        // τα βήματα πιστώνονται καθαρά native (καμία σχέση με το God Mode/v1/v2 -- απλή αποθήκευση αριθμού).
        const val KEY_WIDGET_GOAL = "widgetGoal"
        // ΝΕΟ (5/11, θέματα widget): δείκτης (0-5) του επιλεγμένου θέματος χρώματος — 0=Χρυσό (default),
        // 1=Ocean, 2=Emerald, 3=Crimson, 4=Violet, 5=Orange. Αλλάζει είτε από επιλογή μέσα στην εφαρμογή
        // είτε πατώντας το κουμπί παλέτας πάνω στο ίδιο το widget (βλ. onReceive/ACTION_CYCLE_THEME).
        const val KEY_WIDGET_THEME_INDEX = "widgetThemeIndex"
        const val THEME_COUNT = 6

        // ΝΕΟ: custom broadcast action -- πατώντας το κουμπί παλέτας πάνω στο widget ΔΕΝ ανοίγει την
        // εφαρμογή, στέλνει ΑΥΤΟ το broadcast στο ίδιο το provider (βλ. onReceive παρακάτω), που απλά
        // προχωράει το θέμα στο επόμενο και ξαναζωγραφίζει -- τίποτα άλλο.
        const val ACTION_CYCLE_THEME = "com.fytra.app.ACTION_CYCLE_WIDGET_THEME"
        private const val EXTRA_WIDGET_ID = "fytra_widget_id"

        private data class WidgetTheme(
            val bgRes: Int,
            val badgeRes: Int,
            val statRes: Int,
            val iconWrapRes: Int,
            val accentColor: Int,
            val labelColor: Int,
            val ringStartColor: Int,
            val ringEndColor: Int
        )

        private val THEMES = arrayOf(
            WidgetTheme(R.drawable.widget_bg_0, R.drawable.badge_bg_0, R.drawable.stat_bg_0, R.drawable.icon_wrap_bg_0,
                Color.parseColor("#FFE07A"), Color.parseColor("#B89A5A"), Color.parseColor("#FFE07A"), Color.parseColor("#E8A83D")),
            WidgetTheme(R.drawable.widget_bg_1, R.drawable.badge_bg_1, R.drawable.stat_bg_1, R.drawable.icon_wrap_bg_1,
                Color.parseColor("#7FD8FF"), Color.parseColor("#6F97AC"), Color.parseColor("#7FD8FF"), Color.parseColor("#2E8FD9")),
            WidgetTheme(R.drawable.widget_bg_2, R.drawable.badge_bg_2, R.drawable.stat_bg_2, R.drawable.icon_wrap_bg_2,
                Color.parseColor("#8AFFC4"), Color.parseColor("#6FAE8C"), Color.parseColor("#8AFFC4"), Color.parseColor("#2ECC71")),
            WidgetTheme(R.drawable.widget_bg_3, R.drawable.badge_bg_3, R.drawable.stat_bg_3, R.drawable.icon_wrap_bg_3,
                Color.parseColor("#FF8FA3"), Color.parseColor("#B87A8A"), Color.parseColor("#FF8FA3"), Color.parseColor("#E63950")),
            WidgetTheme(R.drawable.widget_bg_4, R.drawable.badge_bg_4, R.drawable.stat_bg_4, R.drawable.icon_wrap_bg_4,
                Color.parseColor("#C9A3FF"), Color.parseColor("#9A86B8"), Color.parseColor("#C9A3FF"), Color.parseColor("#9D5CE8")),
            WidgetTheme(R.drawable.widget_bg_5, R.drawable.badge_bg_5, R.drawable.stat_bg_5, R.drawable.icon_wrap_bg_5,
                Color.parseColor("#FFB15C"), Color.parseColor("#B8905A"), Color.parseColor("#FFB15C"), Color.parseColor("#E8762E"))
        )

        // Καλείται από το FytraStepCounterPlugin.updateWidgetData()/pushWidgetUpdateFromNativeCredit() αμέσως
        // μετά την αποθήκευση των νέων τιμών -> ζητάει από το Android να ξαναζωγραφίσει ΟΛΑ τα ενεργά
        // instances του widget (συνήθως ένα, αλλά ο χρήστης μπορεί θεωρητικά να προσθέσει παραπάνω από ένα).
        fun requestWidgetUpdate(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, FytraStepsWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    // ΔΙΟΡΘΩΣΗ (bug report, "ακόμα δεν ανανεώνεται με κλειστή εφαρμογή"): πριν στελνόταν ένα
                    // ACTION_APPWIDGET_UPDATE broadcast στον εαυτό μας και περιμέναμε το onUpdate() να το
                    // πιάσει -- ένα έμμεσο, ένα-βήμα-παραπάνω μονοπάτι που σε κάποιες OEM συσκευές (π.χ.
                    // Samsung One UI) μπορεί να καθυστερήσει ή να "χαθεί" όσο η εφαρμογή είναι βαθιά στο
                    // παρασκήνιο, ακόμα κι όταν στέλνεται με applicationContext. ΤΩΡΑ καλούμε απευθείας
                    // appWidgetManager.updateAppWidget() με τα ήδη έτοιμα RemoteViews -- το ΙΔΙΟ, επίσημα
                    // συνιστώμενο μονοπάτι που χρησιμοποιεί ήδη το onUpdate() παρακάτω, απλά χωρίς το
                    // ενδιάμεσο broadcast hop. Καμία επίδραση στο God Mode/v1/v2 filter.
                    val views = buildRemoteViews(context)
                    for (id in ids) {
                        mgr.updateAppWidget(id, views)
                    }
                }
            } catch (e: Exception) { /* μη κρίσιμο -- το widget απλά δεν ανανεώθηκε αυτή τη φορά */ }
        }

        // ΝΕΟ: ζωγραφίζει τον κυκλικό δακτύλιο προόδου σε ένα τετράγωνο Bitmap -- μονοπάτι "φόντο" (λεπτός,
        // ημιδιάφανος κύκλος) + μονοπάτι "πρόοδος" (gradient τόξο, στρογγυλεμένα άκρα, ξεκινάει από πάνω
        // όπως στο εγκεκριμένο mockup). Καλείται ΚΑΘΕ update -- αρκετά ελαφρύ (μία μόνο Bitmap σχεδίαση
        // μερικών δεκάδων pixel) για να τρέχει σε κάθε ανανέωση χωρίς αισθητή επιβάρυνση.
        private fun drawProgressRingBitmap(sizePx: Int, strokeWidthPx: Float, progressFraction: Float, theme: WidgetTheme): Bitmap {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val pad = strokeWidthPx / 2f + 2f
            val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                strokeCap = Paint.Cap.ROUND
                color = Color.argb(28, 255, 255, 255)
            }
            canvas.drawArc(rect, 0f, 360f, false, trackPaint)

            val clamped = progressFraction.coerceIn(0f, 1f)
            val sweep = 360f * clamped
            if (sweep > 0f) {
                val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokeWidthPx
                    strokeCap = Paint.Cap.ROUND
                    val stops = if (clamped < 1f) floatArrayOf(0f, clamped, 1f) else floatArrayOf(0f, 0.999f, 1f)
                    shader = SweepGradient(
                        sizePx / 2f, sizePx / 2f,
                        intArrayOf(theme.ringStartColor, theme.ringEndColor, theme.ringStartColor),
                        stops
                    )
                }
                canvas.save()
                canvas.rotate(-90f, sizePx / 2f, sizePx / 2f)
                canvas.drawArc(rect, 0f, sweep, false, progressPaint)
                canvas.restore()
            }
            return bmp
        }

        private fun themeAt(index: Int): WidgetTheme {
            val safe = if (index in 0 until THEME_COUNT) index else 0
            return THEMES[safe]
        }

        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_steps)
            val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            val steps = prefs.getInt(KEY_WIDGET_STEPS, 0)
            val kcal = prefs.getInt(KEY_WIDGET_KCAL, 0)
            val km = prefs.getFloat(KEY_WIDGET_KM, 0f)
            val goal = prefs.getInt(KEY_WIDGET_GOAL, 6000)
            val themeIndex = prefs.getInt(KEY_WIDGET_THEME_INDEX, 0)
            val theme = themeAt(themeIndex)

            // -------- φόντα θέματος --------
            views.setInt(R.id.widgetRoot, "setBackgroundResource", theme.bgRes)
            views.setInt(R.id.widgetBrandBadge, "setBackgroundResource", theme.badgeRes)
            views.setInt(R.id.widgetThemeBtn, "setBackgroundResource", theme.iconWrapRes)
            views.setInt(R.id.widgetKcalWrap, "setBackgroundResource", theme.statRes)
            views.setInt(R.id.widgetKmWrap, "setBackgroundResource", theme.statRes)

            // -------- κείμενα/χρώματα θέματος --------
            views.setTextColor(R.id.widgetRingNum, theme.accentColor)
            views.setInt(R.id.widgetThemeBtnIcon, "setColorFilter", theme.accentColor)
            views.setTextViewText(R.id.widgetRingNum, formatThousands(steps))
            views.setTextViewText(
                R.id.widgetKcalVal,
                formatThousands(kcal) + " kcal"
            )
            views.setTextViewText(
                R.id.widgetKmVal,
                String.format(Locale.US, "%.2f", km).replace(".", ",") + " km"
            )

            // -------- δακτύλιος προόδου --------
            val goalReached = goal > 0 && steps >= goal
            val progressFraction = if (goal > 0) steps.toFloat() / goal.toFloat() else 0f
            // ΝΕΟ (bug report, "μεγάλωσε ελάχιστα το κυκλικό καντράν"): 94dp στο layout πλέον (πριν 86dp) ->
            // ζωγραφίζουμε σε σταθερά 240px (πριν 220px, ίδια αναλογία), αρκετά ευκρινές ακόμα και σε
            // high-density οθόνες -- το ImageView το προσαρμόζει στο layout μέγεθος ούτως ή άλλως.
            val ringBitmap = drawProgressRingBitmap(240, 21f, progressFraction, theme)
            views.setImageViewBitmap(R.id.widgetRingImage, ringBitmap)

            // -------- κάρτα "Στόχος!" --------
            views.setViewVisibility(R.id.widgetGoalCard, if (goalReached) android.view.View.VISIBLE else android.view.View.GONE)

            // Πατώντας οπουδήποτε στο widget (εκτός από το κουμπί θέματος, που καταναλώνει το touch μόνο
            // του) ανοίγει κανονικά η εφαρμογή (ίδια MainActivity).
            val launchIntent = Intent(context, MainActivity::class.java)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val launchFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val openAppPendingIntent = android.app.PendingIntent.getActivity(context, 0, launchIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent)

            // ΝΕΟ: το κουμπί παλέτας έχει ΔΙΚΟ ΤΟΥ, ξεχωριστό PendingIntent -- BROADCAST (όχι activity), ώστε
            // να ΜΗΝ ανοίγει την εφαρμογή, μόνο να προχωράει το θέμα στο επόμενο (βλ. onReceive παρακάτω).
            // Ένα child view με δικό του explicit click target καταναλώνει το touch -- δεν "διαρρέει" στο
            // γονικό widgetRoot click (τυπικό, αποδεδειγμένο RemoteViews pattern, ίδιο με play/pause κουμπιά
            // πάνω σε media widgets).
            val cycleIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            cycleIntent.action = ACTION_CYCLE_THEME
            val cyclePendingIntent = android.app.PendingIntent.getBroadcast(context, 1, cycleIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetThemeBtn, cyclePendingIntent)

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

    // ΝΕΟ (5/11, θέματα widget): χειρίζεται ΜΟΝΟ το δικό μας custom broadcast (ACTION_CYCLE_THEME) -- το
    // πάτημα του κουμπιού παλέτας πάνω στο widget. ΔΕΝ ανοίγει την εφαρμογή, ΔΕΝ αγγίζει κανένα κλειδί
    // μέτρησης βημάτων -- μόνο προχωράει το δείκτη θέματος κατά ένα (κυκλικά) και ξαναζωγραφίζει.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CYCLE_THEME) {
            try {
                val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
                val current = prefs.getInt(KEY_WIDGET_THEME_INDEX, 0)
                val next = (current + 1) % THEME_COUNT
                prefs.edit().putInt(KEY_WIDGET_THEME_INDEX, next).apply()
                requestWidgetUpdate(context)
            } catch (e: Exception) { /* μη κρίσιμο */ }
            return
        }
        super.onReceive(context, intent)
    }
}
