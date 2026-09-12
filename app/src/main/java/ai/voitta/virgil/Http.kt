package ai.voitta.virgil

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Both Nominatim and Wikipedia ask callers to identify themselves. Nominatim's
 * usage policy requires it.
 */
const val USER_AGENT = "Virgil/0.1 (+https://github.com/voitta-ai/virgil)"

class LookupFailed(message: String) : Exception(message)

/** A non-2xx response. The status is what decides whether a vendor gets parked. */
class HttpFailure(
    val status: Int,
    val body: String,
) : Exception("HTTP $status: ${body.take(300)}")

/** GET [url] as text. Throws [LookupFailed] on any non-200 or network error. */
fun fetch(url: URL, what: String): String {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw LookupFailed("$what returned HTTP $code.")
        }
        val retval = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        return retval
    } catch (e: IOException) {
        throw LookupFailed("$what could not reach the network.")
    } finally {
        connection.disconnect()
    }
}

/** POST JSON and return the response body. Throws [HttpFailure] on non-2xx. */
fun postJson(url: URL, bearerToken: String, body: String, timeoutMs: Int): String {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        // OpenRouter attributes traffic with these; harmless elsewhere.
        connection.setRequestProperty("HTTP-Referer", "https://github.com/voitta-ai/virgil")
        connection.setRequestProperty("X-Title", "Virgil")
        connection.connectTimeout = 15_000
        connection.readTimeout = timeoutMs

        connection.outputStream.use { output -> output.write(body.toByteArray()) }

        val code = connection.responseCode
        if (code < 200 || code >= 300) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw HttpFailure(code, error)
        }
        val retval = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        return retval
    } catch (e: IOException) {
        throw HttpFailure(0, e.message ?: "network error")
    } finally {
        connection.disconnect()
    }
}
