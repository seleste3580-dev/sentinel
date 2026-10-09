package dev.sentinel.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SentinelApp() }
    }
}

@Composable
private fun SentinelApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(SecureSession.token(context)) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("My Android phone") }
    var devices by remember { mutableStateOf<List<TrackedDevice>>(emptyList()) }
    var createAccount by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tracking by remember { mutableStateOf(SecureSession.isTracking(context)) }

    fun refresh() {
        val activeToken = token ?: return
        scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { TrackerApi.devices(activeToken) } }
                .onSuccess { devices = it; message = if (it.isEmpty()) "Add this phone to begin sharing its location." else "Updated device locations." }
                .onFailure { message = it.message ?: "Could not reach Sentinel." }
            busy = false
        }
    }

    LaunchedEffect(token) {
        if (token != null) {
            while (true) {
                refresh()
                delay(60_000)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val locationAllowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notificationAllowed = Build.VERSION.SDK_INT < 33 || result[Manifest.permission.POST_NOTIFICATIONS] == true
        if (locationAllowed && notificationAllowed) {
            val id = SecureSession.deviceId(context)
            if (id != null) {
                val intent = Intent(context, LocationReporterService::class.java)
                ContextCompat.startForegroundService(context, intent)
                SecureSession.saveTracking(context, true)
                tracking = true
                message = "Location sharing is active for this phone."
            }
        } else message = if (!locationAllowed) "Allow location access to share this phone’s location." else "Allow notifications so Android can show the location sharing status."
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = androidx.compose.ui.graphics.Color(0xFF126B62),
        secondary = androidx.compose.ui.graphics.Color(0xFFB36A3C),
        background = androidx.compose.ui.graphics.Color(0xFFF5F7F6),
        surface = androidx.compose.ui.graphics.Color.White,
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("SENTINEL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Your devices,\nwithin reach.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Location updates are shared only with your account while this app is active.", style = MaterialTheme.typography.bodyMedium)

                if (token == null) {
                    OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password (10+ characters)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            runCatching { withContext(Dispatchers.IO) { TrackerApi.authenticate(email, password, createAccount) } }
                                .onSuccess { received -> SecureSession.save(context, received); token = received; message = "Account connected." }
                                .onFailure { message = it.message ?: "Could not sign in." }
                            busy = false
                        }
                    }) { Text(if (busy) "Connecting…" else if (createAccount) "Create account" else "Sign in") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        scope.launch {
                            if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                                message = "Google sign-in needs a Web OAuth client ID. Configure sentinelGoogleWebClientId to enable it."
                                return@launch
                            }
                            busy = true
                            try {
                                val nonce = withContext(Dispatchers.IO) { TrackerApi.googleNonce() }
                                val googleOption = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                                    .setNonce(nonce)
                                    .build()
                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleOption)
                                    .build()
                                val result = CredentialManager.create(context).getCredential(context, request)
                                val credential = result.credential as? CustomCredential
                                    ?: throw IllegalStateException("Choose a Google account to continue.")
                                if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                    throw IllegalStateException("Google did not return a sign-in token. Please try again.")
                                }
                                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                val received = withContext(Dispatchers.IO) {
                                    TrackerApi.googleLogin(googleCredential.idToken, nonce)
                                }
                                SecureSession.save(context, received)
                                token = received
                                message = "Google account connected."
                            } catch (failure: Exception) {
                                message = failure.message ?: "Google sign-in could not be completed."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Continue with Google") }
                    TextButton(onClick = { createAccount = !createAccount }) { Text(if (createAccount) "Already have an account? Sign in" else "New to Sentinel? Create account") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("YOUR DEVICES", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { refresh() }) { Text("Refresh") }
                        TextButton(onClick = {
                            val current = token
                            scope.launch { if (current != null) withContext(Dispatchers.IO) { runCatching { TrackerApi.logout(current) } } }
                            context.startService(Intent(context, LocationReporterService::class.java).setAction(LocationReporterService.ACTION_STOP))
                            SecureSession.saveTracking(context, false)
                            SecureSession.clear(context); token = null; devices = emptyList(); tracking = false
                        }) { Text("Sign out") }
                    }
                    if (devices.any { reportAgeMinutes(it.lastSeen)?.let { age -> age >= 5 } ?: true }) {
                        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFEEF2EF))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Some devices are offline", fontWeight = FontWeight.SemiBold)
                                Text("Their cards show the last location they reported. Refresh when they reconnect.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(deviceName, { deviceName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Add this phone") }, singleLine = true)
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        val activeToken = token ?: return@Button
                        scope.launch {
                            busy = true
                            runCatching { withContext(Dispatchers.IO) { TrackerApi.addDevice(activeToken, deviceName) } }
                                .onSuccess { id -> SecureSession.saveDevice(context, id); refresh(); message = "This phone is linked. Turn on location sharing below." }
                                .onFailure { message = it.message ?: "Could not add this phone." }
                            busy = false
                        }
                    }) { Text("Link this phone") }

                    if (SecureSession.deviceId(context) != null) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (tracking) "Location sharing on" else "This phone is linked", fontWeight = FontWeight.SemiBold)
                                Text("Android shows a persistent notification while location sharing runs.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val permissions = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                                        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                                        permissionLauncher.launch(permissions.toTypedArray())
                                    }) { Text("Start sharing") }
                                    OutlinedButton(onClick = {
                                        context.startService(Intent(context, LocationReporterService::class.java).setAction(LocationReporterService.ACTION_STOP))
                                        SecureSession.saveTracking(context, false)
                                        tracking = false
                                    }) { Text("Stop") }
                                }
                            }
                        }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                        items(devices) { device ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row {
                                        Text(device.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        val reportAge = reportAgeMinutes(device.lastSeen)
                                        val status = when {
                                            device.isLost -> "MARKED LOST"
                                            reportAge != null && reportAge < 5 -> "REPORTING"
                                            else -> "OFFLINE"
                                        }
                                        Text(status, color = if (device.isLost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(if (device.latitude != null && device.longitude != null) "${device.latitude}, ${device.longitude}" else "Waiting for first location", style = MaterialTheme.typography.bodySmall)
                                    Text(device.lastSeen?.let { "Last reported ${relativeReportAge(it)}" } ?: "No location report yet", style = MaterialTheme.typography.labelSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (device.latitude != null && device.longitude != null) {
                                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${device.latitude},${device.longitude}?q=${device.latitude},${device.longitude}(${Uri.encode(device.name)})"))) }) { Text("Open map") }
                                        }
                                        TextButton(onClick = {
                                            val activeToken = token ?: return@TextButton
                                            scope.launch {
                                                runCatching { withContext(Dispatchers.IO) { TrackerApi.markLost(activeToken, device.id, !device.isLost) } }
                                                    .onSuccess { refresh() }.onFailure { message = it.message ?: "Could not update device." }
                                            }
                                        }) { Text(if (device.isLost) "Mark safe" else "Mark lost") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun reportAgeMinutes(timestamp: String?): Long? {
    if (timestamp == null) return null
    return runCatching {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        ((System.currentTimeMillis() - format.parse(timestamp)!!.time) / 60_000).coerceAtLeast(0)
    }.getOrNull()
}

private fun relativeReportAge(timestamp: String): String {
    val minutes = reportAgeMinutes(timestamp) ?: return "at $timestamp UTC"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
