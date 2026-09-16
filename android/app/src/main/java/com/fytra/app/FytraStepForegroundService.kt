package com.fytra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

// ============================================================================
// FytraStepForegroundService
// ----------------------------------------------------------------------------
// ΣΚΟΠΟΣ ΚΑΙ ΜΟΝΟ ΣΚΟΠΟΣ: να κρατάει τη διεργασία της εφαρμογής ζωντανή όσο ο
// χρήστης έχει ελαχιστοποιήσει (background) το FYTRA, ΧΩΡΙΣ να το έχει κλείσει.
// Το Android απαιτεί μια μόνιμη, ορατή ειδοποίηση για να επιτρέψει σε μια
// εφαρμογή να "τρέχει" προτεραιοποιημένα στο παρασκήνιο (foreground service).
//
// ΚΡΙΣΙΜΟ / ΑΠΑΡΑΒΑΤΟΣ ΟΡΟΣ: αυτό το αρχείο ΔΕΝ εγγράφεται ΠΟΤΕ ως SensorEventListener,
// ΔΕΝ κάνει registerListener/unregisterListener σε ΚΑΝΕΝΑ αισθητήρα, ΔΕΝ εισάγει και
// ΔΕΝ καλεί ΚΑΜΙΑ συνάρτηση από το FytraStepCounterPlugin.kt (God Mode, v1 filter,
// v2 shadow κλπ). Η μέτρηση βημάτων συνεχίζει να γίνεται ΑΠΟΚΛΕΙΣΤΙΚΑ μέσα στο ήδη
// υπάρχον plugin, ακριβώς όπως πριν — αυτό το service απλά εμποδίζει το Android να
// σκοτώσει τη διεργασία που το φιλοξενεί.
// ============================================================================
class FytraStepForegroundService : Service() {

    companion object {
        private const val TAG = "FytraDebug"
        private const val CHANNEL_ID = "fytra_step_tracking_channel"
        private const val NOTIFICATION_ID = 4821
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FytraStepForegroundService: onCreate()")
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ΔΙΟΡΘΩΣΗ: το startForeground() καλείται ΚΑΙ εδώ, όχι μόνο στο onCreate() παραπάνω. Το
        // onCreate() καλείται ΜΟΝΟ μία φορά ποτέ, την πρώτη φορά που δημιουργείται η υπηρεσία — αν η
        // υπηρεσία είναι ήδη ζωντανή (π.χ. η εφαρμογή απλά ελαχιστοποιήθηκε/ξανανοίχτηκε χωρίς να
        // σκοτωθεί πραγματικά η διεργασία), οι επόμενες κλήσεις του start() από το plugin περνάνε ΜΟΝΟ
        // από εδώ. Χωρίς αυτή τη γραμμή, αν ο χρήστης έσβηνε χειροκίνητα την ειδοποίηση (swipe), δεν
        // ξαναεμφανιζόταν ΠΟΤΕ, παρόλο που η μέτρηση συνέχιζε κανονικά. Το startForeground() είναι
        // ασφαλές να καλείται επανειλημμένα με το ίδιο NOTIFICATION_ID — απλά ξαναδείχνει/ανανεώνει την
        // ίδια ειδοποίηση, καμία παρενέργεια.
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY: αν η διεργασία σκοτωθεί παρόλα αυτά από το σύστημα (π.χ. πολύ
        // επιθετική εξοικονόμηση μπαταρίας OEM), το Android θα προσπαθήσει να την
        // ξανασηκώσει αργότερα. ΔΕΝ είναι 100% εγγυημένο σε όλες τις συσκευές/OEM.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "FytraStepForegroundService: onDestroy()")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Μέτρηση βημάτων FYTRA",
                NotificationManager.IMPORTANCE_LOW // LOW: χωρίς ήχο/δόνηση, μόνο σιωπηλή, μόνιμη ειδοποίηση
            )
            channel.description = "Κρατάει ενεργή την ακριβή μέτρηση βημάτων όσο το FYTRA είναι στο παρασκήνιο."
            channel.setShowBadge(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        // Άνοιγμα της εφαρμογής αν ο χρήστης πατήσει πάνω στην ειδοποίηση — προαιρετική
        // βελτίωση UX, δεν επηρεάζει καθόλου τη μέτρηση.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE
        else
            0
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, pendingIntentFlags)
        }

        // Χρησιμοποιούμε το ήδη υπάρχον app icon του project (ic_launcher) αντί να
        // απαιτούμε καινούριο drawable resource. Αν για οποιονδήποτε λόγο δεν βρεθεί,
        // πέφτουμε πίσω σε ένα ασφαλές, ενσωματωμένο system icon του Android.
        val iconResId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
            .let { if (it != 0) it else android.R.drawable.ic_dialog_info }

        val builder = Notification.Builder(this)
            .setContentTitle("FYTRA")
            .setContentText("Η μέτρηση βημάτων είναι ενεργή στο παρασκήνιο.")
            .setSmallIcon(iconResId)
            .setOngoing(true)

        if (contentPendingIntent != null) builder.setContentIntent(contentPendingIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(CHANNEL_ID)

        return builder.build()
    }
}
