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
    private val samples = mutableListOf<JSONObject>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        scheduler = Executors.newSingleThreadScheduledExecutor()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Trwa zbieranie danych GPS"))
        startLocationUpdates()
        startUploadScheduler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5_000L,
                0f,
                this
            )
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun startUploadScheduler() {
        scheduler.scheduleAtFixedRate({
            val payload = synchronized(samples) {
                if (samples.isEmpty()) return@scheduleAtFixedRate null
                JSONArray(samples.toList()).also { samples.clear() }
            } ?: return@scheduleAtFixedRate

            postWebhook(payload)
        }, 5, 5, TimeUnit.MINUTES)
    }

    override fun onLocationChanged(location: Location) {
        val item = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy_m", location.accuracy)
            .put("speed_mps", location.speed)
            .put("provider", location.provider)

        synchronized(samples) {
            samples.add(item)
        }
    }

    private fun postWebhook(data: JSONArray) {
        val webhookUrl = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PREF_WEBHOOK_URL, null)
            ?: return

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val body = JSONObject().put("samples", data).toString()
            BufferedOutputStream(connection.outputStream).use { out ->
                out.write(body.toByteArray())
            }

            connection.responseCode
        } catch (_: Exception) {
            synchronized(samples) {
                for (i in 0 until data.length()) {
                    samples.add(data.getJSONObject(i))
                }
            }
        } finally {
            connection?.disconnect()
        }
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Logger",
                NotificationManager.IMPORTANCE_LOW
            )
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
        private const val CHANNEL_ID = "gps_logger_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
