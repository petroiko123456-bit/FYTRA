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
import kotlin.math.sqrt

@CapacitorPlugin(
    name = "FytraStepCounter",
    permissions = [Permission(strings = [Manifest.permission.ACTIVITY_RECOGNITION], alias = "activity")]
)
class FytraStepCounterPlugin : Plugin(), SensorEventListener {

    companion object { const val TAG = "FytraDebug" }

    private lateinit var sensorManager: SensorManager

    // Οι "επίσημοι" αισθητήρες βημάτων της Samsung — τους κρατάμε ΜΟΝΟ για διαγνωστικά logs πλέον.
    // Αποδείχτηκε (βλ. εκτεταμένα logs συνεδρίας debugging) ότι στη συσκευή του χρήστη το τσιπάκι τους
    // δίνει ΜΙΑ στατική τιμή στην εγγραφή και μετά σιωπά μόνιμα, ανεξάρτητα από threading/Handler/ρυθμίσεις
    // μπαταρίας — γνωστός περιορισμός πολλών Samsung συσκευών για εφαρμογές τρίτων. ΔΕΝ τα χρησιμοποιούμε
    // πια για να οδηγήσουμε το πραγματικό μέτρημα.
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var loadError: String? = null

    // ΝΕΟ: δικός μας, native αλγόριθμος μέτρησης βημάτων πάνω στον απλό επιταχυνσιόμετρο (accelerometer),
    // που ΔΕΝ μπλοκάρεται από τη Samsung (τον χρησιμοποιούν συνέχεια παιχνίδια/εφαρμογές). Προτιμάμε
    // TYPE_LINEAR_ACCELERATION όταν υπάρχει (η ίδια η Android έχει ήδη αφαιρέσει τη βαρύτητα μέσω sensor
    // fusion, πιο καθαρό σήμα)· αλλιώς πέφτουμε σε TYPE_ACCELEROMETER και αφαιρούμε εμείς τη βαρύτητα
    // με χαμηλοπερατό φίλτρο (ίδια λογική μ' αυτή που ήδη έχουμε δοκιμασμένη στο JS/DeviceMotion fallback).
    private var linearAccelSensor: Sensor? = null
    private var rawAccelSensor: Sensor? = null
    private var usingLinearAccel = false

    // Ξεχωριστό background thread ΜΕ δικό του σταθερό Looper, ώστε η υψίσυχνη ροή του επιταχυνσιόμετρου να
    // ΜΗΝ επιβαρύνει το κύριο UI thread, και να μη χαθεί ΚΑΝΕΝΑ event ό,τι κι αν κάνει το Capacitor bridge.
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // --- Κατάσταση του αλγορίθμου ανίχνευσης βήματος (peak detection με προσαρμοστικό όριο) ---
    private var gravity = FloatArray(3)          // χαμηλοπερατό φίλτρο βαρύτητας (μόνο για raw accelerometer)
    private var gravityInit = false
    private var lowPassMag = 0.0                 // "αργή" κυλιόμενη βάση του μέτρου επιτάχυνσης
    private var lowPassInit = false
    private var ampEstimate = 1.2                // προσαρμοστικό εύρος ταλάντωσης (φθίνει αργά, ίδιο με JS)
    private var rising = false
    private var lastPeakTimeMs = 0L
    private var internalStepCount = 0L           // το δικό μας, εσωτερικό, συνεχές μέτρημα βημάτων της σεσίας
    private var algoEventCount = 0L              // πόσα raw sensor events έχουμε επεξεργαστεί (διαγνωστικό)

