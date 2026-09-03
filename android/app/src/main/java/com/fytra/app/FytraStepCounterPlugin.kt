package com.fytra.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var loadError: String? = null // ΝΕΟ: αν σκάσει κάτι στο load(), το κρατάμε για να το δούμε αργότερα

    override fun load() {
        try {
            sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        } catch (e: Exception) {
            loadError = e.javaClass.simpleName + ": " + e.message
        }
    }

    @PluginMethod
    fun requestStepPermissions(call: PluginCall) {
        try {
            if (loadError != null) { call.reject("load() failed: " + loadError); return }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED) {
                val ret = JSObject(); ret.put("granted", true); call.resolve(ret)
            } else {
                requestPermissionForAlias("activity", call, "permissionCallback")
            }
        } catch (e: Exception) {
            call.reject("requestStepPermissions crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        try {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            val ret = JSObject(); ret.put("granted", granted); call.resolve(ret)
        } catch (e: Exception) {
            call.reject("permissionCallback crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ΝΕΟ: ΟΛΟΚΛΗΡΗ η συνάρτηση τυλιγμένη σε try/catch — αν σκάσει ΟΤΙΔΗΠΟΤΕ (π.χ. lateinit sensorManager
    // ποτέ αρχικοποιημένο, SecurityException λόγω άδειας, οτιδήποτε), ΤΩΡΑ θα επιστρέψει ρητό μήνυμα λάθους
    // στο JS αντί να χάνεται σιωπηλά (γι' αυτό δεν βλέπαμε ΠΟΤΕ καμία αλλαγή στην οθόνη μέχρι τώρα).
    @PluginMethod
    fun start(call: PluginCall) {
        try {
            if (loadError != null) { call.reject("load() failed: " + loadError); return }
            if (stepCounterSensor == null) {
                call.reject("TYPE_STEP_COUNTER not available on this device")
                return
            }
            val counterRegistered = sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_FASTEST)
            var detectorRegistered = false
            stepDetectorSensor?.let {
                detectorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
            val ret = JSObject()
            ret.put("counterRegistered", counterRegistered)
            ret.put("detectorAvailable", stepDetectorSensor != null)
            ret.put("detectorRegistered", detectorRegistered)
            ret.put("counterSensorName", stepCounterSensor?.name ?: "null")
            ret.put("counterSensorVendor", stepCounterSensor?.vendor ?: "null")
            call.resolve(ret)
        } catch (e: Exception) {
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

    override fun onSensorChanged(event: SensorEvent) {
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
