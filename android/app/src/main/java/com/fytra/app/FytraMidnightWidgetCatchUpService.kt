package com.fytra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ============================================================================================
// FytraMidnightWidgetCatchUpService
// --------------------------------------------------------------------------------------------
// ΝΕΟ (ζητήθηκε ρητά, 12/11 — "η νυχτερινή απόπειρα widget rollover αποτυγχάνει ΠΑΝΤΑ με sensor
// timeout στα 8000ms, επιβεβαιωμένο από πραγματικά logs 7/7 αποτυχιών"). Η ΠΑΛΙΑ
// attemptMidnightWidgetRollover() στο FytraMidnightSnapshotReceiver.kt καλούσε
// FytraStepsWidgetProvider.hardwareCatchUpCore() ΜΕΣΑ σε goAsync() του BroadcastReceiver, που έχει
// σκληρό όριο Android ~9-10s -- ΙΔΙΟΣ ΑΚΡΙΒΩΣ περιορισμός με το tap-to-refresh ΠΡΙΝ τη διόρθωσή
// του (βλ. FytraWidgetCatchUpService.kt, 6/11). ΛΥΣΗ: ΙΔΙΑ ΑΚΡΙΒΩΣ συνταγή -- τρέχει η ΗΔΗ
// υπάρχουσα, δοκιμασμένη hardwareCatchUpCore() σε Service (όχι goAsync()), με πολύ μεγαλύτερο
// παράθυρο αναμονής (15000ms -- ΙΔΙΟ με το checkClosedAppSteps() που αποδεδειγμένα δουλεύει).
//
// ΑΠΟΜΟΝΩΣΗ (σκόπιμη, ζητήθηκε ρητά "χωρίς να αγγίξει τίποτα άλλο"): αυτό είναι ΞΕΧΩΡΙΣΤΗ κλάση
// από το FytraWidgetCatchUpService (tap-to-refresh) -- ΔΙΚΟ ΤΗΣ notification channel/ID, ΔΙΚΗ ΤΗΣ
// δήλωση στο Manifest. ΔΕΝ τροποποιείται καθόλου το FytraWidgetCatchUpService.kt. Η ΜΟΝΗ κοινή
// λογική που καλείται είναι η ΗΔΗ υπάρχουσα hardwareCatchUpCore() -- ΚΑΜΙΑ αλλαγή εκεί, ΚΑΜΙΑ νέα
// λογική μέτρησης/God Mode/v1/v2, ΙΔΙΟΣ "ΠΟΤΕ δεν μειώνει" φρουρός.
//
// Μετά το hardwareCatchUpCore(), ΑΚΡΙΒΩΣ η ΙΔΙΑ λογική απόφασης (πέτυχε / προγραμματίζεται επόμενη
// απόπειρα / εξαντλήθηκαν) που είχε ΠΡΙΝ το attemptMidnightWidgetRollover() -- απλά μεταφέρθηκε
// εδώ μέσα, ΧΩΡΙΣ καμία αλλαγή στα βήματα της.
//
// Απαιτεί (απαίτηση του ίδιου του Android, όχι επιλογή) μια σύντομη, σιωπηλή (IMPORTANCE_LOW,
// χωρίς ήχο/δόνηση) ειδοποίηση όσο περιμένει -- ο χρήστης ενημερώθηκε ρητά και συμφώνησε.
// Ξεκινάει ΑΠΟΚΛΕΙΣΤΙΚΑ από AlarmManager alarm (ίδια εξαίρεση background-start όπως ήδη
// χρησιμοποιείται για τα υπόλοιπα μεσονυχτιου alarms αυτού του αρχείου) -- ΠΟΤΕ από ρητό tap
// χρήστη (αυτό συνεχίζει να πηγαίνει στο δικό του, ξεχωριστό FytraWidgetCatchUpService).
// ============================================================================================
class FytraMidnightWidgetCatchUpService : Service() {

    companion object {
        private const val TAG = "FytraDebug"
        private const val CHANNEL_ID = "fytra_midnight_widget_catchup_channel"
        private const val NOTIFICATION_ID = 4829
        // ΙΔΙΟ με το ήδη αποδεδειγμένα-δουλεύον checkClosedAppSteps() (15s) -- ΟΧΙ τα 8s του παλιού
        // goAsync() μονοπατιού, ΟΧΙ τα 50s του tap-to-refresh (δεν χρειάζεται τόσο, είναι
        // αυτόματη/όχι ρητή ενέργεια χρήστη).
        private const val CATCHUP_TIMEOUT_MS = 15000L
    }

