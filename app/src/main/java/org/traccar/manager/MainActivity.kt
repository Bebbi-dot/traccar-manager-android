/*
 * Copyright 2016 - 2023 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("DEPRECATION")
package org.traccar.manager

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    var pendingEventId: Long? = null

    // --- Kompass (JS-basiert im WebView) ---
    private var compassVisible = false

    // Sensoren
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var accelerometer: Sensor? = null
    private val magneticFieldValues = FloatArray(3)
    private val accelerometerValues = FloatArray(3)
    private var hasMagneticField = false
    private var hasAccelerometer = false
    private var lastAzimuth = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    lastAzimuth = (azimuthDeg + 360) % 360
                    if (compassVisible) sendAzimuthToWeb()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, magneticFieldValues, 0, 3)
                    hasMagneticField = true
                    computeOrientationFromFusion()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
                    hasAccelerometer = true
                    computeOrientationFromFusion()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun computeOrientationFromFusion() {
        if (!hasMagneticField || !hasAccelerometer) return
        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)
        if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, accelerometerValues, magneticFieldValues)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            lastAzimuth = (azimuthDeg + 360) % 360
            if (compassVisible) sendAzimuthToWeb()
        }
    }

    private fun sendAzimuthToWeb() {
        Log.d(TAG, "sendAzimuth: azimuth=$lastAzimuth")
        val fragment = fragmentManager.findFragmentById(R.id.webview_container) as? MainFragment
        fragment?.webView?.evaluateJavascript(
            "window.__compassAzimuth=$lastAzimuth;",
            null
        )
    }

    inner class CompassInterface {
        @android.webkit.JavascriptInterface
        fun setTarget(lat: Double, lng: Double, label: String) {
            Log.d(TAG, "setTarget: $label lat=$lat lon=$lng")
        }
        @android.webkit.JavascriptInterface
        fun clearTarget() {
            Log.d(TAG, "clearTarget")
        }
        @android.webkit.JavascriptInterface
        fun startCompass() {
            runOnUiThread {
                compassVisible = true
                magnetometer?.let {
                    sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                }
                accelerometer?.let {
                    sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                }
                Log.d(TAG, "startCompass: magnetometer=${magnetometer!=null} accelerometer=${accelerometer!=null}")
            }
        }
        @android.webkit.JavascriptInterface
        fun stopCompass() {
            runOnUiThread {
                compassVisible = false
                sensorManager.unregisterListener(sensorListener)
            }
        }
    }

    private fun updateEventId(intent: Intent?) {
        intent?.getStringExtra("eventId")?.let { pendingEventId = it.toLongOrNull() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (magnetometer == null) {
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Log.d(TAG, "sensor: TYPE_ROTATION_VECTOR NULL, fallback MAGNETIC_FIELD=${magnetometer!=null} ACCELEROMETER=${accelerometer!=null}")
        } else {
            Log.d(TAG, "sensor: TYPE_ROTATION_VECTOR OK")
        }

        updateEventId(intent)
        initContent()
    }

    override fun onResume() {
        super.onResume()
        if (compassVisible) {
            magnetometer?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            accelerometer?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (compassVisible) {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(sensorListener)
    }

    private fun initContent() {
        val ft = fragmentManager.beginTransaction()
        ft.add(R.id.webview_container, MainFragment())
        ft.commit()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        updateEventId(intent)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(MainFragment.EVENT_EVENT))
    }

    override fun onBackPressed() {
        val fragment = fragmentManager.findFragmentById(R.id.webview_container) as? android.webkit.WebViewFragment
        if (fragment?.webView?.canGoBack() == true) {
            fragment.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object { private val TAG = "TrackerCompass"
        const val PREFERENCE_URL = "url"
    }
}


