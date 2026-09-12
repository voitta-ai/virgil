package ai.voitta.virgil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * One JSON object per run, appended to a JSONL file.
 *
 * This file is the actual deliverable of v0.1 -- the app exists to produce it.
 * It records what was retrieved, what was said, what it cost, how long it took,
 * and what the listener thought of it.
 *
 * It never contains the API key.
 */
object EvalLog {

    private const val FILE_NAME = "virgil-eval.jsonl"

    fun file(context: Context): File {
        val retval = File(context.filesDir, FILE_NAME)
        return retval
    }

    fun entryCount(context: Context): Int {
        val target = file(context)
        if (!target.exists()) {
            return 0
        }
        val retval = target.readLines().count { line -> line.isNotBlank() }
        return retval
    }

    fun append(context: Context, entry: JSONObject) {
        file(context).appendText(entry.toString() + "\n")
    }

    /**
     * Ratings always apply to the most recent run, so the last line is rewritten
     * rather than a second record appended. Keeps one object per run, which is
     * what makes the file easy to count.
     */
    fun rateLast(context: Context, rating: String) {
        val target = file(context)
        if (!target.exists()) {
            return
        }
        val lines = target.readLines().filter { line -> line.isNotBlank() }
        if (lines.isEmpty()) {
            return
        }
        val last = JSONObject(lines.last())
        last.put("rating", rating)
        val rewritten = lines.dropLast(1) + last.toString()
        target.writeText(rewritten.joinToString("\n") + "\n")
    }

    fun buildEntry(
        retrieval: Retrieval,
        blurb: Blurb?,
        blurbError: String?,
        latency: Latency,
        // Taken separately rather than off the blurb: on total failure there is
        // no blurb, and that is exactly when the walk is worth having.
        attempts: List<VendorAttempt>,
    ): JSONObject {
        val fix = retrieval.fix
        val place = retrieval.place

        val entry = JSONObject()
        entry.put("ts", timestamp())
        entry.put("lat", fix.lat)
        entry.put("lon", fix.lon)
        entry.put("accuracy_m", fix.accuracyM.roundToInt())
        entry.put("stale", fix.stale)

        val geocode = JSONObject()
        geocode.put("display_name", place.displayName)
        geocode.put("street", place.street ?: JSONObject.NULL)
        geocode.put("neighbourhood", place.neighbourhood ?: JSONObject.NULL)
        geocode.put("city", place.city ?: JSONObject.NULL)
        geocode.put("county", place.county ?: JSONObject.NULL)
        geocode.put("state", place.state ?: JSONObject.NULL)
        geocode.put("postcode", place.postcode ?: JSONObject.NULL)
        entry.put("reverse_geocode", geocode)

        val candidates = JSONArray()
        for (candidate in retrieval.candidates) {
            val item = JSONObject()
            item.put("page_id", candidate.pageId)
            item.put("title", candidate.title)
            item.put(
                "distance_m",
                if (candidate.distanceM.isNaN()) JSONObject.NULL
                else candidate.distanceM.roundToInt()
            )
            item.put("has_intro", candidate.intro != null)
            candidates.put(item)
        }
        entry.put("wiki_candidates", candidates)
        entry.put("wiki_error", retrieval.candidatesError ?: JSONObject.NULL)

        // The discriminating number: how far away the nearest thing Wikipedia
        // knows about actually is. See the step 3 finding on issue #1.
        val nearest = retrieval.candidates
            .map { candidate -> candidate.distanceM }
            .filter { distance -> !distance.isNaN() }
            .minOrNull()
        entry.put("nearest_candidate_m", nearest?.roundToInt() ?: JSONObject.NULL)

        entry.put("vendor", blurb?.vendor ?: JSONObject.NULL)
        entry.put("model", blurb?.model ?: JSONObject.NULL)
        // A run served by a rung without web search is measuring something
        // different from one that had it. See the step 3 finding on issue #1.
        entry.put("web_search_available", blurb?.webSearchAvailable ?: JSONObject.NULL)

        // The whole walk, not just the winner: a waterfall's failure mode is
        // that it quietly works while rungs above are dead.
        val walk = JSONArray()
        for (attempt in attempts) {
            walk.put(JSONObject().put("vendor", attempt.vendor).put("outcome", attempt.outcome))
        }
        entry.put("vendor_attempts", walk)
        entry.put("blurb", blurb?.text ?: JSONObject.NULL)
        entry.put("blurb_error", blurbError ?: JSONObject.NULL)

        val usage = JSONObject()
        usage.put("input_tokens", blurb?.inputTokens ?: JSONObject.NULL)
        usage.put("output_tokens", blurb?.outputTokens ?: JSONObject.NULL)
        entry.put("usage", usage)
        // Priced by the vendor rather than from a hardcoded rate card, which
        // would go stale and differs per rung. Null when the vendor does not say.
        entry.put("cost_usd", blurb?.costUsd ?: JSONObject.NULL)

        val timings = JSONObject()
        timings.put("fix", latency.fixMs)
        timings.put("retrieve", latency.retrieveMs)
        timings.put("narrate", latency.narrateMs)
        timings.put("total", latency.totalMs)
        entry.put("latency_ms", timings)

        entry.put("rating", JSONObject.NULL)
        entry.put("note", JSONObject.NULL)

        return entry
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val retval = format.format(Date())
        return retval
    }
}

/** Per-stage wall clock, in milliseconds. */
data class Latency(
    val fixMs: Long,
    val retrieveMs: Long,
    val narrateMs: Long,
    val totalMs: Long,
)
