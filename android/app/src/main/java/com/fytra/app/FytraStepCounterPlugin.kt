package com.fytra.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@CapacitorPlugin(
    name = "FytraStepCounter",
    permissions = [Permission(strings = [Manifest.permission.ACTIVITY_RECOGNITION], alias = "activity")]
)
class FytraStepCounterPlugin : Plugin(), SensorEventListener {

    companion object {
        const val TAG = "FytraDebug"

        // ============================================================================
        // ΚΕΝΤΡΙΚΗ ΡΥΘΜΙΣΗ v2 (FYTRA STEP ENGINE v2 — SHADOW MODE)
        // Όλες οι σταθερές του v2 εδώ, με σχόλιο μονάδας/σκοπού — καμία "μαγική τιμή" σκόρπια στον κώδικα.
        // ΚΑΜΙΑ από αυτές δεν επηρεάζει το πραγματικό (v1/legacy) μέτρημα — μόνο το παράλληλο v2 shadow.
        // ============================================================================
        const val V2_TARGET_WINDOW_SEC = 2.0            // πόσα δευτερόλεπτα ιστορικού κρατάμε για περιοδικότητα/robust στατιστικές
        const val V2_MIN_BUFFER_SAMPLES = 20             // ελάχιστο μέγεθος ring buffer (ασφάλεια σε πολύ αργή δειγματοληψία)
        const val V2_MAX_BUFFER_SAMPLES = 400            // μέγιστο μέγεθος ring buffer (ασφάλεια σε πολύ γρήγορη δειγματοληψία/μνήμη)
        const val V2_AUTOCORR_MIN_LAG_SEC = 0.25         // ταχύτατο βήμα/τρέξιμο (~240 βήματα/λεπτό όριο ασφαλείας)
        const val V2_AUTOCORR_MAX_LAG_SEC = 1.10         // πολύ αργό βάδισμα (~55 βήματα/λεπτό όριο ασφαλείας)
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από 2ο πραγματικό shadow test με πλήρη διαγνωστικά): το αρχικό 0.35 ήταν καθαρή
        // υπόθεση, ΟΧΙ μέτρηση. Τα πραγματικά logs έδειξαν ότι το κανονικό περπάτημα με το κινητό στο χέρι
        // (όχι σταθεροποιημένο) δίνει periodicity συνήθως 0.13-0.31 — πολύ πιο θορυβώδες σήμα απ' όσο
        // υποθέσαμε. Ξεκάθαρα μη-περπάτημα (μεγάλα κενά/artifacts) έδειξε 0.03-0.08. Νέο όριο ανάμεσα στα
        // δύο, βασισμένο σε πραγματικά δεδομένα.
        const val V2_AUTOCORR_MIN_PERIODICITY = 0.10     // ελάχιστη κανονικοποιημένη συσχέτιση [0..1] — αναθεωρήθηκε από 0.35 σε 0.10 βάσει πραγματικών μετρήσεων
        const val V2_AUTOCORR_RECOMPUTE_EVERY_N = 8      // υπολογίζουμε autocorrelation κάθε Ν δείγματα (έλεγχος κόστους CPU), όχι σε κάθε δείγμα
        const val V2_GYRO_BASELINE_ALPHA = 0.98          // πόσο αργά προσαρμόζεται η "ήσυχη" βάση γυροσκοπίου (πιο κοντά στο 1 = πιο αργά)
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικό shadow test): το αρχικό όριο (2.6x) ήταν πολύ αυστηρό — το φυσιολογικό
        // κούνημα χεριού ΚΑΤΑ το περπάτημα ανεβάζει το γυροσκόπιο πολύ πάνω από τη "ήσυχη" βάση, οπότε η πύλη
        // απέρριπτε ΚΑΘΕ πραγματικό βήμα, όχι μόνο τυχαίες χειρονομίες. Χαλαρώνουμε δραστικά μέχρι να έχουμε
        // πραγματικά δεδομένα σύγκρισης ΓΙΑ ΝΑ βαθμονομήσουμε σωστά — προτιμότερο να ΜΗΝ γίνεται καθόλου
        // αυστηρός φραγμός τώρα, παρά να μπλοκάρει αδίκως πραγματικό βάδισμα.
        const val V2_GYRO_HIGH_RATIO = 6.0               // πόσες φορές πάνω από τη βάση θεωρείται "έντονη περιστροφή"
        const val V2_GYRO_PENALTY_REJECT_ABOVE = 0.85    // πόσο υψηλή πρέπει να είναι η ποινή για να απορριφθεί ΤΕΛΙΚΑ ένα χτύπημα (πολύ πιο ανεκτικό από πριν)
        const val V2_MEDIAN_MAD_K = 3.2                  // πολλαπλασιαστής MAD για το robust κατώφλι (median + K*MAD)
        const val V2_MIN_THRESHOLD = 0.30                // απόλυτο ελάχιστο κατώφλι έντασης (ίδιες μονάδες με v1)
        const val V2_MAX_THRESHOLD = 6.0                 // απόλυτο μέγιστο κατώφλι έντασης — ασφάλεια ενάντια σε ακραία δεδομένα
        const val V2_MIN_STEP_INTERVAL_MS = 300L         // ίδιο απόλυτο όριο ασφαλείας με v1 (~200 βήματα/λεπτό)
        const val V2_MAX_STEP_INTERVAL_MS = 2000L        // ΠΛΑΤΥΤΕΡΟ από το v1 (850ms) -> δεν αποκλείει ηλικιωμένους/πολύ αργό βάδισμα
        const val V2_MAX_INTERVAL_RATIO_CHANGE = 1.8     // ελαφρώς πιο ανεκτικό από v1 (1.6)
        const val V2_REQUIRED_CONSECUTIVE = 3            // ίδιο ξεκίνημα με v1 — η βελτίωση είναι η ποιότητα κάθε ελέγχου
        const val V2_LOG_EVERY_N_LEGACY_STEPS = 5        // πόσο συχνά καταγράφουμε σύγκριση legacy/v2 στα logs
    }

    private lateinit var sensorManager: SensorManager

    // Οι "επίσημοι" αισθητήρες βημάτων της Samsung — τους κρατάμε ΜΟΝΟ για διαγνωστικά logs πλέον.
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var loadError: String? = null

    // Δικός μας, native αλγόριθμος μέτρησης βημάτων πάνω στον απλό επιταχυνσιόμετρο (accelerometer).
    private var linearAccelSensor: Sensor? = null
    private var rawAccelSensor: Sensor? = null
    private var usingLinearAccel = false

    // ΝΕΟ (v2, μόνο διαγνωστικά/βελτίωση ποιότητας gravity — ΔΕΝ αλλάζει το v1 μέτρημα).
    private var osGravitySensor: Sensor? = null
    private var osGravity = FloatArray(3)
    private var osGravityAvailable = false

    // ΝΕΟ (v2 ΜΟΝΟ): γυροσκόπιο για anti-gesture/anti-rotation scoring.
    private var gyroSensor: Sensor? = null
    private var hasGyroscope = false
    private var gyroBaselineRms = 0.02
    private var latestGyroRms = 0.0

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    // ΝΕΟ (v2 μόνο): ΞΕΧΩΡΙΣΤΟ background thread για gravity/gyroscope, ώστε το thread του επιταχυνσιόμετρου
    // (που τροφοδοτεί το ΠΑΡΑΓΩΓΙΚΟ v1 μέτρημα) να ΜΗΝ επιβαρύνεται καθόλου από επιπλέον sensor events —
    // πλήρης απομόνωση, ίδιος ακριβώς χρονισμός με πριν την προσθήκη του v2.
    private var v2AuxSensorThread: HandlerThread? = null
    private var v2AuxSensorHandler: Handler? = null

    // --- Κατάσταση του αλγορίθμου ανίχνευσης βήματος v1 (peak detection με προσαρμοστικό όριο) ---
    // ΑΥΤΟ ΤΟ ΤΜΗΜΑ ΕΙΝΑΙ 100% ΑΝΕΠΑΦΟ ΑΠΟ ΤΗΝ ΠΡΟΗΓΟΥΜΕΝΗ, ΔΟΚΙΜΑΣΜΕΝΗ ΕΚΔΟΣΗ.
    private var gravity = FloatArray(3)
    private var gravityInit = false
    private var vSmooth = 0.0
    private var vSmoothInit = false
    private var trendSlow = 0.0
    private var trendInit = false
    private var lastSampleSign = 0
    private var lastRisingZeroCrossMs = 0L
    private val ZERO_CROSS_TO_PEAK_MAX_MS = 400L
    private var lowPassMag = 0.0
    private var lowPassInit = false
    private var ampEstimate = 1.2
    private var rising = false
    private var lastPeakTimeMs = 0L
    private var internalStepCount = 0L
    private var algoEventCount = 0L
    private var usingVerticalProjection = false

    private val MIN_STEP_INTERVAL_MS = 300L
    private val MAX_STEP_INTERVAL_MS = 850L
    private val REQUIRED_CONSECUTIVE_FOR_WALKING = 3
    private val MAX_INTERVAL_RATIO_CHANGE = 1.6
    private val MAX_AMPLITUDE_COEFF_OF_VARIATION = 0.65
    private var pendingPeaks = mutableListOf<Pair<Long, Double>>()
    private var lastIntervalMs: Long = -1L
    private var isWalkingConfirmed = false

    // ============================================================================
    // FYTRA STEP ENGINE v2 — SHADOW MODE STATE
    // Τίποτα εδώ κάτω ΔΕΝ αγγίζει το internalStepCount / notifyListeners του v1.
    // ============================================================================
    private var v2InternalStepCount = 0L
    private var v2PendingPeaks = mutableListOf<Pair<Long, Double>>()
    private var v2LastIntervalMs: Long = -1L
    private var v2IsWalkingConfirmed = false
    private var v2Rising = false
    private var v2LastPeakTimeMs = 0L
    private var v2RejectLogCounter = 0L

    private var lastEventTimestampNs: Long = 0L
    private var estimatedSampleIntervalMs = 20.0
    private var v2RingBufferSize = 100

    private var v2SignalBuffer = DoubleArray(V2_MAX_BUFFER_SAMPLES)
    private var v2SignalBufferCount = 0
    private var v2SignalBufferHead = 0
    private var v2SamplesSinceAutocorr = 0
    private var v2LastPeriodicityScore = 0.0
    private var v2LastDominantPeriodMs = 0L

    override fun load() {
        Log.d(TAG, "load() ΞΕΚΙΝΗΣΕ")
        try {
            sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            rawAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            osGravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            hasGyroscope = gyroSensor != null
            Log.d(TAG, "load() ΤΕΛΕΙΩΣΕ. stepCounterSensor=" + stepCounterSensor + " stepDetectorSensor=" + stepDetectorSensor +
                " linearAccelSensor=" + linearAccelSensor + " rawAccelSensor=" + rawAccelSensor +
                " [v2] osGravitySensor=" + osGravitySensor + " gyroSensor=" + gyroSensor)
        } catch (e: Exception) {
            loadError = e.javaClass.simpleName + ": " + e.message
            Log.d(TAG, "load() ΕΣΚΑΣΕ: " + loadError)
        }
    }

    @PluginMethod
    fun requestStepPermissions(call: PluginCall) {
        Log.d(TAG, "requestStepPermissions() ΚΛΗΘΗΚΕ")
        try {
            if (loadError != null) { Log.d(TAG, "requestStepPermissions: loadError υπάρχει, reject"); call.reject("load() failed: " + loadError); return }
            val already = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "requestStepPermissions: already granted = " + already)
            if (already) {
                val ret = JSObject(); ret.put("granted", true); call.resolve(ret)
                Log.d(TAG, "requestStepPermissions: resolve(granted=true) ΕΓΙΝΕ")
            } else {
                Log.d(TAG, "requestStepPermissions: καλώ requestPermissionForAlias")
                requestPermissionForAlias("activity", call, "permissionCallback")
            }
        } catch (e: Exception) {
            Log.d(TAG, "requestStepPermissions ΕΣΚΑΣΕ: " + e.message)
            call.reject("requestStepPermissions crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        Log.d(TAG, "permissionCallback() ΚΛΗΘΗΚΕ")
        try {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            val ret = JSObject(); ret.put("granted", granted); call.resolve(ret)
            Log.d(TAG, "permissionCallback: resolve(granted=" + granted + ") ΕΓΙΝΕ")
        } catch (e: Exception) {
            Log.d(TAG, "permissionCallback ΕΣΚΑΣΕ: " + e.message)
            call.reject("permissionCallback crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        Log.d(TAG, "start() ΚΛΗΘΗΚΕ")
        try {
            if (loadError != null) { Log.d(TAG, "start: loadError υπάρχει, reject"); call.reject("load() failed: " + loadError); return }
            if (linearAccelSensor == null && rawAccelSensor == null) {
                Log.d(TAG, "start: ΔΕΝ υπάρχει κανένας επιταχυνσιόμετρος στη συσκευή, reject")
                call.reject("No accelerometer available on this device")
                return
            }

            resetAlgorithmState()

            if (sensorThread == null) {
                sensorThread = HandlerThread("FytraStepSensorThread").also { it.start() }
                sensorHandler = Handler(sensorThread!!.looper)
            }

            usingLinearAccel = rawAccelSensor == null && linearAccelSensor != null
            usingVerticalProjection = rawAccelSensor != null
            val chosenAccelSensor = if (rawAccelSensor != null) rawAccelSensor else linearAccelSensor
            Log.d(TAG, "start: επιλεγμένος αισθητήρας κίνησης = " + (if (usingVerticalProjection) "TYPE_ACCELEROMETER (κατακόρυφη προβολή)" else "TYPE_LINEAR_ACCELERATION (μέτρο, fallback)"))

            val accelRegistered = sensorManager.registerListener(this, chosenAccelSensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            Log.d(TAG, "start: registerListener (accelerometer αλγόριθμος) = " + accelRegistered)

            // ΝΕΟ (v2 μόνο, δεν επηρεάζει το v1): gravity/gyroscope σε ΔΙΚΟ ΤΟΥΣ, ξεχωριστό background thread
            // — ΔΙΟΡΘΩΘΗΚΕ: πριν μοιράζονταν το ΙΔΙΟ thread με τον επιταχυνσιόμετρο, κάτι που θα μπορούσε
            // θεωρητικά να εισάγει μικρές καθυστερήσεις χρονισμού στο ΠΑΡΑΓΩΓΙΚΟ v1 μονοπάτι. Τώρα το
            // thread του επιταχυνσιόμετρου είναι πλήρως απομονωμένο, ΑΚΡΙΒΩΣ όπως πριν την προσθήκη του v2.
            if (v2AuxSensorThread == null) {
                v2AuxSensorThread = HandlerThread("FytraV2AuxSensorThread").also { it.start() }
                v2AuxSensorHandler = Handler(v2AuxSensorThread!!.looper)
            }
            osGravityAvailable = false
            osGravitySensor?.let {
                val ok = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, v2AuxSensorHandler)
                Log.d(TAG, "start: registerListener (v2 gravity sensor, ξεχωριστό thread) = " + ok)
            }
            if (hasGyroscope) {
                val ok = sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME, v2AuxSensorHandler)
                Log.d(TAG, "start: registerListener (v2 gyroscope, ξεχωριστό thread) = " + ok)
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var counterRegistered = false
            stepCounterSensor?.let {
                counterRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
                Log.d(TAG, "start: registerListener (Samsung step counter, μόνο διαγνωστικά) = " + counterRegistered)
            }
            var detectorRegistered = false
            stepDetectorSensor?.let {
                detectorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
                Log.d(TAG, "start: registerListener (Samsung step detector, μόνο διαγνωστικά) = " + detectorRegistered)
            }

            val ret = JSObject()
            ret.put("counterRegistered", accelRegistered)
            ret.put("detectorAvailable", true)
            ret.put("detectorRegistered", accelRegistered)
            ret.put("counterSensorName", "FYTRA native βηματόμετρο (" + (if (usingLinearAccel) "linear accelerometer" else "raw accelerometer") + ")")
            ret.put("counterSensorVendor", chosenAccelSensor?.vendor ?: "?")
            call.resolve(ret)
            Log.d(TAG, "start: call.resolve() ΕΓΙΝΕ. [v2 shadow ενεργό: hasGravitySensor=" + (osGravitySensor != null) + " hasGyroscope=" + hasGyroscope + "]")
        } catch (e: Exception) {
            Log.d(TAG, "start ΕΣΚΑΣΕ: " + e.message)
            call.reject("start crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun resetAlgorithmState() {
        gravity = FloatArray(3)
        gravityInit = false
        vSmooth = 0.0
        vSmoothInit = false
        trendSlow = 0.0
        trendInit = false
        lastSampleSign = 0
        lastRisingZeroCrossMs = System.currentTimeMillis()
        lowPassMag = 0.0
        lowPassInit = false
        ampEstimate = 1.2
        rising = false
        lastPeakTimeMs = 0L
        internalStepCount = 0L
        algoEventCount = 0L
        pendingPeaks = mutableListOf()
        lastIntervalMs = -1L
        isWalkingConfirmed = false

        v2InternalStepCount = 0L
        v2PendingPeaks = mutableListOf()
        v2LastIntervalMs = -1L
        v2IsWalkingConfirmed = false
        v2Rising = false
        v2LastPeakTimeMs = 0L
        v2RejectLogCounter = 0L
        osGravity = FloatArray(3)
        osGravityAvailable = false
        gyroBaselineRms = 0.02
        latestGyroRms = 0.0
        lastEventTimestampNs = 0L
        estimatedSampleIntervalMs = 20.0
        v2RingBufferSize = 100
        v2SignalBuffer = DoubleArray(V2_MAX_BUFFER_SAMPLES)
        v2SignalBufferCount = 0
        v2SignalBufferHead = 0
        v2SamplesSinceAutocorr = 0
        v2LastPeriodicityScore = 0.0
        v2LastDominantPeriodMs = 0L
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        try {
            sensorManager.unregisterListener(this)
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
            v2AuxSensorThread?.quitSafely()
            v2AuxSensorThread = null
            v2AuxSensorHandler = null
            call.resolve()
        } catch (e: Exception) {
            call.reject("stop crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PluginMethod
    fun getDebugLogs(call: PluginCall) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "6000"))
            val allLines = process.inputStream.bufferedReader().readText()
            val tagPattern = Regex("""\b(FytraDebug|AndroidRuntime|System\.err)\b""")
            val capacitorTagPattern = Regex("""\bCapacitor\s*:""")
            val relevant = allLines.lines().filter { line ->
                tagPattern.containsMatchIn(line) || capacitorTagPattern.containsMatchIn(line)
            }.joinToString("\n")
            val ret = JSObject()
            ret.put("logs", if (relevant.isNotBlank()) relevant else "(δεν βρέθηκαν σχετικές γραμμές μέσα στα τελευταία logs)\n\n--- ΟΛΑ τα logs (τελευταίες γραμμές) ---\n" + allLines.takeLast(3000))
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("getDebugLogs crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ΝΕΟ (v2): on-demand σύγκριση legacy/v2, χωρίς τον θόρυβο όλων των υπόλοιπων logs.
    @PluginMethod
    fun getShadowComparison(call: PluginCall) {
        val ret = JSObject()
        ret.put("legacySteps", internalStepCount.toDouble())
        ret.put("v2Steps", v2InternalStepCount.toDouble())
        ret.put("estimatedHz", if (estimatedSampleIntervalMs > 0) 1000.0 / estimatedSampleIntervalMs else 0.0)
        ret.put("periodicityScore", v2LastPeriodicityScore)
        ret.put("dominantPeriodMs", v2LastDominantPeriodMs.toDouble())
        ret.put("gyroRms", latestGyroRms)
        ret.put("hasGyroscope", hasGyroscope)
        ret.put("hasGravitySensor", osGravitySensor != null)
        call.resolve(ret)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                Log.d(TAG, "onSensorChanged (Samsung step counter, ΜΟΝΟ diag) value=" + (if (event.values.isNotEmpty()) event.values[0] else "?"))
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                Log.d(TAG, "onSensorChanged (Samsung step detector, ΜΟΝΟ diag) ΚΛΗΘΗΚΕ")
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                updateEstimatedSampleRate(event.timestamp)
                processAccelSample(event)
            }
            Sensor.TYPE_GRAVITY -> {
                osGravity[0] = event.values[0]; osGravity[1] = event.values[1]; osGravity[2] = event.values[2]
                osGravityAvailable = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                processGyroSample(event)
            }
        }
    }

    // ΝΕΟ (v2): εκτιμά τον πραγματικό ρυθμό δειγματοληψίας από τα ίδια τα timestamps του αισθητήρα.
    private fun updateEstimatedSampleRate(timestampNs: Long) {
        if (lastEventTimestampNs != 0L) {
            val dtMs = (timestampNs - lastEventTimestampNs) / 1_000_000.0
            if (dtMs in 1.0..200.0) {
                estimatedSampleIntervalMs = estimatedSampleIntervalMs * 0.9 + dtMs * 0.1
                val desired = (1000.0 / estimatedSampleIntervalMs * V2_TARGET_WINDOW_SEC).toInt()
                v2RingBufferSize = desired.coerceIn(V2_MIN_BUFFER_SAMPLES, V2_MAX_BUFFER_SAMPLES)
            }
        }
        lastEventTimestampNs = timestampNs
    }

    private fun processGyroSample(event: SensorEvent) {
        val wx = event.values[0]; val wy = event.values[1]; val wz = event.values[2]
        val omega = sqrt((wx * wx + wy * wy + wz * wz).toDouble())
        latestGyroRms = latestGyroRms * 0.8 + omega * 0.2
        if (latestGyroRms < gyroBaselineRms) {
            gyroBaselineRms = gyroBaselineRms * 0.95 + latestGyroRms * 0.05
        } else {
            gyroBaselineRms = gyroBaselineRms * V2_GYRO_BASELINE_ALPHA + latestGyroRms * (1 - V2_GYRO_BASELINE_ALPHA)
        }
    }

    // ⚠️ ΠΑΡΑΓΩΓΙΚΟ ΜΟΝΟΠΑΤΙ (v1/legacy) — ΑΝΕΠΑΦΟ εκτός από μία κλήση στο processV2Shadow() στο τέλος.
    private fun processAccelSample(event: SensorEvent) {
        algoEventCount++
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]

        val hp: Double
        if (usingVerticalProjection) {
            if (!gravityInit) { gravity[0] = x; gravity[1] = y; gravity[2] = z; gravityInit = true }
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
            val gMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble())
            val lx = x - gravity[0]; val ly = y - gravity[1]; val lz = z - gravity[2]
            val vertical = if (gMag > 0.1) (lx * gravity[0] + ly * gravity[1] + lz * gravity[2]) / gMag else 0.0
            if (!vSmoothInit) { vSmooth = vertical; vSmoothInit = true }
            vSmooth = vSmooth * 0.7 + vertical * 0.3
            if (!trendInit) { trendSlow = vSmooth; trendInit = true }
            trendSlow = trendSlow * 0.97 + vSmooth * 0.03
            hp = vSmooth - trendSlow
        } else {
            val mag = sqrt((x * x + y * y + z * z).toDouble())
            if (!lowPassInit) { lowPassMag = mag; lowPassInit = true }
            lowPassMag = lowPassMag * 0.9 + mag * 0.1
            hp = mag - lowPassMag
        }

        val now = System.currentTimeMillis()

        val deadzone = 0.05
        val currentSign = when { hp > deadzone -> 1; hp < -deadzone -> -1; else -> lastSampleSign }
        if (lastSampleSign == -1 && currentSign == 1) { lastRisingZeroCrossMs = now }
        lastSampleSign = currentSign

        ampEstimate = max(ampEstimate * 0.996, abs(hp))
        val threshold = max(0.32, ampEstimate * 0.35)

        if (hp > threshold && !rising && (now - lastPeakTimeMs) > MIN_STEP_INTERVAL_MS) {
            rising = true
            val interval = if (lastPeakTimeMs == 0L) -1L else (now - lastPeakTimeMs)
            lastPeakTimeMs = now
            val hasProperWaveShape = (now - lastRisingZeroCrossMs) in 0..ZERO_CROSS_TO_PEAK_MAX_MS
            if (hasProperWaveShape) {
                handleCandidatePeak(now, interval, hp)
            } else {
                Log.d(TAG, "processAccelSample: peak χωρίς καθαρό ανερχόμενο zero-crossing πριν από αυτό -> αγνοείται ως πιθανό artifact")
            }
        } else if (hp < -threshold * 0.3) {
            rising = false
        }

        // ΝΕΟ: v2 shadow — τρέχει ΠΑΝΤΑ παράλληλα, ανεξάρτητα από το αποτέλεσμα του v1 παραπάνω.
        processV2Shadow(now, hp)
    }

    // ⚠️ ΠΑΡΑΓΩΓΙΚΟ ΜΟΝΟΠΑΤΙ (v1/legacy) — ΑΝΕΠΑΦΟ.
    private fun handleCandidatePeak(now: Long, intervalFromPrevious: Long, amplitude: Double) {
        val intervalInRange = intervalFromPrevious in MIN_STEP_INTERVAL_MS..MAX_STEP_INTERVAL_MS
        val ratioOk = if (lastIntervalMs <= 0L || intervalFromPrevious <= 0L) true else {
            val ratio = intervalFromPrevious.toDouble() / lastIntervalMs.toDouble()
            val biggerOverSmaller = if (ratio >= 1.0) ratio else 1.0 / ratio
            biggerOverSmaller <= MAX_INTERVAL_RATIO_CHANGE
        }
        val passesTimingChecks = intervalFromPrevious != -1L && intervalInRange && ratioOk

        if (isWalkingConfirmed) {
            if (passesTimingChecks) {
                lastIntervalMs = intervalFromPrevious
                creditOneStep(now)
            } else {
                isWalkingConfirmed = false
                lastIntervalMs = -1L
                pendingPeaks = mutableListOf(now to amplitude)
            }
            return
        }

        if (!passesTimingChecks) {
            pendingPeaks = mutableListOf(now to amplitude)
            lastIntervalMs = -1L
            return
        }

        lastIntervalMs = intervalFromPrevious
        pendingPeaks.add(now to amplitude)
        if (pendingPeaks.size >= REQUIRED_CONSECUTIVE_FOR_WALKING) {
            val amplitudes = pendingPeaks.map { it.second }
            val meanAmp = amplitudes.average()
            val variance = amplitudes.sumOf { (it - meanAmp) * (it - meanAmp) } / amplitudes.size
            val coeffOfVariation = if (meanAmp > 0.0001) sqrt(variance) / meanAmp else 0.0

            if (coeffOfVariation <= MAX_AMPLITUDE_COEFF_OF_VARIATION) {
                isWalkingConfirmed = true
                val toCredit = pendingPeaks.toList()
                pendingPeaks = mutableListOf()
                toCredit.forEach { creditOneStep(it.first) }
            } else {
                pendingPeaks = mutableListOf(pendingPeaks.last())
            }
        }
    }

    private fun creditOneStep(atTimeMs: Long) {
        internalStepCount++
        val data = JSObject()
        data.put("totalSteps", internalStepCount.toDouble())
        notifyListeners("stepCounterUpdate", data)
        notifyListeners("stepDetectorEvent", JSObject())
        if (internalStepCount % 10 == 0L) {
            Log.d(TAG, "creditOneStep: βήμα #" + internalStepCount + " (events επεξεργασμένα=" + algoEventCount + ")")
        }
    }

    // ============================================================================
    // FYTRA STEP ENGINE v2 — SHADOW MODE. Τίποτα εδώ δεν καλεί notifyListeners ούτε αγγίζει internalStepCount.
    // ============================================================================

    private fun v2PushSample(value: Double) {
        v2SignalBuffer[v2SignalBufferHead] = value
        v2SignalBufferHead = (v2SignalBufferHead + 1) % v2SignalBuffer.size
        if (v2SignalBufferCount < v2SignalBuffer.size) v2SignalBufferCount++
    }

    private fun v2GetRecentWindow(): DoubleArray {
        val n = min(v2RingBufferSize, v2SignalBufferCount)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val idx = (v2SignalBufferHead - n + i + v2SignalBuffer.size) % v2SignalBuffer.size
            out[i] = v2SignalBuffer[idx]
        }
        return out
    }

    // Robust στατιστικές (median + MAD) — πιο ανθεκτικό σε ένα μεμονωμένο ακραίο τίναγμα από ένα EMA.
    private fun v2RobustThreshold(window: DoubleArray): Double {
        if (window.isEmpty()) return V2_MIN_THRESHOLD
        val absVals = DoubleArray(window.size) { abs(window[it]) }
        absVals.sort()
        val median = absVals[absVals.size / 2]
        val deviations = DoubleArray(absVals.size) { abs(absVals[it] - median) }
        deviations.sort()
        val mad = deviations[deviations.size / 2]
        val t = median + V2_MEDIAN_MAD_K * mad
        return t.coerceIn(V2_MIN_THRESHOLD, V2_MAX_THRESHOLD)
    }

    // Autocorrelation: "υπάρχει σταθερή περιοδικότητα σαν βάδισμα εδώ μέσα;" — επιστρέφει (score[0..1], periodMs)
    private fun v2ComputePeriodicity(window: DoubleArray): Pair<Double, Long> {
        if (window.size < V2_MIN_BUFFER_SAMPLES || estimatedSampleIntervalMs <= 0) return Pair(0.0, 0L)
        val mean = window.average()
        val centered = DoubleArray(window.size) { window[it] - mean }
        val energy0 = centered.sumOf { it * it }
        if (energy0 < 1e-6) return Pair(0.0, 0L)

        val minLag = max(1, (V2_AUTOCORR_MIN_LAG_SEC * 1000.0 / estimatedSampleIntervalMs).toInt())
        val maxLag = min(centered.size - 1, (V2_AUTOCORR_MAX_LAG_SEC * 1000.0 / estimatedSampleIntervalMs).toInt())
        if (maxLag <= minLag) return Pair(0.0, 0L)

        var bestLag = -1
        var bestCorr = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until centered.size - lag) sum += centered[i] * centered[i + lag]
            val normalized = sum / energy0
            if (normalized > bestCorr) { bestCorr = normalized; bestLag = lag }
        }
        if (bestLag < 0) return Pair(0.0, 0L)
        val periodMs = (bestLag * estimatedSampleIntervalMs).toLong()
        return Pair(bestCorr.coerceIn(0.0, 1.0), periodMs)
    }

    private fun processV2Shadow(now: Long, hp: Double) {
        v2PushSample(hp)
        v2SamplesSinceAutocorr++
        val window = v2GetRecentWindow()

        if (v2SamplesSinceAutocorr >= V2_AUTOCORR_RECOMPUTE_EVERY_N) {
            v2SamplesSinceAutocorr = 0
            val (periodicity, periodMs) = v2ComputePeriodicity(window)
            v2LastPeriodicityScore = periodicity
            v2LastDominantPeriodMs = periodMs
        }

        val threshold = v2RobustThreshold(window)

        val gyroPenalty = if (hasGyroscope && gyroBaselineRms > 0.0001) {
            val ratio = latestGyroRms / gyroBaselineRms
            ((ratio - V2_GYRO_HIGH_RATIO) / V2_GYRO_HIGH_RATIO).coerceIn(0.0, 1.0)
        } else 0.0

        if (hp > threshold && !v2Rising && (now - v2LastPeakTimeMs) > V2_MIN_STEP_INTERVAL_MS) {
            v2Rising = true
            val interval = if (v2LastPeakTimeMs == 0L) -1L else (now - v2LastPeakTimeMs)
            v2LastPeakTimeMs = now
            v2HandleCandidatePeak(now, interval, hp, gyroPenalty)
        } else if (hp < -threshold * 0.3) {
            v2Rising = false
        }
    }

    private fun v2HandleCandidatePeak(now: Long, intervalFromPrevious: Long, amplitude: Double, gyroPenalty: Double) {
        val intervalInRange = intervalFromPrevious in V2_MIN_STEP_INTERVAL_MS..V2_MAX_STEP_INTERVAL_MS
        val ratioOk = if (v2LastIntervalMs <= 0L || intervalFromPrevious <= 0L) true else {
            val ratio = intervalFromPrevious.toDouble() / v2LastIntervalMs.toDouble()
            val biggerOverSmaller = if (ratio >= 1.0) ratio else 1.0 / ratio
            biggerOverSmaller <= V2_MAX_INTERVAL_RATIO_CHANGE
        }
        val periodicityOk = v2LastPeriodicityScore >= V2_AUTOCORR_MIN_PERIODICITY
        val gyroOk = gyroPenalty < V2_GYRO_PENALTY_REJECT_ABOVE
        val passesTimingChecks = intervalFromPrevious != -1L && intervalInRange && ratioOk && periodicityOk && gyroOk

        // ΝΕΟ: διαγνωστικό — δείχνει ΑΚΡΙΒΩΣ ποιος έλεγχος απέρριψε το χτύπημα, όταν δεν είμαστε ήδη σε
        // επιβεβαιωμένο περπάτημα. Χωρίς αυτό, ένα αποτυχημένο shadow test (όπως το πρώτο) είναι "μαύρο κουτί".
        if (!v2IsWalkingConfirmed && !passesTimingChecks) {
            v2RejectLogCounter++
            if (v2RejectLogCounter % 8 == 0L) {
                Log.d(TAG, "V2_REJECT interval=" + intervalFromPrevious + "ms inRange=" + intervalInRange +
                    " ratioOk=" + ratioOk + " periodicity=" + String.format("%.2f", v2LastPeriodicityScore) + "(ok=" + periodicityOk + ")" +
                    " gyroPenalty=" + String.format("%.2f", gyroPenalty) + "(ok=" + gyroOk + ")")
            }
        }

        if (v2IsWalkingConfirmed) {
            if (passesTimingChecks) {
                v2LastIntervalMs = intervalFromPrevious
                v2CreditOneStep()
            } else {
                v2IsWalkingConfirmed = false
                v2LastIntervalMs = -1L
                v2PendingPeaks = mutableListOf(now to amplitude)
            }
            return
        }

        if (!passesTimingChecks) {
            v2PendingPeaks = mutableListOf(now to amplitude)
            v2LastIntervalMs = -1L
            return
        }

        v2LastIntervalMs = intervalFromPrevious
        v2PendingPeaks.add(now to amplitude)
        if (v2PendingPeaks.size >= V2_REQUIRED_CONSECUTIVE) {
            val amplitudes = v2PendingPeaks.map { it.second }
            val meanAmp = amplitudes.average()
            val variance = amplitudes.sumOf { (it - meanAmp) * (it - meanAmp) } / amplitudes.size
            val coeffOfVariation = if (meanAmp > 0.0001) sqrt(variance) / meanAmp else 0.0

            if (coeffOfVariation <= MAX_AMPLITUDE_COEFF_OF_VARIATION) {
                v2IsWalkingConfirmed = true
                val toCredit = v2PendingPeaks.size
                v2PendingPeaks = mutableListOf()
                repeat(toCredit) { v2CreditOneStep() }
            } else {
                v2PendingPeaks = mutableListOf(v2PendingPeaks.last())
            }
        }
    }

    private fun v2CreditOneStep() {
        v2InternalStepCount++
        if (v2InternalStepCount % V2_LOG_EVERY_N_LEGACY_STEPS == 0L || internalStepCount % V2_LOG_EVERY_N_LEGACY_STEPS == 0L) {
            Log.d(TAG, "V2_SHADOW_COMPARE legacySteps=" + internalStepCount + " v2Steps=" + v2InternalStepCount +
                " diff=" + (v2InternalStepCount - internalStepCount) +
                " estimatedHz=" + String.format("%.1f", if (estimatedSampleIntervalMs > 0) 1000.0 / estimatedSampleIntervalMs else 0.0) +
                " periodicity=" + String.format("%.2f", v2LastPeriodicityScore) +
                " dominantPeriodMs=" + v2LastDominantPeriodMs +
                " gyroRms=" + String.format("%.3f", latestGyroRms) + " gyroBaseline=" + String.format("%.3f", gyroBaselineRms))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
