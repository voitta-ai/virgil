package ai.voitta.virgil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/** A Wikipedia article with coordinates near the point. */
data class WikiCandidate(
    val pageId: Long,
    val title: String,
    val distanceM: Double,
    val intro: String?,
)

private const val SEARCH_RADIUS_M = 10_000
private const val SEARCH_LIMIT = 20

/** How many of the nearest articles get their intro paragraph fetched. */
private const val INTRO_LIMIT = 8

private const val API = "https://en.wikipedia.org/w/api.php"

/**
 * Wikipedia articles with coordinates within [SEARCH_RADIUS_M] of the point,
 * nearest first, with intro text for the closest [INTRO_LIMIT].
 *
 * An empty list is a normal, expected result -- most of the world is not
 * within 10 km of anything Wikipedia has written about. That case is the whole
 * point of the experiment, not an error.
 */
suspend fun nearbyArticles(lat: Double, lon: Double): List<WikiCandidate> {
    val retval = withContext(Dispatchers.IO) {
        val found = geosearch(lat, lon)
        val withIntros = if (found.isEmpty()) found else attachIntros(found)
        withIntros
    }
    return retval
}

private fun geosearch(lat: Double, lon: Double): List<WikiCandidate> {
    // gscoord joins lat and lon with a pipe, which has to be percent-encoded.
    val url = URL(
        "$API?action=query&list=geosearch" +
            "&gscoord=$lat%7C$lon" +
            "&gsradius=$SEARCH_RADIUS_M" +
            "&gslimit=$SEARCH_LIMIT" +
            "&gsprop=type%7Cname" +
            "&format=json"
    )
    val body = fetch(url, "Nearby article lookup")
    val results = JSONObject(body)
        .optJSONObject("query")
        ?.optJSONArray("geosearch")

    if (results == null) {
        val retval = emptyList<WikiCandidate>()
        return retval
    }

    val candidates = mutableListOf<WikiCandidate>()
    for (index in 0 until results.length()) {
        val item = results.getJSONObject(index)
        candidates.add(
            WikiCandidate(
                pageId = item.getLong("pageid"),
                title = item.getString("title"),
                distanceM = item.optDouble("dist", Double.NaN),
                intro = null,
            )
        )
    }
    val retval = candidates.sortedBy { candidate -> candidate.distanceM }
    return retval
}

private fun attachIntros(candidates: List<WikiCandidate>): List<WikiCandidate> {
    val wanted = candidates.take(INTRO_LIMIT)
    val ids = wanted.joinToString("%7C") { candidate -> candidate.pageId.toString() }
    val url = URL(
        "$API?action=query&prop=extracts&exintro&explaintext" +
            "&pageids=$ids" +
            "&format=json"
    )
    val body = fetch(url, "Article intro lookup")
    val pages = JSONObject(body)
        .optJSONObject("query")
        ?.optJSONObject("pages")

    if (pages == null) {
        return candidates
    }

    val introsById = mutableMapOf<Long, String>()
    val keys = pages.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val page = pages.optJSONObject(key) ?: continue
        val extract = page.optString("extract", "")
        if (extract.isNotBlank()) {
            introsById[page.optLong("pageid")] = extract
        }
    }

    val retval = candidates.map { candidate ->
        candidate.copy(intro = introsById[candidate.pageId])
    }
    return retval
}
