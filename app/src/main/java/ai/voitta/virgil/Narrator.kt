package ai.voitta.virgil

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import kotlin.math.roundToInt

/** What Virgil has to say, which rung said it, and what it cost. */
data class Blurb(
    val text: String,
    val vendor: String,
    val model: String,
    /** False means this run did not have web search at all. See [Vendor.webSearch]. */
    val webSearchAvailable: Boolean,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Reported by the vendor when it can; null when it does not say. */
    val costUsd: Double?,
    val attempts: List<VendorAttempt>,
)

/** One rung's outcome, so a quiet walk down the chain is visible afterwards. */
data class VendorAttempt(val vendor: String, val outcome: String)

class NarrationFailed(
    message: String,
    /** The walk that led here. Carried on the failure so the log keeps it. */
    val attempts: List<VendorAttempt> = emptyList(),
) : Exception(message)

private const val MAX_TOKENS = 4000
private const val MAX_WEB_RESULTS = 3
private const val REQUEST_TIMEOUT_MS = 120_000

/** How much of each article intro is worth sending. */
private const val INTRO_BUDGET = 600

private const val WEB_SEARCH_CLAUSE = """
When the retrieved articles are all far away, reach for the web instead. Try the
street name, the subdivision or neighbourhood name, what the land was before it
was built on, who it was named for, the township, the county historical society.
Ordinary places have histories. They are simply not in Wikipedia.
"""

private const val NO_WEB_SEARCH_CLAUSE = """
You have no web access on this request. Everything you know about this place is
either in the material below or already in your memory, and your memory of one
specific residential street is almost certainly nothing at all. Do not reason
your way to a plausible-sounding history. If the retrieved articles are all far
away and you have nothing solid, say exactly that.
"""

private const val SYSTEM_PROMPT = """
You are Virgil, a guide. Someone is standing at a specific point and wants to
know something worth knowing about where they are.

Write 120 to 180 words, to be read aloud. Second person, present tense,
conversational. A guide talking, not an encyclopedia being read.

Lead with the most specific true thing you have. Never open with the
administrative identity of the place. They already know what county they are
in, and it is the dullest thing you could tell them.

You are given nearby Wikipedia articles with their distance in metres. Distance
governs how you may use them:
- Under about 200 m: this is where they are standing. Lead with it.
- 200 m to 1 km: nearby. Usable, but say how far away it is.
- Over 1 km: a different place. Do not present it as where they are. At most it
  is context, and usually it is not worth mentioning at all.

Hedge explicitly when you are inferring rather than sourcing. "I am not certain,
but" is better than false confidence.

If you genuinely have nothing, say so plainly, then offer the nearest genuinely
interesting thing and how far away it is. Never invent. A fabricated detail is
worse than silence, because the listener cannot tell it from a real one.

Do not read out citations. No headings, no bullet points, no markdown. Plain
spoken prose only.
"""

/**
 * Walk the waterfall until a rung answers.
 *
 * Call sites ask for narration, never for a vendor. Which rung served is
 * reported back on [Blurb] rather than logged and forgotten, because the
 * failure mode of a waterfall is that it works: a dead rung is experienced as
 * the app being slow and flaky, not as an error.
 */
suspend fun narrate(context: Context, retrieval: Retrieval): Blurb {
    val retval = withContext(Dispatchers.IO) {
        val chain = Providers.enabled(context)
        if (chain.isEmpty()) {
            throw NarrationFailed("No providers chosen.")
        }

        val attempts = mutableListOf<VendorAttempt>()

        for (vendor in chain) {
            val apiKey = ApiKeyStore.get(context, vendor.name)
            if (apiKey == null) {
                attempts.add(VendorAttempt(vendor.name, "no key"))
                continue
            }
            if (VendorParking.isParked(context, vendor.name)) {
                attempts.add(VendorAttempt(vendor.name, "parked"))
                continue
            }

            try {
                val blurb = callVendor(vendor, apiKey, retrieval, attempts)
                VendorParking.clear(context, vendor.name)
                attempts.add(VendorAttempt(vendor.name, "served"))
                return@withContext blurb.copy(attempts = attempts.toList())
            } catch (e: HttpFailure) {
                val park = parkSecondsFor(e.status, e.body)
                if (park > 0) {
                    VendorParking.park(context, vendor.name, park)
                }
                val parked = if (park > 0) ", parked ${park}s" else ""
                attempts.add(
                    VendorAttempt(vendor.name, "HTTP ${e.status}$parked: ${reason(e.body)}")
                )
            } catch (e: Exception) {
                attempts.add(VendorAttempt(vendor.name, e.message ?: "failed"))
            }
        }

        val report = attempts.joinToString("; ") { attempt -> "${attempt.vendor}: ${attempt.outcome}" }
        throw NarrationFailed("Every provider failed. $report", attempts.toList())
    }
    return retval
}

