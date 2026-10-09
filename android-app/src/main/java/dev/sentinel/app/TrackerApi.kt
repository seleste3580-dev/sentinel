package dev.sentinel.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TrackedDevice(
    val id: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val lastSeen: String?,
    val isLost: Boolean,
)

object TrackerApi {
    private val base get() = BuildConfig.API_BASE_URL.trimEnd('/')

    private fun request(path: String, method: String, token: String? = null, body: JSONObject? = null): String {
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { connection.outputStream.use { stream -> stream.write(it.toString().toByteArray()) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(JSONObject(text).optString("error", "Request failed ($status)"))
            return text
        } finally { connection.disconnect() }
    }

    fun authenticate(email: String, password: String, create: Boolean): String {
        val result = request(if (create) "/v1/register" else "/v1/login", "POST", body = JSONObject().put("email", email).put("password", password))
        return JSONObject(result).getString("token")
    }

    fun googleNonce(): String = JSONObject(request("/v1/google/nonce", "POST")).getString("nonce")

    fun googleLogin(idToken: String, nonce: String): String {
        val body = JSONObject().put("id_token", idToken).put("nonce", nonce)
        return JSONObject(request("/v1/google/login", "POST", body = body)).getString("token")
    }

    fun logout(token: String) { request("/v1/logout", "POST", token) }

    fun addDevice(token: String, name: String): String = JSONObject(request("/v1/devices", "POST", token, JSONObject().put("name", name))).getString("id")

    fun devices(token: String): List<TrackedDevice> {
        val rows = JSONArray(request("/v1/devices", "GET", token))
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let { d ->
            TrackedDevice(d.getString("id"), d.getString("name"), if (d.isNull("latitude")) null else d.getDouble("latitude"),
                if (d.isNull("longitude")) null else d.getDouble("longitude"), if (d.isNull("last_seen")) null else d.getString("last_seen"), d.getBoolean("is_lost"))
        } }
    }

    fun updateLocation(token: String, deviceId: String, latitude: Double, longitude: Double, accuracy: Float?) {
        check(SentinelNative.validCoordinates(latitude, longitude)) { "Invalid coordinates" }
        val body = JSONObject().put("latitude", latitude).put("longitude", longitude)
        accuracy?.let { body.put("accuracy_m", it.toDouble()) }
        request("/v1/devices/$deviceId/location", "PUT", token, body)
    }

    fun markLost(token: String, deviceId: String, isLost: Boolean) {
        request("/v1/devices/$deviceId/lost", "POST", token, JSONObject().put("is_lost", isLost))
    }
}
