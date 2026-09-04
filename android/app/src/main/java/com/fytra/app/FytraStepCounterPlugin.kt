package com.fytra.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "FytraStepCounter",
    permissions = [Permission(strings = [Manifest.permission.ACTIVITY_RECOGNITION], alias = "activity")]
)
class FytraStepCounterPlugin : Plugin(), SensorEventListener {

    companion object { const val TAG = "FytraDebug" }

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var loadError: String? = null

    override fun load() {
        Log.d(TAG, "load() ΞΕΚΙΝΗΣΕ")
        try {
            sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            Log.d(TAG, "load() ΤΕΛΕΙΩΣΕ. stepCounterSensor=" + stepCounterSensor + " stepDetectorSensor=" + stepDetectorSensor)
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
            if (stepCounterSensor == null) {
                Log.d(TAG, "start: stepCounterSensor == null, reject")
                call.reject("TYPE_STEP_COUNTER not available on this device")
                return
            }
            Log.d(TAG, "start: πριν το registerListener (counter)")
            val counterRegistered = sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d(TAG, "start: μετά το registerListener (counter) = " + counterRegistered)
            var detectorRegistered = false
            stepDetectorSensor?.let {
                detectorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d(TAG, "start: registerListener (detector) = " + detectorRegistered)
            }
            val ret = JSObject()
            ret.put("counterRegistered", counterRegistered)
            ret.put("detectorAvailable", stepDetectorSensor != null)
            ret.put("detectorRegistered", detectorRegistered)
            ret.put("counterSensorName", stepCounterSensor?.name ?: "null")
            ret.put("counterSensorVendor", stepCounterSensor?.vendor ?: "null")
            call.resolve(ret)
            Log.d(TAG, "start: call.resolve() ΕΓΙΝΕ")
        } catch (e: Exception) {
            Log.d(TAG, "start ΕΣΚΑΣΕ: " + e.message)
            call.reject("start crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        try {
            sensorManager.unregisterListener(this)
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
        Log.d(TAG, "onSensorChanged ΚΛΗΘΗΚΕ, sensor.type=" + event.sensor.type + " value=" + (if (event.values.isNotEmpty()) event.values[0] else "?"))
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val data = JSObject()
                data.put("totalSteps", event.values[0].toDouble())
                notifyListeners("stepCounterUpdate", data)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                notifyListeners("stepDetectorEvent", JSObject())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
