package ai.voitta.virgil

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Where the phone thinks it is. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val stale: Boolean,
)

/** What that point is called, administratively. */
data class Place(
    val displayName: String,
    val houseNumber: String?,
    val road: String?,
    val neighbourhood: String?,
    val city: String?,
    val county: String?,
    val state: String?,
    val postcode: String?,
) {
    /** House number and road, when both are known. */
    val street: String?
        get() {
            val retval = when {
                road == null -> null
                houseNumber == null -> road
                else -> "$houseNumber $road"
            }
            return retval
        }
}

class LookupFailed(message: String) : Exception(message)

private const val FIX_TIMEOUT_MS = 15_000L

// Nominatim's usage policy requires a User-Agent identifying the application.
private const val USER_AGENT = "Virgil/0.1 (+https://github.com/voitta-ai/virgil)"

/**
 * A current fix, or the last known one marked stale, or null if neither is
 * available. Caller must hold a location permission.
 */
@SuppressLint("MissingPermission")
suspend fun currentFix(context: Context): Fix? {
    val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) { awaitCurrentLocation(context) }
    if (fresh != null) {
        val retval = Fix(fresh.latitude, fresh.longitude, fresh.accuracy, stale = false)
        return retval
    }
    val last = awaitLastLocation(context)
    val retval = last?.let { Fix(it.latitude, it.longitude, it.accuracy, stale = true) }
    return retval
}

@SuppressLint("MissingPermission")
private suspend fun awaitCurrentLocation(context: Context): Location? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellation = CancellationTokenSource()
    val retval = suspendCancellableCoroutine { continuation ->
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location -> continuation.resume(location) }
            .addOnFailureListener { continuation.resume(null) }
        continuation.invokeOnCancellation { cancellation.cancel() }
    }
    return retval
}

@SuppressLint("MissingPermission")
private suspend fun awaitLastLocation(context: Context): Location? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val retval = suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { location -> continuation.resume(location) }
            .addOnFailureListener { continuation.resume(null) }
    }
    return retval
}

/** Reverse geocode a point via Nominatim. Throws [LookupFailed]. */
suspend fun reverseGeocode(lat: Double, lon: Double): Place {
    val retval = withContext(Dispatchers.IO) {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2&lat=$lat&lon=$lon&zoom=18&addressdetails=1"
        )
        val body = fetch(url)
        val parsed = parsePlace(body)
        parsed
    }
    return retval
}

private fun fetch(url: URL): String {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw LookupFailed("Address lookup returned HTTP $code.")
        }
        val retval = connection.inputStream.bufferedReader().use { it.readText() }
        return retval
    } catch (e: IOException) {
        throw LookupFailed("Address lookup could not reach the network.")
    } finally {
        connection.disconnect()
    }
}

private fun parsePlace(body: String): Place {
    val root = JSONObject(body)
    if (root.has("error")) {
        throw LookupFailed("No address is known for this point.")
    }
    val address = root.optJSONObject("address")
    val retval = Place(
        displayName = root.optString("display_name", ""),
        houseNumber = address?.stringOrNull("house_number"),
        road = address?.stringOrNull("road"),
        neighbourhood = address?.firstOf("neighbourhood", "suburb", "quarter"),
        city = address?.firstOf("city", "town", "village", "hamlet"),
        county = address?.stringOrNull("county"),
        state = address?.stringOrNull("state"),
        postcode = address?.stringOrNull("postcode"),
    )
    return retval
}

private fun JSONObject.stringOrNull(key: String): String? {
    val retval = if (has(key) && !isNull(key)) getString(key) else null
    return retval
}

/** Nominatim names the same tier differently by country; take whichever is present. */
private fun JSONObject.firstOf(vararg keys: String): String? {
    val retval = keys.firstNotNullOfOrNull { key -> stringOrNull(key) }
    return retval
}
