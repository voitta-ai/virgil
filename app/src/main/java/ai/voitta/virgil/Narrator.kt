package ai.voitta.virgil

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.roundToInt

/** What Virgil has to say, plus what it cost to say it. */
data class Blurb(
    val text: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val webSearches: Long,
    val costUsd: Double,
)

class NarrationFailed(message: String) : Exception(message)

const val NARRATION_MODEL = "claude-opus-5"
const val NARRATION_EFFORT = "low"

// claude-opus-5 list price. Web search is billed separately per search at a rate
// not folded in here; the evaluation log records the search count instead so the
// real figure can be reconstructed.
private const val INPUT_USD_PER_TOKEN = 5.0 / 1_000_000
private const val OUTPUT_USD_PER_TOKEN = 25.0 / 1_000_000

private const val MAX_TOKENS = 2000L
private const val MAX_WEB_SEARCHES = 3L

/** How much of each article intro is worth sending. */
private const val INTRO_BUDGET = 600

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

When the retrieved articles are all far away, search the web instead. Try the
street name, the subdivision or neighbourhood name, what the land was before it
was built on, who it was named for, the township, the county historical society.
Ordinary places have histories. They are simply not in Wikipedia.

Hedge explicitly when you are inferring rather than sourcing. "I am not certain,
but" is better than false confidence.

If you genuinely have nothing, say so plainly, then offer the nearest genuinely
interesting thing and how far away it is. Never invent. A fabricated detail is
worse than silence, because the listener cannot tell it from a real one.

Do not read out citations. No headings, no bullet points, no markdown. Plain
spoken prose only.
"""

/** Turn what the structured sources found into something worth hearing. */
suspend fun narrate(apiKey: String, retrieval: Retrieval): Blurb {
    val retval = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

        val search = WebSearchTool20260209.builder()
            .maxUses(MAX_WEB_SEARCHES)
            .userLocation(userLocation(retrieval.place))
            .build()

        val params = MessageCreateParams.builder()
            .model(NARRATION_MODEL)
            .maxTokens(MAX_TOKENS)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(SYSTEM_PROMPT.trim())
            .addTool(search)
            .addUserMessage(describe(retrieval))
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: Exception) {
            throw NarrationFailed(e.message ?: "The narration call failed.")
        }

        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
            .trim()

        if (text.isEmpty()) {
            val reason = message.stopReason().map { stop -> stop.toString() }.orElse("no text")
            throw NarrationFailed("Virgil had nothing to say ($reason).")
        }

        val usage = message.usage()
        val searches = usage.serverToolUse().map { tools -> tools.webSearchRequests() }.orElse(0L)
        val cost = usage.inputTokens() * INPUT_USD_PER_TOKEN +
            usage.outputTokens() * OUTPUT_USD_PER_TOKEN

        Blurb(
            text = text,
            inputTokens = usage.inputTokens(),
            outputTokens = usage.outputTokens(),
            webSearches = searches,
            costUsd = cost,
        )
    }
    return retval
}

private fun userLocation(place: Place): UserLocation {
    val builder = UserLocation.builder()
    place.city?.let { city -> builder.city(city) }
    place.state?.let { state -> builder.region(state) }
    place.countryCode?.let { code -> builder.country(code.uppercase()) }
    builder.timezone(TimeZone.getDefault().id)
    val retval = builder.build()
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
