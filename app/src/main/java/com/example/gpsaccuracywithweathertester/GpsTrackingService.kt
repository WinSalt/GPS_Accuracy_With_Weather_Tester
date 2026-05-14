package com.example.gpsaccuracywithweathertester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GpsTrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var scheduler: ScheduledExecutorService

    @Volatile
    private var latestSample: JSONObject? = null
    private val pendingSamples = mutableListOf<JSONObject>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        scheduler = Executors.newSingleThreadScheduledExecutor()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Trwa zbieranie danych GPS"))
        startLocationUpdates()
        startUploadScheduler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, this)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun startUploadScheduler() {
        scheduler.scheduleAtFixedRate({
            val snapshot = latestSample ?: return@scheduleAtFixedRate
            synchronized(pendingSamples) {
                pendingSamples.add(JSONObject(snapshot.toString()))
            }
            postPendingSamples()
        }, 5, 5, TimeUnit.MINUTES)
    }

    override fun onLocationChanged(location: Location) {
        latestSample = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy_m", location.accuracy)
            .put("speed_mps", location.speed)
            .put("provider", location.provider)
    }

    private fun postPendingSamples() {
        val webhookUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_WEBHOOK_URL, null) ?: return

        val samplesToSend = synchronized(pendingSamples) {
            if (pendingSamples.isEmpty()) return
            pendingSamples.map { JSONObject(it.toString()) }
        }

        val includeHeaders = !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HEADERS_SENT, false)
        val payload = buildRowsPayload(samplesToSend, includeHeaders)

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            BufferedOutputStream(connection.outputStream).use { out ->
                out.write(payload.toString().toByteArray())
            }

            val success = connection.responseCode in 200..299
            if (success) {
                synchronized(pendingSamples) {
                    pendingSamples.clear()
                }
                if (includeHeaders) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HEADERS_SENT, true).apply()
                }
            }
        } catch (_: Exception) {
            // dane zostają w pendingSamples i zostaną wysłane w następnym cyklu
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildRowsPayload(samples: List<JSONObject>, includeHeaders: Boolean): JSONObject {
        val headers = listOf("timestamp", "latitude", "longitude", "accuracy_m", "speed_mps", "provider")
        val rows = JSONArray()

        for (sample in samples) {
            val row = JSONArray()
            headers.forEach { key -> row.put(sample.opt(key)) }
            rows.put(row)
        }

        return JSONObject()
            .put("include_headers", includeHeaders)
            .put("headers", JSONArray(headers))
            .put("rows", rows)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        scheduler.shutdownNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPS Logger", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Accuracy Logger")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PREFS = "gps_logger_prefs"
        const val PREF_WEBHOOK_URL = "pref_webhook_url"
        const val PREF_HEADERS_SENT = "pref_headers_sent"
        private const val CHANNEL_ID = "gps_logger_channel"
        private const val NOTIFICATION_ID = 1001
    }
}