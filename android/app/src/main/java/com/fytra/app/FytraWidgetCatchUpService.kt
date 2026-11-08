package com.fytra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

// ============================================================================================
// FytraWidgetCatchUpService
// --------------------------------------------------------------------------------------------
// ΝΕΟ (ζητήθηκε ρητά, 6/11): λύση στο bug report "πατάω το widget με κλειστή εφαρμογή και δεν
// συγχρονίζονται τα βήματα". Το ΠΑΛΙΟ μονοπάτι (tryHardwareCatchUp μέσω goAsync() στο
// FytraStepsWidgetProvider) περιορίζεται ΣΚΛΗΡΑ από το ίδιο το Android σε ~9-10 δευτερόλεπτα -- σε
// πολλές συσκευές (ειδικά Samsung, βλ. σχόλιο στο checkClosedAppSteps() του FytraStepCounterPlugin)
// ο αισθητήρας TYPE_STEP_COUNTER ΔΕΝ απαντάει καθόλου μέχρι να γίνει το ΕΠΟΜΕΝΟ πραγματικό βήμα -- αν
// ο χρήστης στέκεται ακίνητος τη στιγμή του tap, αυτό το μικρό παράθυρο μπορεί να λήξει χωρίς καμία
// ένδειξη, άρα χωρίς καμία ενημέρωση, σιωπηλά.
//
// ΛΥΣΗ: αυτό το Service τρέχει ΑΚΡΙΒΩΣ την ΙΔΙΑ λογική ανάγνωσης αισθητήρα
// (FytraStepsWidgetProvider.hardwareCatchUpCore -- ΚΑΜΙΑ νέα λογική μέτρησης, ΚΑΝΕΝΑ νέο listener,
// ΚΑΜΙΑ επίδραση στο God Mode/v1/v2 filter, ΙΔΙΟΣ "ΠΟΤΕ δεν μειώνει" φρουρός), απλά σε ΔΙΑΦΟΡΕΤΙΚΟ
// "σπίτι" (Service αντί για BroadcastReceiver+goAsync()) που ΔΕΝ έχει το σκληρό όριο των ~10s -- εδώ
// του δίνουμε έως 50 δευτερόλεπτα (CATCHUP_TIMEOUT_MS). Αυτό ΑΠΑΙΤΕΙ (απαίτηση του ίδιου του Android,
// όχι επιλογή μας) μια σύντομη, σιωπηλή (IMPORTANCE_LOW, χωρίς ήχο/δόνηση) ειδοποίηση όσο περιμένει --
// ο χρήστης ενημερώθηκε και συμφώνησε ρητά για αυτό. Μόλις έρθει η πρώτη ένδειξη από τον αισθητήρα (ή
// λήξει το παράθυρο), η ειδοποίηση αφαιρείται ΑΜΕΣΑ και το service σταματάει μόνο του -- ΔΕΝ παραμένει
// μόνιμα.
//
// ΔΕΝ έχει ΚΑΜΙΑ σχέση με το ξεχωριστό FytraStepForegroundService (αυτό κρατάει απλά ζωντανή τη
// διεργασία όσο η εφαρμογή είναι ελαχιστοποιημένη -- ΔΕΝ κάνει καμία ανάγνωση αισθητήρα μόνο του, γι'
// αυτό δεν μπορούσε να χρησιμοποιηθεί για αυτό το σκοπό).
//
// Ξεκινάει ΑΠΟΚΛΕΙΣΤΙΚΑ από το ρητό tap του χρήστη στο κύριο σώμα του widget (ACTION_WIDGET_REFRESH,
// βλ. FytraStepsWidgetProvider.onReceive()) -- ΠΟΤΕ από το αυτόματο onUpdate() κάθε 30' (εκείνο συνεχίζει
// να χρησιμοποιεί το ΠΑΛΙΟ, σύντομο goAsync() μονοπάτι, αφού δεν είναι ρητή ενέργεια χρήστη -- ασφαλέστερο
// ως προς τους περιορισμούς εκκίνησης υπηρεσιών παρασκηνίου του Android).
// ============================================================================================
class FytraWidgetCatchUpService : Service() {

    companion object {
        private const val TAG = "FytraDebug"
        private const val CHANNEL_ID = "fytra_widget_catchup_channel"
        private const val NOTIFICATION_ID = 4822
        private const val CATCHUP_TIMEOUT_MS = 50000L
    }

    private var finished = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ΝΕΟ (διαγνωστικό, 8/11 -- "το tap δεν κάνει τίποτα πια", 32 φορές onDestroy() σε 46"):
        // καταγράφει ότι ΞΕΚΙΝΗΣΕ το onStartCommand -- αν στο log λείπει αυτή η γραμμή πριν από ένα
        // onDestroy(), το service "πεθαίνει" ΠΡΙΝ καν μπει εδώ (π.χ. το ίδιο το Android το σκοτώνει
        // αμέσως). ΚΑΘΑΡΑ διαγνωστικό -- καμία αλλαγή λογικής.
        Log.d(TAG, "FytraWidgetCatchUpService: onStartCommand() ΞΕΚΙΝΗΣΕ, startId=" + startId)
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.d(TAG, "FytraWidgetCatchUpService: startForeground ΟΚ")
        } catch (e: Exception) {
            // ΔΕΝ μπορούμε να τρέξουμε χωρίς startForeground() -- σταματάμε καθαρά. Το onReceive() στο
            // FytraStepsWidgetProvider κάνει ΗΔΗ fallback στο παλιό σύντομο μονοπάτι αν ΑΥΤΟ το
            // startForegroundService()/startService() πετάξει εξαίρεση πριν φτάσει έως εδώ -- αυτό εδώ
            // είναι δεύτερο δίχτυ ασφαλείας για την σπάνια περίπτωση που η εκκίνηση πέτυχε αλλά το
            // startForeground() μέσα στο service αποτύχει (π.χ. OEM-specific περιορισμός).
            Log.d(TAG, "FytraWidgetCatchUpService: startForeground ΕΣΚΑΣΕ: " + e.javaClass.simpleName + ": " + e.message)
            stopSelf()
            return START_NOT_STICKY
        }

        val appCtx = applicationContext
        try {
            FytraStepsWidgetProvider.hardwareCatchUpCore(appCtx, CATCHUP_TIMEOUT_MS) {
                finishAndStop()
            }
        } catch (e: Exception) {
            Log.d(TAG, "FytraWidgetCatchUpService: hardwareCatchUpCore ΕΣΚΑΣΕ: " + e.javaClass.simpleName + ": " + e.message)
            finishAndStop()
        }

        return START_NOT_STICKY
    }

    private fun finishAndStop() {
        if (finished) {
            Log.d(TAG, "FytraWidgetCatchUpService: finishAndStop() ξανακλήθηκε, ΑΓΝΟΕΙΤΑΙ (ήδη finished)")
            return
        }
        finished = true
        Log.d(TAG, "FytraWidgetCatchUpService: finishAndStop() -- σταματάει το service")
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        try { stopSelf() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FytraWidgetCatchUpService: onDestroy()")
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Συγχρονισμός widget FYTRA",
                NotificationManager.IMPORTANCE_LOW // LOW: χωρίς ήχο/δόνηση, σύντομη ειδοποίηση
            )
            channel.description = "Σύντομη, σιωπηλή ειδοποίηση όσο το widget συγχρονίζει τα βήματα με κλειστή την εφαρμογή. Κλείνει αυτόματα σε λίγα δευτερόλεπτα."
            channel.setShowBadge(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        // Ίδιο fallback icon pattern με το FytraStepForegroundService.
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
