package com.eladcohen.buswatch.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits the watch's compass heading in degrees clockwise from north (0 = N,
 * 90 = E) from the fused rotation-vector sensor. Used to point the user marker
 * the way they're facing on the north-up map.
 */
class HeadingProvider(private val context: Context) {

    fun updates(): Flow<Float> = callbackFlow {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close(); return@callbackFlow
        }
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, e.values)
                SensorManager.getOrientation(rotation, orientation)
                val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                trySend(deg)
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }
}