    override fun load() {
        Log.d(TAG, "load() ΞΕΚΙΝΗΣΕ")
        try {
            sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            rawAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Log.d(TAG, "load() ΤΕΛΕΙΩΣΕ. stepCounterSensor=" + stepCounterSensor + " stepDetectorSensor=" + stepDetectorSensor +
                " linearAccelSensor=" + linearAccelSensor + " rawAccelSensor=" + rawAccelSensor)
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

            // Καθαρό ξεκίνημα της σεσίας μέτρησης κάθε φορά που καλείται start().
            resetAlgorithmState()

            // Ξεκινάμε το δικό μας background thread με σταθερό Looper, αν δεν τρέχει ήδη.
            if (sensorThread == null) {
                sensorThread = HandlerThread("FytraStepSensorThread").also { it.start() }
                sensorHandler = Handler(sensorThread!!.looper)
            }

            usingLinearAccel = linearAccelSensor != null
            val chosenAccelSensor = if (usingLinearAccel) linearAccelSensor else rawAccelSensor
            Log.d(TAG, "start: επιλεγμένος αισθητήρας κίνησης = " + (if (usingLinearAccel) "TYPE_LINEAR_ACCELERATION" else "TYPE_ACCELEROMETER"))

            val accelRegistered = sensorManager.registerListener(this, chosenAccelSensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            Log.d(TAG, "start: registerListener (accelerometer αλγόριθμος) = " + accelRegistered)

            // Κρατάμε ΚΑΙ τους επίσημους αισθητήρες βημάτων εγγεγραμμένους — μόνο για διαγνωστικά logs,
            // σε περίπτωση που σε κάποια άλλη συσκευή δουλέψουν κανονικά. ΔΕΝ επηρεάζουν το μέτρημα πλέον.
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
            ret.put("counterRegistered", accelRegistered) // ΣΗΜΑΝΤΙΚΟ: το JS διαβάζει αυτό ως "λειτουργεί ο αισθητήρας"
            ret.put("detectorAvailable", true)
            ret.put("detectorRegistered", accelRegistered)
            ret.put("counterSensorName", "FYTRA native βηματόμετρο (" + (if (usingLinearAccel) "linear accelerometer" else "raw accelerometer") + ")")
            ret.put("counterSensorVendor", chosenAccelSensor?.vendor ?: "?")
            call.resolve(ret)
            Log.d(TAG, "start: call.resolve() ΕΓΙΝΕ")
        } catch (e: Exception) {
            Log.d(TAG, "start ΕΣΚΑΣΕ: " + e.message)
            call.reject("start crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun resetAlgorithmState() {
        gravity = FloatArray(3)
        gravityInit = false
        lowPassMag = 0.0
        lowPassInit = false
        ampEstimate = 1.2
        rising = false
        lastPeakTimeMs = 0L
        internalStepCount = 0L
        algoEventCount = 0L
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        try {
            sensorManager.unregisterListener(this)
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
            call.resolve()
        } catch (e: Exception) {
            call.reject("stop crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ΝΕΟ: διαβάζει τα πρόσφατα logcat logs ΤΗΣ ΙΔΙΑΣ ΕΦΑΡΜΟΓΗΣ (χωρίς root/υπολογιστή) και τα επιστρέφει
    // σαν κείμενο στο JS, ώστε να φαίνονται απευθείας στην οθόνη του κινητού.
    @PluginMethod
    fun getDebugLogs(call: PluginCall) {
        try {
            // ΔΙΟΡΘΩΘΗΚΕ: -t 400 ήταν πολύ μικρό — η Android παράγει εκατοντάδες δικές της γραμμές το
            // δευτερόλεπτο (π.χ. "setRequestedFrameRate" από το animation περπατήματος), οπότε τα 400 πιο
            // πρόσφατα συστημικά logs ήταν σχεδόν όλα άσχετα, σπρώχνοντας έξω τα δικά μας FytraDebug logs.
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "6000"))
            val allLines = process.inputStream.bufferedReader().readText()
            // ΔΙΟΡΘΩΘΗΚΕ: το παλιό it.contains("Capacitor") "έπιανε" λανθασμένα και άσχετες γραμμές που απλά
            // περιέχουν τη λέξη "Capacitor" μέσα σε άλλο string (π.χ. "CapacitorWebView" στα logs του View),
            // ΟΧΙ μόνο τις πραγματικές γραμμές με tag "Capacitor". Τώρα απαιτούμε ακριβές όριο tag (boundary),
            // ώστε να πιάνει ΜΟΝΟ τις πραγματικές μας ετικέτες, όχι τυχαία ενσωματωμένα ονόματα.
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

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                // Μόνο διαγνωστικό log πλέον — ΔΕΝ οδηγεί το πραγματικό μέτρημα (βλ. σχόλιο στο load()).
                Log.d(TAG, "onSensorChanged (Samsung step counter, ΜΟΝΟ diag) value=" + (if (event.values.isNotEmpty()) event.values[0] else "?"))
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                Log.d(TAG, "onSensorChanged (Samsung step detector, ΜΟΝΟ diag) ΚΛΗΘΗΚΕ")
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                processAccelSample(event)
            }
        }
    }

    // Ο δικός μας αλγόριθμος ανίχνευσης βήματος. Ίδια βασική λογική με το δοκιμασμένο JS/DeviceMotion
    // fallback (χαμηλοπερατό φίλτρο, προσαρμοστικό όριο πλάτους, υστέρηση/hysteresis, ελάχιστος χρόνος
    // ανάμεσα σε "χτυπήματα" ώστε να μην μετράει διπλά ένα βήμα) — αλλά πλέον τρέχει native, σε δικό του
    // background thread, με πολύ μεγαλύτερη σταθερότητα δειγματοληψίας απ' ό,τι μέσα σε ένα WebView.
    private fun processAccelSample(event: SensorEvent) {
        algoEventCount++
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]

        val mag: Double
        if (usingLinearAccel) {
            // Η ίδια η Android έχει ήδη αφαιρέσει τη βαρύτητα (sensor fusion) — καθαρό σήμα κίνησης.
            mag = sqrt((x * x + y * y + z * z).toDouble())
        } else {
            // Raw accelerometer: αφαιρούμε εμείς τη βαρύτητα με ένα αργό χαμηλοπερατό φίλτρο ανά άξονα.
            if (!gravityInit) { gravity[0] = x; gravity[1] = y; gravity[2] = z; gravityInit = true }
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
            val lx = x - gravity[0]; val ly = y - gravity[1]; val lz = z - gravity[2]
            mag = sqrt((lx * lx + ly * ly + lz * lz).toDouble())
        }

        if (!lowPassInit) { lowPassMag = mag; lowPassInit = true }
        lowPassMag = lowPassMag * 0.9 + mag * 0.1 // αργή, "κυλιόμενη" βάση
        val hp = mag - lowPassMag                 // γρήγορη μεταβολή γύρω από τη βάση
        ampEstimate = max(ampEstimate * 0.996, abs(hp)) // προσαρμοστικό εύρος ταλάντωσης, φθίνει αργά
        val threshold = max(0.28, ampEstimate * 0.32)
        val now = System.currentTimeMillis()

        if (hp > threshold && !rising && (now - lastPeakTimeMs) > 260) {
            rising = true
            lastPeakTimeMs = now
            internalStepCount++
            val data = JSObject()
            data.put("totalSteps", internalStepCount.toDouble())
            notifyListeners("stepCounterUpdate", data)
            notifyListeners("stepDetectorEvent", JSObject())
            if (internalStepCount % 10 == 0L) {
                Log.d(TAG, "processAccelSample: βήμα #" + internalStepCount + " (events επεξεργασμένα=" + algoEventCount + ")")
            }
        } else if (hp < -threshold * 0.3) {
            rising = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