    private var finished = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val attempt = intent?.getIntExtra("attempt", 0) ?: 0
        Log.d(TAG, "FytraMidnightWidgetCatchUpService: onStartCommand() ΞΕΚΙΝΗΣΕ, attempt=$attempt")
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.d(TAG, "FytraMidnightWidgetCatchUpService: startForeground ΕΣΚΑΣΕ: " + e.javaClass.simpleName + ": " + e.message)
            stopSelf()
            return START_NOT_STICKY
        }

        val appCtx = applicationContext
        try {
            FytraStepsWidgetProvider.hardwareCatchUpCore(appCtx, CATCHUP_TIMEOUT_MS) {
                try {
                    finishAttempt(appCtx, attempt)
                } catch (e: Exception) {
                    Log.d(TAG, "FytraMidnightWidgetCatchUpService: finishAttempt ΕΣΚΑΣΕ: " + e.message)
                } finally {
                    finishAndStop()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "FytraMidnightWidgetCatchUpService: hardwareCatchUpCore ΕΣΚΑΣΕ: " + e.javaClass.simpleName + ": " + e.message)
            finishAndStop()
        }

        return START_NOT_STICKY
    }

    // ΙΔΙΑ ΑΚΡΙΒΩΣ λογική απόφασης που είχε ΠΡΙΝ το attemptMidnightWidgetRollover() στο
    // FytraMidnightSnapshotReceiver.kt -- μόνο μεταφέρθηκε εδώ μέσα, καμία αλλαγή βημάτων.
    private fun finishAttempt(context: Context, attempt: Int) {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        val closedAppPrefs = context.getSharedPreferences(FytraStepCounterPlugin.CLOSED_APP_PREFS_NAME, Context.MODE_PRIVATE)
        val baselineDayKeyAfter = closedAppPrefs.getString(FytraStepCounterPlugin.KEY_DAILY_BASELINE_DAYKEY, "") ?: ""
        if (baselineDayKeyAfter == todayKey) {
            Log.d(TAG, "FytraMidnightWidgetCatchUpService(#$attempt): ΠΕΤΥΧΕ -> widget μηδενίστηκε για $todayKey")
            FytraStepCounterPlugin.appendSnapshotDiag(context, "WIDGET_ROLLOVER #$attempt: ΠΕΤΥΧΕ (Service, 15s), widget μηδενίστηκε")
        } else if (attempt < FytraStepCounterPlugin.MIDNIGHT_WIDGET_RETRY_MAX_ATTEMPTS) {
            Log.d(TAG, "FytraMidnightWidgetCatchUpService(#$attempt): ΑΠΕΤΥΧΕ (sensor timeout) -> προγραμματίζεται απόπειρα #" + (attempt + 1))
            FytraStepCounterPlugin.scheduleMidnightWidgetRetryAlarm(context, attempt + 1)
        } else {
            Log.d(TAG, "FytraMidnightWidgetCatchUpService(#$attempt): εξαντλήθηκαν οι απόπειρες -> αναμονή κανονικού 30λεπτου refresh widget")
            FytraStepCounterPlugin.appendSnapshotDiag(context, "WIDGET_ROLLOVER: εξαντλήθηκαν $attempt απόπειρες (Service, 15s), αναμονή κανονικού refresh")
        }
    }

    private fun finishAndStop() {
        if (finished) return
        finished = true
        Log.d(TAG, "FytraMidnightWidgetCatchUpService: finishAndStop() -- σταματάει το service")
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        try { stopSelf() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FytraMidnightWidgetCatchUpService: onDestroy()")
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Νυχτερινός συγχρονισμός widget FYTRA",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Σύντομη, σιωπηλή ειδοποίηση γύρω στα μεσάνυχτα όσο το widget προσπαθεί να συγχρονίσει μόνο του τα βήματα. Κλείνει αυτόματα σε λίγα δευτερόλεπτα."
            channel.setShowBadge(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val iconResId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
            .let { if (it != 0) it else android.R.drawable.ic_dialog_info }

        val builder = Notification.Builder(this)
            .setContentTitle("FYTRA")
            .setContentText("Συγχρονισμός βημάτων...")
            .setSmallIcon(iconResId)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(CHANNEL_ID)

        return builder.build()
    }
}
