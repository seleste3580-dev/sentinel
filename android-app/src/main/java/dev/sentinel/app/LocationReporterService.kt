package dev.sentinel.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationReporterService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Device protection", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sentinel location sharing is on")
            .setContentText("Your linked account can see this phone’s latest reported location.")
            .setOngoing(true).build()
        startForeground(41, note)
        SecureSession.saveTracking(this, true)

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { stopSelf(); return START_NOT_STICKY }
        try {
            val provider = listOfNotNull(
                LocationManager.GPS_PROVIDER.takeIf { fine && locationManager.isProviderEnabled(it) },
                LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(it) },
            ).firstOrNull()
            if (provider == null) { stopSelf(); return START_NOT_STICKY }
            locationManager.requestLocationUpdates(provider, 60_000L, 25f, this)
            locationManager.getLastKnownLocation(provider)?.let(::reportLocation)
        } catch (_: SecurityException) { stopSelf(); return START_NOT_STICKY }
        catch (_: IllegalArgumentException) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    override fun onLocationChanged(location: Location) = reportLocation(location)

    private fun reportLocation(location: Location) {
        val ageMs = System.currentTimeMillis() - location.time
        if (ageMs !in -60_000L..120_000L) return
        val token = SecureSession.token(this) ?: return
        val deviceId = SecureSession.deviceId(this) ?: return
        scope.launch {
            runCatching { TrackerApi.updateLocation(token, deviceId, location.latitude, location.longitude, location.accuracy) }
        }
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        SecureSession.saveTracking(this, false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "sentinel_location"
        const val ACTION_STOP = "dev.sentinel.app.STOP_LOCATION"
    }
}
