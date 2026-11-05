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

        // ΝΕΟ (ζητήθηκε ρητά, 5/11): πατώντας στο ΚΥΡΙΟ σώμα του widget -- ΟΧΙ στο κουμπί "Open App" --
        // στέλνεται αυτό το broadcast, που καλεί ΑΜΕΣΩΣ (on-demand, όχι περιοδικά) το tryHardwareCatchUp()
        // για να συγχρονίσει με τον αισθητήρα του υλικού ΤΩΡΑ, ακόμα κι αν η εφαρμογή είναι τελείως
        // κλειστή. Δεν ανοίγει καθόλου την εφαρμογή.
        const val ACTION_WIDGET_REFRESH = "com.fytra.app.ACTION_WIDGET_REFRESH"

        // ΝΕΟ: η κουκκίδα "i" πάνω δεξιά -- δείχνει ένα σύντομο Toast που εξηγεί στον χρήστη πώς δουλεύει
        // το widget (πάτημα = refresh όσο η εφαρμογή είναι κλειστή· αυτόματο real-time όσο τρέχει).
        const val ACTION_WIDGET_INFO = "com.fytra.app.ACTION_WIDGET_INFO"

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

            val launchFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT

            // ΑΛΛΑΓΗ (ζητήθηκε ρητά, 5/11): πατώντας πλέον στο ΚΥΡΙΟ σώμα του widget (widgetRoot) ΔΕΝ
            // ανοίγει η εφαρμογή -- κάνει REFRESH (βλ. ACTION_WIDGET_REFRESH/onReceive), ώστε ο χρήστης
            // να μπορεί να συγχρονίσει το widget με το πραγματικό βήμα ΤΗ ΣΤΙΓΜΗ που θέλει, ακόμα κι όσο
            // η εφαρμογή είναι τελείως κλειστή -- χωρίς να περιμένει το αυτόματο catch-up των 30 λεπτών.
            val refreshIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            refreshIntent.action = ACTION_WIDGET_REFRESH
            val refreshPendingIntent = android.app.PendingIntent.getBroadcast(context, 2, refreshIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, refreshPendingIntent)

            // ΝΕΟ: το άνοιγμα της εφαρμογής μεταφέρθηκε στο δικό του, ξεχωριστό κουμπί "Open App" κάτω
            // δεξιά -- δικό του explicit click target, ώστε να καταναλώνει το touch μόνο του και να ΜΗΝ
            // "διαρρέει" στο γονικό widgetRoot (που πλέον κάνει refresh).
            val launchIntent = Intent(context, MainActivity::class.java)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val openAppPendingIntent = android.app.PendingIntent.getActivity(context, 0, launchIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetOpenAppBtn, openAppPendingIntent)

            // ΝΕΟ: το κουμπί παλέτας έχει ΔΙΚΟ ΤΟΥ, ξεχωριστό PendingIntent -- BROADCAST (όχι activity), ώστε
            // να ΜΗΝ ανοίγει την εφαρμογή, μόνο να προχωράει το θέμα στο επόμενο (βλ. onReceive παρακάτω).
            // Ένα child view με δικό του explicit click target καταναλώνει το touch -- δεν "διαρρέει" στο
            // γονικό widgetRoot click (τυπικό, αποδεδειγμένο RemoteViews pattern, ίδιο με play/pause κουμπιά
            // πάνω σε media widgets).
            val cycleIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            cycleIntent.action = ACTION_CYCLE_THEME
            val cyclePendingIntent = android.app.PendingIntent.getBroadcast(context, 1, cycleIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetThemeBtn, cyclePendingIntent)

            // ΝΕΟ: κουκκίδα πληροφοριών πάνω δεξιά -- δικό της click target, broadcast ACTION_WIDGET_INFO.
            val infoIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            infoIntent.action = ACTION_WIDGET_INFO
            val infoPendingIntent = android.app.PendingIntent.getBroadcast(context, 3, infoIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetInfoBtn, infoPendingIntent)

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
        // ΝΕΟ (ΔΙΧΤΥ ΑΣΦΑΛΕΙΑΣ, βλ. πλήρες σχόλιο στο tryHardwareCatchUp παρακάτω): αυτό το onUpdate()
        // καλείται αυτόματα από το ίδιο το Android -- όταν προστίθεται το widget ΚΑΙ περιοδικά, το πολύ
        // κάθε updatePeriodMillis=1800000 (30', το ελάχιστο που επιτρέπει το OS) -- ΑΝΕΞΑΡΤΗΤΑ από το αν η
        // εφαρμογή/το Foreground Service είναι ζωντανά ή όχι. Το εκμεταλλευόμαστε εδώ ως ανεξάρτητο
        // "κόλλημα" με το υλικό (hardware) βηματόμετρο, για την περίπτωση που η διεργασία έχει πεθάνει
        // εντελώς.
        try { tryHardwareCatchUp(context) } catch (e: Exception) { /* μη κρίσιμο */ }
    }

    // ============================================================================================
    // ΔΙΟΡΘΩΣΗ ("το widget δεν ενημερώνεται όσο η εφαρμογή είναι ΕΝΤΕΛΩΣ κλειστή", bug report 5/11):
    // επιβεβαιώθηκε μέσω πραγματικού logcat (νέο PID σε ΚΑΘΕ επανάνοιγμα, "FytraStepForegroundService:
    // onCreate()" να τρέχει ξανά από το μηδέν κάθε φορά) ότι σε αρκετές συσκευές/ρυθμίσεις (εδώ: Samsung)
    // το Android σκοτώνει ΟΛΟΚΛΗΡΗ τη διεργασία της εφαρμογής -- ΟΧΙ μόνο το Activity -- παρά το Foreground
    // Service. Όταν η διεργασία είναι νεκρή, ΚΑΝΕΝΑΣ κώδικας μέσα στο FytraStepCounterPlugin δεν τρέχει πια
    // να ενημερώσει το widget -- αυτό είναι περιορισμός του ίδιου του Android (ΚΑΝΕΝΑ app, ούτε Google
    // Fit/Samsung Health, δεν μπορεί να τρέξει κώδικα με μηδενική διεργασία), όχι κάτι διορθώσιμο μόνο στο
    // plugin.
    //
    // ΛΥΣΗ (το καλύτερο εφικτό μέσα στα όρια του Android): το ΙΔΙΟ το widget διαβάζει απευθείας τον ΥΛΙΚΟ
    // (hardware) βηματόμετρο αισθητήρα TYPE_STEP_COUNTER -- αυτός ζει στο επίπεδο οδηγού/λειτουργικού,
    // ΠΑΝΤΑ ενεργός, ΑΝΕΞΑΡΤΗΤΑ από οποιαδήποτε διεργασία εφαρμογής. Το Android μας δίνει μια σύντομη
    // "ευκαιρία" εκτέλεσης σε κάθε onUpdate() (βλ. goAsync() παρακάτω) -- αρκετή για μία γρήγορη ανάγνωση.
    // ΔΕΝ είναι "απόλυτο real-time" (αυτό είναι τεχνικά αδύνατο με μηδενική διεργασία, σε ΚΑΝΕΝΑ app) --
    // είναι όμως ΕΓΓΥΗΜΕΝΟ αυτόματο "κόλλημα" το πολύ κάθε 30' (το ελάχιστο διάστημα που επιτρέπει το ίδιο
    // το OS), ΑΚΟΜΑ κι αν η εφαρμογή/service έχει πεθάνει εντελώς -- πολύ καλύτερο από "κολλημένο για
    // πάντα μέχρι το άνοιγμα". Όποτε η διεργασία ΕΙΝΑΙ ζωντανή, το ήδη υπάρχον ζωντανό push (από το
    // creditOneStep()/pushWidgetUpdateFromNativeCredit μέσα στο FytraStepCounterPlugin) συνεχίζει να δίνει
    // πολύ πιο συχνές (~5") ενημερώσεις -- αυτό εδώ είναι ΜΟΝΟ το δίχτυ ασφαλείας για όταν δεν είναι.
    //
    // ΧΡΗΣΙΜΟΠΟΙΕΙ το ΙΔΙΟ ακριβώς CLOSED_APP_PREFS_NAME/KEY_LAST_RAW_STEP_COUNTER(_TS) που ήδη διατηρεί
    // το FytraStepCounterPlugin.checkClosedAppSteps() -- ΙΔΙΑ πηγή αλήθειας, συνεπές delta. Ο υπολογισμός
    // του "σημερινού συνόλου" γίνεται ΜΟΝΟ όταν το αποθηκευμένο baseline (KEY_DAILY_BASELINE_STEPS) είναι
    // ΗΔΗ της ΣΗΜΕΡΙΝΗΣ μέρας (αλλιώς αγνοείται -- ασφαλές, το widget απλά περιμένει να ανοίξει η εφαρμογή
    // έστω μία φορά σήμερα πριν αρχίσει να "μαντεύει"). Ο ίδιος "ΠΟΤΕ δεν μειώνει" φρουρός με το
    // writeWidgetDisplayCacheGuarded εφαρμόζεται ΚΑΙ εδώ. ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 -- ξεχωριστό,
    // ανεξάρτητο νήμα λογικής, γράφει μόνο στο δικό του "display cache".
    // ============================================================================================
    private fun tryHardwareCatchUp(context: Context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val counterSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) ?: return
        val appCtx = context.applicationContext ?: context

        val closedAppPrefs = appCtx.getSharedPreferences(FytraStepCounterPlugin.CLOSED_APP_PREFS_NAME, Context.MODE_PRIVATE)
        val hasStoredBefore = closedAppPrefs.contains(FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER)
        val storedValueBefore = closedAppPrefs.getFloat(FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER, 0f)

        // ΝΕΟ: goAsync() -- ζητάει από το Android λίγο παραπάνω χρόνο εκτέλεσης πέρα από το κανονικό,
        // πολύ σύντομο παράθυρο ενός broadcast receiver, ΜΟΝΟ για όσο χρειάζεται αυτή η γρήγορη ανάγνωση
        // αισθητήρα. ΠΡΕΠΕΙ να κληθεί το pendingResult.finish() ΠΑΝΤΑ, σε κάθε πιθανό μονοπάτι εξόδου.
        val pendingResult = try { goAsync() } catch (e: Exception) { return }
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var oneShotListener: android.hardware.SensorEventListener
        val retryRunnables = mutableListOf<Runnable>()

        val finishOnce = {
            if (resolved.compareAndSet(false, true)) {
                try { sensorManager.unregisterListener(oneShotListener) } catch (e: Exception) {}
                retryRunnables.forEach { timeoutHandler.removeCallbacks(it) }
                try { pendingResult.finish() } catch (e: Exception) {}
            }
        }

        oneShotListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (resolved.get()) return
                try {
                    val rawValue = if (event.values.isNotEmpty()) event.values[0] else 0f
                    if (hasStoredBefore && rawValue >= storedValueBefore) {
                        val delta = (rawValue - storedValueBefore).toInt()
                        if (delta > 0) {
                            val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            val baselineDayKey = closedAppPrefs.getString(FytraStepCounterPlugin.KEY_DAILY_BASELINE_DAYKEY, "") ?: ""
                            val baselineSteps = closedAppPrefs.getInt(FytraStepCounterPlugin.KEY_DAILY_BASELINE_STEPS, 0)
                            if (baselineDayKey == todayKey) {
                                val totalToday = baselineSteps + delta
                                val widgetPrefs = appCtx.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
                                val strideM = widgetPrefs.getFloat(KEY_WIDGET_STRIDE_M, 0.75f)
                                val kcalVal = Math.round(totalToday * 0.04).toInt()
                                val kmVal = (totalToday * strideM) / 1000.0
                                val storedDayKey = widgetPrefs.getString(KEY_WIDGET_DAYKEY, "") ?: ""
                                val storedSteps = widgetPrefs.getInt(KEY_WIDGET_STEPS, 0)
                                // ΙΔΙΟΣ "ΠΟΤΕ δεν μειώνει" φρουρός με το writeWidgetDisplayCacheGuarded.
                                if (storedDayKey != todayKey || totalToday >= storedSteps) {
                                    widgetPrefs.edit()
                                        .putInt(KEY_WIDGET_STEPS, totalToday)
                                        .putInt(KEY_WIDGET_KCAL, kcalVal)
                                        .putFloat(KEY_WIDGET_KM, kmVal.toFloat())
                                        .putString(KEY_WIDGET_DAYKEY, todayKey)
                                        .putLong(KEY_WIDGET_UPDATED_TS, System.currentTimeMillis())
                                        .apply()
                                    requestWidgetUpdate(appCtx)
                                }
                            }
                        }
                    }
                    closedAppPrefs.edit()
                        .putFloat(FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER, rawValue)
                        .putLong(FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER_TS, System.currentTimeMillis())
                        .apply()
                } catch (e: Exception) { /* μη κρίσιμο */ }
                finishOnce()
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        try {
            // ΙΔΙΟ τέχνασμα batching+flush() με το checkClosedAppSteps() στο plugin -- ζητάει να μας δοθεί
            // η ΗΔΗ υπάρχουσα τιμή πιο άμεσα, χωρίς να περιμένουμε πραγματικό νέο βήμα.
            sensorManager.registerListener(oneShotListener, counterSensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST, Int.MAX_VALUE)
            try { sensorManager.flush(oneShotListener) } catch (e: Exception) {}
            for (delayMs in longArrayOf(1500L, 3000L, 5000L)) {
                val retry = Runnable { if (!resolved.get()) { try { sensorManager.flush(oneShotListener) } catch (e: Exception) {} } }
                retryRunnables.add(retry)
                timeoutHandler.postDelayed(retry, delayMs)
            }
            // ΣΗΜΑΝΤΙΚΟ: goAsync() δίνει ΠΕΡΙΟΡΙΣΜΕΝΟ χρόνο (λίγα δευτερόλεπτα) -- timeout αρκετά
            // σύντομο ώστε να προλάβει ΠΑΝΤΑ να κλείσει πριν το Android "τιμωρήσει" τον receiver.
            timeoutHandler.postDelayed({ finishOnce() }, 7000L)
        } catch (e: Exception) {
            finishOnce()
        }
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
        // ΝΕΟ (ζητήθηκε ρητά, 5/11): πάτημα στο κύριο σώμα του widget -- on-demand συγχρονισμός ΤΩΡΑ με
        // τον αισθητήρα του υλικού, ΑΝΕΞΑΡΤΗΤΑ από το αυτόματο 30λεπτο onUpdate(). Χρησιμοποιεί ΑΚΡΙΒΩΣ
        // την ίδια, ήδη δοκιμασμένη λογική (goAsync() + one-shot listener + flush retries + timeout) --
        // απλά καλείται απευθείας από εδώ αντί να περιμένει το περιοδικό onUpdate(). onReceive() είναι
        // ο ίδιος "ζωντανός" call stack που χρειάζεται το goAsync(), άρα είναι ασφαλές.
        if (intent.action == ACTION_WIDGET_REFRESH) {
            try { tryHardwareCatchUp(context) } catch (e: Exception) { /* μη κρίσιμο */ }
            return
        }
        // ΝΕΟ: κουκκίδα πληροφοριών -- απλό Toast, ΔΕΝ ανοίγει την εφαρμογή, ΔΕΝ αγγίζει κανένα κλειδί
        // μέτρησης. Toast.makeText() από broadcast receiver context δουλεύει κανονικά (δεν χρειάζεται
        // Activity) -- καθιερωμένο Android pattern για feedback πάνω σε home-screen widgets.
        if (intent.action == ACTION_WIDGET_INFO) {
            try {
                android.widget.Toast.makeText(
                    context,
                    "Πατώντας στο widget, συγχρονίζονται τα βήματα που μετρήθηκαν όσο η εφαρμογή είναι τελείως κλειστή. Όσο η εφαρμογή τρέχει στο παρασκήνιο, το widget ενημερώνεται μόνο του σε πραγματικό χρόνο.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) { /* μη κρίσιμο */ }
            return
        }
        super.onReceive(context, intent)
    }
}
