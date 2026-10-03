package com.fytra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
// FytraMidnightSnapshotReceiver
// ----------------------------------------------------------------------------
// ΝΕΟ (Σενάριο Γ — ζητήθηκε ρητά: "να μοιράζει τα βήματα στις σωστές μέρες, ακόμα κι
// αν ήταν κλειστό το κινητό ΠΟΛΛΕΣ μέρες και χωρίς wifi"). Αυτός ο receiver ΔΕΝ
// χρειάζεται καμία ζωντανή διεργασία/WebView/JS της εφαρμογής — τον ενεργοποιεί
// απευθείας το ίδιο το Android (AlarmManager / BOOT_COMPLETED), ΑΚΟΜΑ ΚΙ ΑΝ η
// εφαρμογή είναι ΕΝΤΕΛΩΣ κλειστή, αρκεί η συσκευή να έχει μπαταρία/να είναι αναμμένη.
//
// ΚΡΙΣΙΜΟ / ΑΠΑΡΑΒΑΤΟΣ ΟΡΟΣ (όπως και το FytraStepForegroundService.kt): αυτό το
// αρχείο ΔΕΝ αγγίζει, ΔΕΝ εισάγει και ΔΕΝ καλεί καμία συνάρτηση από το God Mode/v1/v2
// filter μέσα στο FytraStepCounterPlugin.kt. Διαβάζει ΜΟΝΟ τον ανεξάρτητο hardware
// TYPE_STEP_COUNTER αισθητήρα (το ίδιο, ήδη υπάρχον, απομονωμένο "Σενάριο Β" side-
// channel) και γράφει σε ΔΙΚΑ ΤΟΥ, ξεχωριστά κλειδιά SharedPreferences
// (DAILY_SNAPSHOT_KEY_PREFIX) — καμία επίδραση στη ζωντανή μέτρηση βημάτων.
//
// ΠΕΡΙΟΡΙΣΜΟΣ (ΕΙΛΙΚΡΙΝΗΣ, ίδιος με το ήδη υπάρχον checkClosedAppSteps()): ο
// TYPE_STEP_COUNTER αισθητήρας σε ΚΑΠΟΙΕΣ συσκευές/OEM ενημερώνει μόνο "on-change"
// (δεν απαντά καθόλου αν δεν γίνει ΚΑΝΕΝΑ νέο βήμα μέσα στο μικρό χρονικό παράθυρο
// που περιμένουμε) — στην πράξη οι περισσότερες συσκευές επαναλαμβάνουν αμέσως την
// τρέχουσα τιμή μόλις εγγραφεί νέος listener, το ίδιο ακριβώς στο οποίο βασίζεται ήδη
// το checkClosedAppSteps(). Αν για κάποια συγκεκριμένη νύχτα ο αισθητήρας δεν απαντήσει
// καθόλου, απλά ΔΕΝ θα υπάρχει στιγμιότυπο για εκείνη τη μέρα — ΚΑΝΕΝΑ βήμα δεν χάνεται,
// το JS απλά πέφτει πίσω στην παλιότερη, λιγότερο ακριβή μέθοδο (ενιαίο άθροισμα) μόνο
// για το κομμάτι που δεν μπορεί να διαιρεθεί σωστά.
//
// Χρησιμοποιεί setAndAllowWhileIdle (βλ. FytraStepCounterPlugin.scheduleMidnightSnapshotAlarm)
// -> ανεκτικό στο Doze, ΧΩΡΙΣ να απαιτεί το ειδικό permission SCHEDULE_EXACT_ALARM, με το
// κόστος μιας μικρής πιθανής καθυστέρησης (τυπικά λεπτά) στην ώρα πυροδότησης.
// ============================================================================
class FytraMidnightSnapshotReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FytraDebug"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action
            Log.d(TAG, "FytraMidnightSnapshotReceiver.onReceive: action=$action")
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                // Τα alarms του AlarmManager ΔΕΝ επιζούν επανεκκίνηση συσκευής -> ξαναπρογραμματίζουμε.
                FytraStepCounterPlugin.scheduleMidnightSnapshotAlarm(context)
                return
            }
            if (action == FytraStepCounterPlugin.ACTION_MIDNIGHT_SNAPSHOT) {
                takeSnapshotAndReschedule(context)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "FytraMidnightSnapshotReceiver.onReceive ΕΣΚΑΣΕ: " + e.message)
        }
    }

    private fun takeSnapshotAndReschedule(context: Context) {
        // ΠΑΝΤΑ ξαναπρογραμματίζουμε ΠΡΩΤΑ το επόμενο alarm, ό,τι κι αν συμβεί με τον αισθητήρα
        // παρακάτω — ώστε ένα μεμονωμένο αποτυχημένο στιγμιότυπο να ΜΗΝ "σταματήσει" οριστικά τον
        // μηχανισμό για όλες τις επόμενες μέρες.
        FytraStepCounterPlugin.scheduleMidnightSnapshotAlarm(context)

        val pendingResult = goAsync()
        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val counterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sensorManager == null || counterSensor == null) {
                Log.d(TAG, "takeSnapshotAndReschedule: δεν υπάρχει TYPE_STEP_COUNTER -> skip")
                pendingResult.finish()
                return
            }
            // "Χθες" σε σχέση με ΤΩΡΑ (τη στιγμή που πυροδοτήθηκε το alarm, λίγο μετά τα μεσάνυχτα) —
            // δηλαδή η ημέρα που μόλις έληξε.
            val yesterdayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            )
            val resolved = AtomicBoolean(false)
            val timeoutHandler = Handler(Looper.getMainLooper())
            lateinit var oneShotListener: SensorEventListener
            val timeoutRunnable = Runnable {
                if (resolved.compareAndSet(false, true)) {
                    try { sensorManager.unregisterListener(oneShotListener) } catch (e: Exception) {}
                    Log.d(TAG, "takeSnapshotAndReschedule: TIMEOUT -> δεν αποθηκεύτηκε στιγμιότυπο για $yesterdayKey")
                    pendingResult.finish()
                }
            }
            oneShotListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!resolved.compareAndSet(false, true)) return
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    try {
                        sensorManager.unregisterListener(this)
                        val rawValue = if (event.values.isNotEmpty()) event.values[0] else 0f
                        val prefs = context.getSharedPreferences(FytraStepCounterPlugin.DAILY_SNAPSHOT_PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putFloat(FytraStepCounterPlugin.DAILY_SNAPSHOT_KEY_PREFIX + yesterdayKey, rawValue).apply()
                        Log.d(TAG, "takeSnapshotAndReschedule: αποθηκεύτηκε στιγμιότυπο $yesterdayKey = $rawValue")
                    } catch (e: Exception) {
                        Log.d(TAG, "takeSnapshotAndReschedule (listener) ΕΣΚΑΣΕ: " + e.message)
                    } finally {
                        pendingResult.finish()
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(oneShotListener, counterSensor, SensorManager.SENSOR_DELAY_FASTEST)
            timeoutHandler.postDelayed(timeoutRunnable, 8000L)
        } catch (e: Exception) {
            Log.d(TAG, "takeSnapshotAndReschedule ΕΣΚΑΣΕ: " + e.message)
            try { pendingResult.finish() } catch (e2: Exception) {}
        }
    }
}
