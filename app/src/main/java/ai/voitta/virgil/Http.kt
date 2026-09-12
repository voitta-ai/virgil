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
