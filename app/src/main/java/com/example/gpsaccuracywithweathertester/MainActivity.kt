package com.example.gpsaccuracywithweathertester

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.gpsaccuracywithweathertester.ui.theme.GPSAccuracyWithWeatherTesterTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startTracker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GPSAccuracyWithWeatherTesterTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    TrackerScreen(
                        modifier = Modifier.padding(padding),
                        onStart = { webhook ->
                            saveWebhook(webhook)
                            ensurePermissionAndStart()
                        },
                        onStop = { stopService(Intent(this, GpsTrackingService::class.java)) }
                    )
                }
            }
        }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracker()
            return
        }
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startTracker() {
        val serviceIntent = Intent(this, GpsTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun saveWebhook(webhookUrl: String) {
        getSharedPreferences(GpsTrackingService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(GpsTrackingService.PREF_WEBHOOK_URL, webhookUrl)
            .putBoolean(GpsTrackingService.PREF_HEADERS_SENT, false)
            .apply()
    }
}

@Composable
private fun TrackerScreen(
    modifier: Modifier = Modifier,
    onStart: (String) -> Unit,
    onStop: () -> Unit
) {
    var webhookUrl by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Logger GPS dla testu miesięcznego")
        OutlinedTextField(
            value = webhookUrl,
            onValueChange = { webhookUrl = it.trim() },
            label = { Text("Webhook URL (Google Apps Script)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onStart(webhookUrl) },
            enabled = webhookUrl.startsWith("http")
        ) { Text("Start zbierania") }

        Button(onClick = onStop) { Text("Stop") }

        Text("Aplikacja co 5 minut wysyła 1 najnowszy punkt; po błędzie doda go do bufora i wyśle zaległe punkty w kolejnym cyklu. Format: wiersze + jednorazowe nagłówki.")
    }
}