private fun callVendor(
    vendor: Vendor,
    apiKey: String,
    retrieval: Retrieval,
    attempts: MutableList<VendorAttempt>,
): Blurb {
    val body = JSONObject()
    body.put("model", vendor.model)
    body.put("max_tokens", MAX_TOKENS)

    // A rung without web search must not be told to search: that instruction
    // is an invitation to invent, which is the one unrecoverable failure.
    val searchClause = if (vendor.webSearch) WEB_SEARCH_CLAUSE else NO_WEB_SEARCH_CLAUSE
    val system = SYSTEM_PROMPT.trim() + "\n\n" + searchClause.trim()

    val messages = JSONArray()
    messages.put(JSONObject().put("role", "system").put("content", system))
    messages.put(JSONObject().put("role", "user").put("content", describe(retrieval)))
    body.put("messages", messages)

    if (vendor.webSearch) {
        val plugins = JSONArray()
        plugins.put(JSONObject().put("id", "web").put("max_results", MAX_WEB_RESULTS))
        body.put("plugins", plugins)
    }
    // Ask the vendor to price the call rather than hardcoding a rate card that
    // goes stale and differs per rung -- but only where the field exists.
    if (vendor.costReporting) {
        body.put("usage", JSONObject().put("include", true))
    }

    val url = URL("${vendor.baseUrl}/chat/completions")
    val response = postJson(url, apiKey, body.toString(), REQUEST_TIMEOUT_MS)

    val root = JSONObject(response)
    val message = root.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
    val text = message?.optString("content", "")?.trim() ?: ""

    if (text.isEmpty()) {
        val finish = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optString("finish_reason", "unknown") ?: "unknown"
        throw NarrationFailed("Virgil had nothing to say (finish_reason: $finish).")
    }

    val usage = root.optJSONObject("usage")
    val cost = if (usage != null && usage.has("cost") && !usage.isNull("cost")) {
        usage.optDouble("cost")
    } else {
        null
    }

    val retval = Blurb(
        text = text,
        vendor = vendor.name,
        model = vendor.model,
        webSearchAvailable = vendor.webSearch,
        inputTokens = usage?.optLong("prompt_tokens", 0L) ?: 0L,
        outputTokens = usage?.optLong("completion_tokens", 0L) ?: 0L,
        costUsd = cost,
        attempts = attempts.toList(),
    )
    return retval
}

/**
 * The useful sentence out of an error body. Without this the walk records a
 * bare status, which is exactly as much as you already knew.
 */
private fun reason(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) {
        return "no body"
    }
    val retval = try {
        val root = if (trimmed.startsWith("[")) {
            org.json.JSONArray(trimmed).optJSONObject(0)
        } else {
            JSONObject(trimmed)
        }
        val message = root?.optJSONObject("error")?.optString("message", "")
        if (message.isNullOrBlank()) trimmed.take(160) else message.take(160)
    } catch (e: Exception) {
        trimmed.take(160)
    }
    return retval
}

/** The user-message half of the prompt: everything the structured sources found. */
private fun describe(retrieval: Retrieval): String {
    val place = retrieval.place
    val fix = retrieval.fix
    val text = StringBuilder()

    text.appendLine("Coordinates: ${fix.lat}, ${fix.lon}")
    text.appendLine("Fix accuracy: ${fix.accuracyM.roundToInt()} m")
    if (fix.stale) {
        text.appendLine("Note: this is a last known position, not a fresh fix.")
    }
    text.appendLine()

    text.appendLine("Reverse geocode:")
    if (place.displayName.isNotEmpty()) {
        text.appendLine("  full: ${place.displayName}")
    }
    place.street?.let { street -> text.appendLine("  street: $street") }
    place.neighbourhood?.let { area -> text.appendLine("  neighbourhood: $area") }
    place.city?.let { city -> text.appendLine("  city: $city") }
    place.county?.let { county -> text.appendLine("  county: $county") }
    place.state?.let { state -> text.appendLine("  state: $state") }
    place.postcode?.let { code -> text.appendLine("  postcode: $code") }
    text.appendLine()

    if (retrieval.candidates.isEmpty()) {
        text.appendLine("Nearby Wikipedia articles: none within 10 km.")
    } else {
        text.appendLine("Nearby Wikipedia articles, nearest first:")
        for (candidate in retrieval.candidates) {
            val distance = if (candidate.distanceM.isNaN()) {
                "distance unknown"
            } else {
                "${candidate.distanceM.roundToInt()} m"
            }
            text.appendLine("- ${candidate.title} ($distance)")
            candidate.intro?.let { intro ->
                text.appendLine("  ${intro.take(INTRO_BUDGET)}")
            }
        }
    }

    val retval = text.toString()
    return retval
}
