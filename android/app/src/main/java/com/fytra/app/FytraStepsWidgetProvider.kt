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
        // ΝΕΟ (ΔΙΟΡΘΩΣΗ, ζητήθηκε ρητά, 6/11, bug "ο πραγματικός μετρητής δεν πιστώθηκε κανονικά όταν
        // άνοιξα την εφαρμογή μετά από tap στο widget"): ΔΙΚΟ ΤΟΥ, ξεχωριστό "σημείο αναφοράς" του raw
        // αισθητήρα TYPE_STEP_COUNTER, ΑΠΟΚΛΕΙΣΤΙΚΑ για το hardwareCatchUpCore() (tap-to-refresh widget).
        // ΠΡΙΝ, το hardwareCatchUpCore() μοιραζόταν το ΙΔΙΟ σημείο αναφοράς
        // (FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER/_TS) με το checkClosedAppSteps() του plugin
        // (αυτό που τρέχει ΚΑΝΟΝΙΚΑ όταν ανοίγεις την εφαρμογή και πιστώνει τον ΠΡΑΓΜΑΤΙΚΟ σου μετρητή) --
        // αυτό σήμαινε ότι όποιο από τα δύο έτρεχε ΠΡΩΤΟ "κατανάλωνε" τη διαφορά και μετακινούσε το κοινό
        // σημείο αναφοράς, αφήνοντας στο άλλο μόνο το υπόλοιπο. Αποθηκεύεται στο ΙΔΙΟ WIDGET_PREFS_NAME
        // (όχι στο CLOSED_APP_PREFS_NAME του plugin) ακριβώς για να ΜΗΝ αγγίζει ΠΟΤΕ πια το σημείο αναφοράς
        // που χρησιμοποιεί το checkClosedAppSteps() για τον πραγματικό μετρητή -- έτσι το άνοιγμα της
        // εφαρμογής συνεχίζει να βρίσκει ΠΑΝΤΑ το πλήρες, σωστό delta, ό,τι κι αν έχει κάνει το widget στο
        // μεταξύ. Το widget δείχνει ΜΟΝΟ μια προσωρινή προεπισκόπηση (δικό του "σπίτι", ξεχωριστό cache) --
        // μόλις ανοίξεις την εφαρμογή, ο πραγματικός μετρητής παίρνει το σωστό, πλήρες νούμερο και το
        // ξαναστέλνει στο widget μέσω του ήδη υπάρχοντος updateWidgetData()/writeWidgetDisplayCacheGuarded()
        // (ΙΔΙΟΣ "ΠΟΤΕ δεν μειώνει" φρουρός) -- οπότε τελικά ΠΑΝΤΑ ευθυγραμμίζονται. ΚΑΜΙΑ επίδραση στο God
        // Mode/v1/v2 filter ή στο checkClosedAppSteps() -- μόνο το tap-to-refresh του widget πλέον διαβάζει/
        // γράφει εδώ.
        const val KEY_WIDGET_LAST_RAW_STEP_COUNTER = "widgetLastRawStepCounter"
        const val KEY_WIDGET_LAST_RAW_STEP_COUNTER_TS = "widgetLastRawStepCounterTs"
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

        // ΔΙΟΡΘΩΣΗ (bug report, "το Toast βγαίνει κομμένο κάτω στην οθόνη"): το Toast εγκαταλείφθηκε --
        // από Android 11+, η θέση/gravity ενός Toast ΔΕΝ τηρείται πλέον όταν προέρχεται από διεργασία
        // που δεν είναι στο προσκήνιο (όπως ένας widget BroadcastReceiver), το OS το αναγκάζει πάντα στην
        // προεπιλεγμένη θέση κάτω -- εκτός ελέγχου μας. Αντί γι' αυτό, η κουκκίδα "i" τώρα ανοιγοκλείνει
        // ΕΝΑ POPUP μέσα στο ΙΔΙΟ το widget (βλ. widgetInfoBubble στο layout) -- πλήρως ελεγχόμενο από
        // εμάς μέσω visibility GONE/VISIBLE, άρα πάντα εντός των ορίων.
        const val ACTION_WIDGET_INFO = "com.fytra.app.ACTION_WIDGET_INFO"
        // ΝΕΟ: το κουμπί "✕" πάνω στο ίδιο το popup -- κλείνει το popup.
        const val ACTION_WIDGET_INFO_HIDE = "com.fytra.app.ACTION_WIDGET_INFO_HIDE"
        // ΝΕΟ: αποθηκεύει αν το popup είναι ΗΔΗ ανοιχτό (boolean) -- διαβάζεται από buildRemoteViews() για
        // να αποφασίσει αν το widgetInfoBubble είναι VISIBLE ή GONE σε κάθε (ανα)σχεδιασμό του widget.
        const val KEY_WIDGET_INFO_VISIBLE = "widgetInfoVisible"

        internal data class WidgetTheme(
            val bgRes: Int,
            val badgeRes: Int,
            val statRes: Int,
            val iconWrapRes: Int,
            val accentColor: Int,
            val labelColor: Int,
            val ringStartColor: Int,
            val ringEndColor: Int,
            // ΝΕΟ (ζητήθηκε ρητά, 6/11): ίδιο φόντο με bgRes αλλά τυλιγμένο σε <ripple> -- χρησιμοποιείται
            // ΜΟΝΟ στο κλικαρόμενο root view (widgetRoot/widgetCompactRoot) ώστε το πάτημα για refresh να
            // δίνει οπτικό εφέ "πατήματος" (ripple/highlight), ενώ τα υπόλοιπα views (κουμπιά θέματος κλπ.)
            // κρατάνε το απλό bgRes.
            val pressableBgRes: Int
        )

        internal val THEMES = arrayOf(
            WidgetTheme(R.drawable.widget_bg_0, R.drawable.badge_bg_0, R.drawable.stat_bg_0, R.drawable.icon_wrap_bg_0,
                Color.parseColor("#FFE07A"), Color.parseColor("#B89A5A"), Color.parseColor("#FFE07A"), Color.parseColor("#E8A83D"),
                R.drawable.widget_bg_0_pressable),
            WidgetTheme(R.drawable.widget_bg_1, R.drawable.badge_bg_1, R.drawable.stat_bg_1, R.drawable.icon_wrap_bg_1,
                Color.parseColor("#7FD8FF"), Color.parseColor("#6F97AC"), Color.parseColor("#7FD8FF"), Color.parseColor("#2E8FD9"),
                R.drawable.widget_bg_1_pressable),
            WidgetTheme(R.drawable.widget_bg_2, R.drawable.badge_bg_2, R.drawable.stat_bg_2, R.drawable.icon_wrap_bg_2,
                Color.parseColor("#8AFFC4"), Color.parseColor("#6FAE8C"), Color.parseColor("#8AFFC4"), Color.parseColor("#2ECC71"),
                R.drawable.widget_bg_2_pressable),
            WidgetTheme(R.drawable.widget_bg_3, R.drawable.badge_bg_3, R.drawable.stat_bg_3, R.drawable.icon_wrap_bg_3,
                Color.parseColor("#FF8FA3"), Color.parseColor("#B87A8A"), Color.parseColor("#FF8FA3"), Color.parseColor("#E63950"),
                R.drawable.widget_bg_3_pressable),
            WidgetTheme(R.drawable.widget_bg_4, R.drawable.badge_bg_4, R.drawable.stat_bg_4, R.drawable.icon_wrap_bg_4,
                Color.parseColor("#C9A3FF"), Color.parseColor("#9A86B8"), Color.parseColor("#C9A3FF"), Color.parseColor("#9D5CE8"),
                R.drawable.widget_bg_4_pressable),
            WidgetTheme(R.drawable.widget_bg_5, R.drawable.badge_bg_5, R.drawable.stat_bg_5, R.drawable.icon_wrap_bg_5,
                Color.parseColor("#FFB15C"), Color.parseColor("#B8905A"), Color.parseColor("#FFB15C"), Color.parseColor("#E8762E"),
                R.drawable.widget_bg_5_pressable)
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

            // ΝΕΟ (ζητήθηκε ρητά): αν ο χρήστης έχει βάλει ΚΑΙ το μικρό (2x1) widget, ενημερώνεται ΚΑΙ αυτό
            // εδώ -- μοιράζονται το ΙΔΙΟ WIDGET_PREFS_NAME "display cache", απλά διαφορετικό RemoteViews
            // layout (βλ. FytraStepsWidgetProviderCompact.buildRemoteViewsCompact()).
            try {
                val mgr2 = AppWidgetManager.getInstance(context)
                val compactIds = mgr2.getAppWidgetIds(android.content.ComponentName(context, FytraStepsWidgetProviderCompact::class.java))
                if (compactIds.isNotEmpty()) {
                    val compactViews = FytraStepsWidgetProviderCompact.buildRemoteViewsCompact(context)
                    for (id in compactIds) { mgr2.updateAppWidget(id, compactViews) }
                }
            } catch (e: Exception) { /* μη κρίσιμο -- απλά δεν υπάρχει/δεν ανανεώθηκε το compact widget */ }

            // ΝΕΟ (ζητήθηκε ρητά, v2): το ίδιο και για την τρίτη επιλογή μεγέθους (3x1) -- ΤΩΡΑ
            // χρησιμοποιεί το ΙΔΙΟ compact RemoteViews με το 2x1 (buildRemoteViewsCompact), ΟΧΙ το πλήρες
            // buildRemoteViews(), αφού το 3x1 κράτησε το ίδιο μειωμένο περιεχόμενο με το 2x1 (βλ. σχόλιο
            // στο FytraStepsWidgetProviderMedium.kt).
            try {
                val mgr3 = AppWidgetManager.getInstance(context)
                val mediumIds = mgr3.getAppWidgetIds(android.content.ComponentName(context, FytraStepsWidgetProviderMedium::class.java))
                if (mediumIds.isNotEmpty()) {
                    // ΔΙΟΡΘΩΣΗ (6/11, v2): το 3x1 έχει ΤΩΡΑ δικό του layout (widget_steps_medium) --
                    // βλ. σχόλιο στο FytraStepsWidgetProviderMedium.kt.
                    val mediumViews = FytraStepsWidgetProviderCompact.buildRemoteViewsCompact(context, R.layout.widget_steps_medium)
                    for (id in mediumIds) { mgr3.updateAppWidget(id, mediumViews) }
                }
            } catch (e: Exception) { /* μη κρίσιμο -- απλά δεν υπάρχει/δεν ανανεώθηκε το medium widget */ }

            // ΝΕΟ (ζητήθηκε ρητά, 6/11): τέταρτη επιλογή μεγέθους (3x2) -- ΧΡΗΣΙΜΟΠΟΙΕΙ το πλήρες
            // buildRemoteViews() (ΟΧΙ το compact), αφού το 3x2 κρατάει ακριβώς το περιεχόμενο του 4x2
            // (βλ. σχόλιο στο FytraStepsWidgetProviderWide.kt).
            try {
                val mgr4 = AppWidgetManager.getInstance(context)
                val wideIds = mgr4.getAppWidgetIds(android.content.ComponentName(context, FytraStepsWidgetProviderWide::class.java))
                if (wideIds.isNotEmpty()) {
                    val wideViews = buildRemoteViews(context)
                    for (id in wideIds) { mgr4.updateAppWidget(id, wideViews) }
                }
            } catch (e: Exception) { /* μη κρίσιμο -- απλά δεν υπάρχει/δεν ανανεώθηκε το wide widget */ }
        }

        // ΝΕΟ: ζωγραφίζει τον κυκλικό δακτύλιο προόδου σε ένα τετράγωνο Bitmap -- μονοπάτι "φόντο" (λεπτός,
        // ημιδιάφανος κύκλος) + μονοπάτι "πρόοδος" (gradient τόξο, στρογγυλεμένα άκρα, ξεκινάει από πάνω
        // όπως στο εγκεκριμένο mockup). Καλείται ΚΑΘΕ update -- αρκετά ελαφρύ (μία μόνο Bitmap σχεδίαση
        // μερικών δεκάδων pixel) για να τρέχει σε κάθε ανανέωση χωρίς αισθητή επιβάρυνση.
        internal fun drawProgressRingBitmap(sizePx: Int, strokeWidthPx: Float, progressFraction: Float, theme: WidgetTheme): Bitmap {
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

        internal fun themeAt(index: Int): WidgetTheme {
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
            val infoVisible = prefs.getBoolean(KEY_WIDGET_INFO_VISIBLE, false)

            // -------- φόντα θέματος --------
            // ΔΙΟΡΘΩΣΗ (ζητήθηκε ρητά, 6/11): pressableBgRes αντί για bgRes -- δίνει ripple/highlight εφέ
            // "πατήματος" στο tap-to-refresh.
            views.setInt(R.id.widgetRoot, "setBackgroundResource", theme.pressableBgRes)
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

            // ΝΕΟ: κουκκίδα πληροφοριών πάνω δεξιά -- δικό της click target, broadcast ACTION_WIDGET_INFO
            // (ανοίγει το popup, βλ. onReceive).
            val infoIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            infoIntent.action = ACTION_WIDGET_INFO
            val infoPendingIntent = android.app.PendingIntent.getBroadcast(context, 3, infoIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetInfoBtn, infoPendingIntent)

            // ΝΕΟ: το κουμπί "✕" πάνω στο ίδιο το popup -- δικό του click target, broadcast
            // ACTION_WIDGET_INFO_HIDE (κλείνει το popup).
            val infoHideIntent = Intent(context, FytraStepsWidgetProvider::class.java)
            infoHideIntent.action = ACTION_WIDGET_INFO_HIDE
            val infoHidePendingIntent = android.app.PendingIntent.getBroadcast(context, 4, infoHideIntent, launchFlags)
            views.setOnClickPendingIntent(R.id.widgetInfoCloseBtn, infoHidePendingIntent)

            // ΝΕΟ: το ίδιο το popup εμφανίζεται/κρύβεται ανάλογα με το αποθηκευμένο KEY_WIDGET_INFO_VISIBLE
            // -- πάντα GONE εξ ορισμού (δεν ανοίγει μόνο του ποτέ, μόνο όταν πατηθεί το "i").
            views.setViewVisibility(R.id.widgetInfoBubble, if (infoVisible) android.view.View.VISIBLE else android.view.View.GONE)

            return views
        }

        // Απλή μορφοποίηση χιλιάδων (π.χ. 12.345) -- ανεξάρτητη από το el-GR locale ρύθμισμα του JS,
        // ώστε να δουλεύει αξιόπιστα και σε συσκευές χωρίς ελληνικό locale εγκατεστημένο.
        internal fun formatThousands(n: Int): String {
            return try {
                String.format(Locale.US, "%,d", n).replace(",", ".")
            } catch (e: Exception) { n.toString() }
        }

        // ============================================================================================
        // ΚΟΙΝΟΧΡΗΣΤΟ (βλ. πλήρες σχόλιο στο tryHardwareCatchUp): διαβάζει τον ΥΛΙΚΟ (hardware) αισθητήρα
        // βημάτων TYPE_STEP_COUNTER μία φορά, με retries/timeout, και ενημερώνει το κοινό "display cache"
        // (WIDGET_PREFS_NAME) με τον ίδιο "ΠΟΤΕ δεν μειώνει" φρουρό. ΔΕΝ καλεί το δικό της goAsync() --
        // αυτό πρέπει να γίνει από το ΔΙΚΟ ΤΟΥ instance κάθε provider class (FytraStepsWidgetProvider ή
        // FytraStepsWidgetProviderCompact) -- αντ' αυτού παίρνει ένα onDone() callback που καλείται όταν
        // τελειώσει (είτε βρήκε κάτι, είτε όχι, είτε timeout) ώστε ο καλών να κλείσει το ΔΙΚΟ του
        // pendingResult.finish(). Έτσι την ίδια, ήδη δοκιμασμένη λογική τη μοιράζονται ΚΑΙ τα δύο μεγέθη
        // widget, χωρίς διπλό/αποκλίνον κώδικα. ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 filter.
        // ============================================================================================
        // ΔΙΟΡΘΩΣΗ (ζητήθηκε ρητά, 6/11): προστέθηκε παράμετρος timeoutMs (προεπιλογή 9000ms, ΙΔΙΑ με
        // πριν -- ΚΑΜΙΑ αλλαγή συμπεριφοράς για το αυτόματο onUpdate() κάθε 30' ή το tryHardwareCatchUp()
        // μέσω goAsync(), που δεν περνάνε καμία τιμή και άρα συνεχίζουν να παίρνουν 9000ms). ΤΟ ΝΕΟ
        // FytraWidgetCatchUpService (tap-to-refresh, τρέχει ΩΣ Service, ΟΧΙ μέσα σε goAsync(), άρα ΔΕΝ έχει
        // το σκληρό όριο των ~10s) καλεί αυτή τη συνάρτηση με πολύ μεγαλύτερο timeoutMs (50000ms), ώστε να
        // προλάβει ένα φρέσκο βήμα του χρήστη χωρίς να χρειάζεται να το κάνει ρητά/σκόπιμα. Η λίστα των
        // retry delays παρακάτω φιλτράρεται ώστε να μη δημιουργεί ΑΧΡΗΣΤΑ Runnable πέρα από το εκάστοτε
        // timeoutMs -- με το προεπιλεγμένο 9000ms, τα retries που όντως τρέχουν είναι ΑΚΡΙΒΩΣ τα ίδια με
        // πριν (500/1500/3000/5000/7000ms). ΚΑΜΙΑ άλλη αλλαγή λογικής -- ίδιος υπολογισμός delta/baseline,
        // ίδιος "ΠΟΤΕ δεν μειώνει" φρουρός, καμία επίδραση στο God Mode/v1/v2.
        internal fun hardwareCatchUpCore(context: Context, timeoutMs: Long = 9000L, onDone: () -> Unit) {
            // ΝΕΟ (διαγνωστικό, 8/11): μπήκαμε καν μέσα στο hardwareCatchUpCore, με τι timeoutMs.
            android.util.Log.d("FytraDebug", "hardwareCatchUpCore: ΞΕΚΙΝΑΕΙ, timeoutMs=" + timeoutMs)
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            val counterSensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
            if (sensorManager == null || counterSensor == null) {
                android.util.Log.d("FytraDebug", "hardwareCatchUpCore: ΤΕΛΟΣ ΑΜΕΣΑ -- sensorManager=" + sensorManager + " counterSensor=" + counterSensor)
                onDone(); return
            }
            val appCtx = context.applicationContext ?: context

            // ΝΕΟ (ζητήθηκε ρητά, 7/11 — "να λειτουργούν συμπληρωματικά"): δεύτερος αισθητήρας,
            // TYPE_STEP_DETECTOR, χρησιμοποιείται ΑΠΟΚΛΕΙΣΤΙΚΑ ΩΣ "ΞΥΠΝΗΤΗΡΙ" -- ΔΕΝ δίνει ΠΟΤΕ ο ίδιος
            // το νούμερο βημάτων (δεν στέλνει αθροιστική τιμή, μόνο "έγινε 1 βήμα ΤΩΡΑ"). Ο ΜΟΝΟΣ που
            // συνεχίζει να υπολογίζει το πραγματικό delta είναι, όπως πριν, ο TYPE_STEP_COUNTER (μέσω του
            // oneShotListener παρακάτω) -- ΚΑΜΙΑ αλλαγή στη λογική υπολογισμού/God Mode. Ο στόχος είναι
            // ΜΟΝΟ να ζητάμε φρέσκο flush() από τον STEP_COUNTER ΑΜΕΣΩΣ μόλις όντως γίνει ένα βήμα, αντί
            // να περιμένουμε τον δικό του αργό, παρτιδοποιημένο ρυθμό ενημέρωσης σε αρκετές συσκευές.
            // Αν η συσκευή δεν έχει καθόλου αυτόν τον αισθητήρα (detectorSensor == null), απλά τον
            // αγνοούμε -- η υπάρχουσα λογική (flush() + οι χρονισμένες επαναλήψεις) συνεχίζει ΑΚΡΙΒΩΣ όπως
            // πριν, χωρίς καμία επίδραση.
            val detectorSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR)

            val closedAppPrefs = appCtx.getSharedPreferences(FytraStepCounterPlugin.CLOSED_APP_PREFS_NAME, Context.MODE_PRIVATE)
            // ΔΙΟΡΘΩΣΗ (6/11): ΔΙΚΟ ΤΟΥ σημείο αναφοράς (WIDGET_PREFS_NAME), ΟΧΙ πια το κοινόχρηστο
            // FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER -- βλ. πλήρες σχόλιο στον ορισμό του
            // KEY_WIDGET_LAST_RAW_STEP_COUNTER παραπάνω.
            val widgetRawPrefs = appCtx.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            val hasStoredBefore = widgetRawPrefs.contains(KEY_WIDGET_LAST_RAW_STEP_COUNTER)
            val storedValueBefore = widgetRawPrefs.getFloat(KEY_WIDGET_LAST_RAW_STEP_COUNTER, 0f)
            // ΔΙΟΡΘΩΣΗ ΠΡΑΓΜΑΤΙΚΟΥ BUG (ζητήθηκε ρητά, 8/11 -- "κάνω παραπάνω βήματα κ μετράει λιγότερα
            // όσο η εφαρμογή είναι κλειστή και πατάω tap refresh"): το ΠΑΛΙΟ delta μετριόταν από το ΔΙΚΟ
            // ΤΟΥ (του widget) σημείο αναφοράς (storedValueBefore από πάνω) -- αυτό προχωράει σε ΚΑΘΕ tap,
            // άρα μετράει μόνο "πόσα βήματα έγιναν ΑΠΟ ΤΟ ΠΡΟΗΓΟΥΜΕΝΟ tap". Η baselineSteps όμως
            // (closedAppPrefs.KEY_DAILY_BASELINE_STEPS, παρακάτω) ΔΕΝ αλλάζει καθόλου όσο η εφαρμογή
            // παραμένει κλειστή -- οπότε στο 2ο, 3ο, ... tap μετά το πρώτο, το άθροισμα
            // "baselineSteps + (λίγα βήματα από το προηγούμενο tap)" είναι ΛΑΝΘΑΣΜΕΝΟ: αγνοεί τα βήματα
            // που είχαν ήδη περπατηθεί ΠΡΙΝ το προηγούμενο tap, τα οποία ΠΟΤΕ δεν μπήκαν στην baselineSteps
            // (αφού αυτή ενημερώνεται ΜΟΝΟ όταν ανοίγει η εφαρμογή). ΔΙΟΡΘΩΣΗ: το delta πρέπει να μετριέται
            // ΠΑΝΤΑ από το σημείο αναφοράς ΤΗΣ ΕΦΑΡΜΟΓΗΣ (closedAppPrefs.KEY_LAST_RAW_STEP_COUNTER -- το
            // ΙΔΙΟ σημείο με το οποίο αντιστοιχεί η τρέχουσα baselineSteps, και που ΔΕΝ προχωράει στα tap,
            // μόνο όταν ανοίξει πραγματικά η εφαρμογή), ΟΧΙ από το δικό του widgetRawPrefs σημείο.
            val hasBaselineRaw = closedAppPrefs.contains(FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER)
            val baselineRawValue = closedAppPrefs.getFloat(FytraStepCounterPlugin.KEY_LAST_RAW_STEP_COUNTER, 0f)
            // ΝΕΟ (διαγνωστικό, 8/11): τι ξέραμε ΠΡΙΝ ζητήσουμε φρέσκια τιμή, και υπάρχει ο δεύτερος αισθητήρας.
            android.util.Log.d("FytraDebug", "hardwareCatchUpCore: hasStoredBefore=" + hasStoredBefore + " storedValueBefore=" + storedValueBefore + " hasBaselineRaw=" + hasBaselineRaw + " baselineRawValue=" + baselineRawValue + " detectorSensor=" + (detectorSensor != null))

            val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            lateinit var oneShotListener: android.hardware.SensorEventListener
            lateinit var detectorListener: android.hardware.SensorEventListener
            val retryRunnables = mutableListOf<Runnable>()

            val finishOnce = {
                if (resolved.compareAndSet(false, true)) {
                    try { sensorManager.unregisterListener(oneShotListener) } catch (e: Exception) {}
                    // ΝΕΟ: ξεκλειδώνει ΚΑΙ τον δεύτερο, "ξυπνητήρι" listener -- αν δεν είχε καθόλου
                    // καταχωρηθεί (π.χ. η συσκευή δεν έχει TYPE_STEP_DETECTOR), το unregisterListener()
                    // είναι ασφαλές no-op.
                    if (detectorSensor != null) { try { sensorManager.unregisterListener(detectorListener) } catch (e: Exception) {} }
                    retryRunnables.forEach { timeoutHandler.removeCallbacks(it) }
                    try { onDone() } catch (e: Exception) {}
                }
            }

            oneShotListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    if (resolved.get()) return
                    try {
                        val rawValue = if (event.values.isNotEmpty()) event.values[0] else 0f
                        // ΔΙΟΡΘΩΣΗ (8/11, βλ. πλήρες σχόλιο στον ορισμό του baselineRawValue παραπάνω):
                        // το delta μετριέται ΠΛΕΟΝ από το σημείο αναφοράς ΤΗΣ ΕΦΑΡΜΟΓΗΣ (baselineRawValue),
                        // ΟΧΙ από το δικό του widget σημείο (storedValueBefore) -- έτσι κάθε tap δείχνει
                        // ΣΩΣΤΑ όλα τα βήματα από τότε που άνοιξε τελευταία φορά η εφαρμογή, όχι μόνο όσα
                        // έγιναν από το αμέσως προηγούμενο tap.
                        if (hasBaselineRaw && rawValue >= baselineRawValue) {
                            val delta = (rawValue - baselineRawValue).toInt()
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
                        widgetRawPrefs.edit()
                            .putFloat(KEY_WIDGET_LAST_RAW_STEP_COUNTER, rawValue)
                            .putLong(KEY_WIDGET_LAST_RAW_STEP_COUNTER_TS, System.currentTimeMillis())
                            .apply()
                        // ΝΕΟ (διαγνωστικό, 8/11): ήρθε φρέσκια τιμή από τον πραγματικό STEP_COUNTER.
                        android.util.Log.d("FytraDebug", "hardwareCatchUpCore/oneShotListener: ΗΡΘΕ rawValue=" + rawValue)
                    } catch (e: Exception) {
                        android.util.Log.d("FytraDebug", "hardwareCatchUpCore/oneShotListener: ΕΣΚΑΣΕ: " + e.javaClass.simpleName + ": " + e.message)
                    }
                    finishOnce()
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }

            // ΝΕΟ: ο "ξυπνητήρι" listener -- ΔΕΝ υπολογίζει τίποτα, ΔΕΝ γράφει πουθενά, ΔΕΝ καλεί ποτέ
            // finishOnce() ο ίδιος. Το ΜΟΝΟ που κάνει είναι να ζητάει ΑΜΕΣΩΣ flush() από τον
            // oneShotListener (τον πραγματικό, TYPE_STEP_COUNTER) μόλις ειδοποιηθεί για ένα φρέσκο βήμα --
            // η απόφαση/ο υπολογισμός γίνεται ΠΑΝΤΑ ΜΟΝΟ μέσα στον oneShotListener από πάνω, όπως πριν.
            detectorListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    if (resolved.get()) return
                    // ΝΕΟ (διαγνωστικό, 8/11): χτύπησε το "ξυπνητήρι" -- ζητάμε flush στον πραγματικό μετρητή.
                    android.util.Log.d("FytraDebug", "hardwareCatchUpCore/detectorListener: ΧΤΥΠΗΣΕ, ζητάω flush")
                    try { sensorManager.flush(oneShotListener) } catch (e: Exception) {}
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }

            try {
                // ΙΔΙΟ τέχνασμα batching+flush() με το checkClosedAppSteps() στο plugin -- ζητάει να μας
                // δοθεί η ΗΔΗ υπάρχουσα τιμή πιο άμεσα, χωρίς να περιμένουμε πραγματικό νέο βήμα. ΣΗΜΕΙΩΣΗ
                // (ζητήθηκε ρητά, 6/11, διάγνωση "tap δεν κάνει τπτ"): σε πολλές συσκευές (βλ. ΙΔΙΟ σχόλιο
                // στο checkClosedAppSteps() του plugin) ο TYPE_STEP_COUNTER ΔΕΝ απαντάει καθόλου μέχρι να
                // γίνει το ΕΠΟΜΕΝΟ πραγματικό βήμα -- το flush() ΔΕΝ εγγυάται άμεση απάντηση σε τέτοιες
                // συσκευές. ΔΙΟΡΘΩΣΗ: πιο συχνές/γρήγορες επαναλήψεις flush() (ξεκινώντας πολύ νωρίτερα) ΚΑΙ
                // μεγαλύτερο συνολικό παράθυρο αναμονής, ώστε να έχει τις μέγιστες πιθανότητες να προλάβει
                // ένα φρέσκο βήμα του χρήστη μέσα στο διάστημα αυτό -- ΔΕΝ λύνει το θεμελιώδες περιορισμό
                // (χρειάζεται τουλάχιστον ένα νέο βήμα μέσα στο παράθυρο), απλά το μεγαλώνει όσο επιτρέπεται
                // με ασφάλεια.
                sensorManager.registerListener(oneShotListener, counterSensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST, Int.MAX_VALUE)
                try { sensorManager.flush(oneShotListener) } catch (e: Exception) {}
                // ΝΕΟ: καταχωρίζει ΚΑΙ τον "ξυπνητήρι" listener, ΜΟΝΟ αν η συσκευή τον υποστηρίζει --
                // ΧΩΡΙΣ maxReportLatency (0), αφού εδώ ΘΕΛΟΥΜΕ την πιο άμεση δυνατή ειδοποίηση ανά βήμα,
                // ΟΧΙ batching.
                if (detectorSensor != null) {
                    try { sensorManager.registerListener(detectorListener, detectorSensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST) } catch (e: Exception) {}
                }
                val allRetryDelays = longArrayOf(500L, 1500L, 3000L, 5000L, 7000L, 10000L, 15000L, 20000L, 30000L, 40000L)
                for (delayMs in allRetryDelays) {
                    if (delayMs >= timeoutMs) continue
                    val retry = Runnable { if (!resolved.get()) { try { sensorManager.flush(oneShotListener) } catch (e: Exception) {} } }
                    retryRunnables.add(retry)
                    timeoutHandler.postDelayed(retry, delayMs)
                }
                timeoutHandler.postDelayed({
                    // ΝΕΟ (διαγνωστικό, 8/11): τελειώσαμε επειδή χτύπησε το χρονικό όριο, όχι επειδή ήρθε βήμα.
                    android.util.Log.d("FytraDebug", "hardwareCatchUpCore: ΧΤΥΠΗΣΕ ΤΟ TIMEOUT (" + timeoutMs + "ms)")
                    finishOnce()
                }, timeoutMs)
            } catch (e: Exception) {
                android.util.Log.d("FytraDebug", "hardwareCatchUpCore: ΕΣΚΑΣΕ στο setup: " + e.javaClass.simpleName + ": " + e.message)
                finishOnce()
            }
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
    // ΔΙΟΡΘΩΣΗ (6/11, bug "ο πραγματικός μετρητής δεν πιστώθηκε κανονικά όταν άνοιξα την εφαρμογή μετά από
    // tap στο widget"): ΠΡΙΝ, χρησιμοποιούσε το ΙΔΙΟ ακριβώς CLOSED_APP_PREFS_NAME/KEY_LAST_RAW_STEP_COUNTER
    // (_TS) που διατηρεί το FytraStepCounterPlugin.checkClosedAppSteps() -- αποδείχτηκε ΛΑΘΟΣ: όποιο από τα
    // δύο (tap στο widget ή άνοιγμα εφαρμογής) έτρεχε ΠΡΩΤΟ, μετακινούσε το κοινό σημείο αναφοράς και άφηνε
    // στο άλλο μόνο το υπόλοιπο delta -> ο πραγματικός μετρητής πιστωνόταν ελλιπώς όταν προηγούνταν tap στο
    // widget. ΤΩΡΑ χρησιμοποιεί ΔΙΚΟ ΤΟΥ, ξεχωριστό σημείο αναφοράς (WIDGET_PREFS_NAME/
    // KEY_WIDGET_LAST_RAW_STEP_COUNTER(_TS), βλ. ορισμός παραπάνω) -- ΔΕΝ αγγίζει πλέον ΚΑΘΟΛΟΥ το σημείο
    // αναφοράς του checkClosedAppSteps(), το οποίο έτσι βρίσκει ΠΑΝΤΑ το πλήρες, σωστό delta όταν ανοίγει η
    // εφαρμογή -- ανεξάρτητα από το τι έχει κάνει το widget στο μεταξύ. Ο υπολογισμός
    // του "σημερινού συνόλου" γίνεται ΜΟΝΟ όταν το αποθηκευμένο baseline (KEY_DAILY_BASELINE_STEPS) είναι
    // ΗΔΗ της ΣΗΜΕΡΙΝΗΣ μέρας (αλλιώς αγνοείται -- ασφαλές, το widget απλά περιμένει να ανοίξει η εφαρμογή
    // έστω μία φορά σήμερα πριν αρχίσει να "μαντεύει"). Ο ίδιος "ΠΟΤΕ δεν μειώνει" φρουρός με το
    // writeWidgetDisplayCacheGuarded εφαρμόζεται ΚΑΙ εδώ. ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 -- ξεχωριστό,
    // ανεξάρτητο νήμα λογικής, γράφει μόνο στο δικό του "display cache".
    // ============================================================================================
    // ΔΙΟΡΘΩΣΗ (ώστε να το ξαναχρησιμοποιεί ΚΑΙ το νέο, μικρό widget 2x1 -- βλ. FytraStepsWidgetProviderCompact):
    // το goAsync() είναι μέθοδος του ΙΔΙΟΥ του BroadcastReceiver instance, πρέπει να κληθεί από το ΔΙΚΟ
    // ΤΟΥ onReceive()/onUpdate() κάθε provider class -- ΔΕΝ μπορεί να "δανειστεί" από άλλη κλάση. Γι' αυτό
    // η πραγματική λογική αισθητήρα μεταφέρθηκε στο κοινόχρηστο companion.hardwareCatchUpCore() παρακάτω
    // (παίρνει ένα callback onDone() αντί να καλεί το δικό της pendingResult.finish() απευθείας) -- εδώ
    // μένει μόνο το "λεπτό" wrapper που κάνει goAsync() και περνάει το pendingResult.finish() ως onDone.
    private fun tryHardwareCatchUp(context: Context) {
        val pendingResult = try { goAsync() } catch (e: Exception) { return }
        hardwareCatchUpCore(context) {
            try { pendingResult.finish() } catch (e: Exception) {}
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
        // ΔΙΟΡΘΩΣΗ (ζητήθηκε ρητά, 6/11, bug report "πατάω το widget και δεν γίνεται τπτ"): το ΠΑΛΙΟ
        // tryHardwareCatchUp() μέσω goAsync() έχει σκληρό όριο Android ~9-10s -- σε πολλές συσκευές
        // (ειδικά Samsung) ο TYPE_STEP_COUNTER δεν απαντάει καθόλου μέχρι το ΕΠΟΜΕΝΟ πραγματικό βήμα, άρα
        // αν ο χρήστης στέκεται ακίνητος τη στιγμή του tap, το παράθυρο λήγει ΧΩΡΙΣ καμία ενημέρωση.
        // ΛΥΣΗ (ζητήθηκε ρητά, ο χρήστης ενημερώθηκε και συμφώνησε για τη σύντομη, σιωπηλή ειδοποίηση που
        // αυτό συνεπάγεται): το tap ΤΩΡΑ ξεκινάει το FytraWidgetCatchUpService -- ΙΔΙΑ ΑΚΡΙΒΩΣ λογική
        // ανάγνωσης αισθητήρα (hardwareCatchUpCore, καμία αλλαγή στον υπολογισμό/God Mode), απλά σε Service
        // αντί για goAsync(), που επιτρέπει πολύ μεγαλύτερο παράθυρο αναμονής (50s). Αν η εκκίνηση του
        // service αποτύχει για οποιονδήποτε λόγο (π.χ. περιορισμός συγκεκριμένου OEM), γίνεται ΑΜΕΣΗ
        // υποχώρηση (fallback) στο παλιό, εγγυημένα ασφαλές σύντομο μονοπάτι, ώστε το tap να ΜΗΝ μείνει
        // ποτέ χωρίς καμία προσπάθεια συγχρονισμού.
        if (intent.action == ACTION_WIDGET_REFRESH) {
            // ΝΕΟ (διαγνωστικό, 8/11 — "το tap δεν κάνει τίποτα πια"): καταγράφει ΤΙ αποφασίζεται εδώ,
            // ώστε το επόμενο log να δείξει ξεκάθαρα αν μπήκαμε καν εδώ, τι έδειξε το isRunning, και αν η
            // εκκίνηση του service πέτυχε ή πέταξε εξαίρεση. ΚΑΘΑΡΑ διαγνωστικό -- καμία αλλαγή λογικής.
            android.util.Log.d("FytraDebug", "onReceive: ACTION_WIDGET_REFRESH, FytraStepForegroundService.isRunning=" + FytraStepForegroundService.isRunning)
            // ΔΙΟΡΘΩΣΗ (ζητήθηκε ρητά, 6/11, bug "πρόσθεσε 60 λάθος βήματα ενώ η εφαρμογή ΔΕΝ ήταν
            // κλειστή"): η raw ανάγνωση αισθητήρα (μέσω FytraWidgetCatchUpService/hardwareCatchUpCore)
            // είναι ΜΟΝΟ για την περίπτωση που η εφαρμογή είναι ΠΡΑΓΜΑΤΙΚΑ κλειστή -- όταν το ζωντανό
            // FytraStepForegroundService ήδη τρέχει, ο πραγματικός μετρητής ΗΔΗ συγχρονίζει το widget
            // μόνος του (pushWidgetUpdateFromNativeCredit, κάθε ~5"), οπότε μια ΕΠΙΠΛΕΟΝ raw ανάγνωση
            // εδώ θα πρόσθετε ΜΗ φιλτραρισμένο αριθμό πάνω από τον ήδη σωστό (αυτό ήταν το bug). Σε αυτή
            // την περίπτωση απλά ξαναζωγραφίζουμε το widget από το ΗΔΗ σωστό cache -- καμία ανάγνωση
            // αισθητήρα, καμία επίδραση στη μέτρηση.
            if (FytraStepForegroundService.isRunning) {
                try { requestWidgetUpdate(context) } catch (e: Exception) { /* μη κρίσιμο */ }
                return
            }
            try {
                val svcIntent = Intent(context, FytraWidgetCatchUpService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
                // ΝΕΟ (διαγνωστικό, 8/11): η κλήση startForegroundService/startService ΔΕΝ πέταξε εξαίρεση.
                android.util.Log.d("FytraDebug", "onReceive: startForegroundService/startService ΟΚ (δεν έσκασε)")
            } catch (e: Exception) {
                android.util.Log.d("FytraDebug", "onReceive: startForegroundService/startService ΕΣΚΑΣΕ: " + e.javaClass.simpleName + ": " + e.message)
                try { tryHardwareCatchUp(context) } catch (e2: Exception) {
                    android.util.Log.d("FytraDebug", "onReceive: ΚΑΙ το fallback tryHardwareCatchUp ΕΣΚΑΣΕ: " + e2.javaClass.simpleName + ": " + e2.message)
                }
            }
            return
        }
        // ΔΙΟΡΘΩΣΗ (bug report, "το Toast βγαίνει κομμένο κάτω στην οθόνη"): εγκαταλείφθηκε το Toast
        // (βλ. πλήρες σχόλιο στη δήλωση ACTION_WIDGET_INFO παραπάνω) -- τώρα απλά ανοίγει το popup μέσα
        // στο ίδιο το widget (KEY_WIDGET_INFO_VISIBLE=true) και ξαναζωγραφίζει. ΔΕΝ ανοίγει την εφαρμογή,
        // ΔΕΝ αγγίζει κανένα κλειδί μέτρησης.
        if (intent.action == ACTION_WIDGET_INFO) {
            try {
                val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_WIDGET_INFO_VISIBLE, true).apply()
                requestWidgetUpdate(context)
            } catch (e: Exception) { /* μη κρίσιμο */ }
            return
        }
        // ΝΕΟ: το κουμπί "✕" πάνω στο popup -- κλείνει το popup ξανά.
        if (intent.action == ACTION_WIDGET_INFO_HIDE) {
            try {
                val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_WIDGET_INFO_VISIBLE, false).apply()
                requestWidgetUpdate(context)
            } catch (e: Exception) { /* μη κρίσιμο */ }
            return
        }
        super.onReceive(context, intent)
    }
}
