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

    override fun load() {
        sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    }

    @PluginMethod
    fun requestStepPermissions(call: PluginCall) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            == PackageManager.PERMISSION_GRANTED) {
            val ret = JSObject(); ret.put("granted", true); call.resolve(ret)
        } else {
            requestPermissionForAlias("activity", call, "permissionCallback")
        }
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        val ret = JSObject(); ret.put("granted", granted); call.resolve(ret)
    }

    // ΝΕΟ, ΔΙΑΓΝΩΣΤΙΚΟ: επιστρέφει ρητά αν η καταχώρηση κάθε αισθητήρα πέτυχε πραγματικά (Boolean από το
    // ίδιο το Android), αντί να υποθέτουμε ότι πέτυχε απλά επειδή δεν πέταξε σφάλμα. Επίσης επιστρέφει το
    // όνομα/vendor του αισθητήρα καταμέτρησης βημάτων, για να δούμε τι chip/υλοποίηση αναφέρει η συσκευή.
    @PluginMethod
    fun start(call: PluginCall) {
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
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        sensorManager.unregisterListener(this)
        call.resolve()
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
