package ai.voitta.virgil

import android.content.Context

/**
 * How a rung is spoken to.
 *
 * The waterfall's claim is that rungs can serve the same request, not that they
 * share one JSON shape. Two protocols with an adapter each is honest; what is
 * not allowed is a vendor-specific field smuggled into a shared body.
 */
enum class Protocol {
    /** POST {baseUrl}/chat/completions, OpenAI shaped. */
    OPENAI_COMPAT,

    /**
     * POST {baseUrl}/models/{model}:generateContent, Google shaped.
     *
     * Exists solely because Google's own OpenAI-compatible layer rejects
     * google_search grounding, and grounding is what carries Tier 1.
     */
    GEMINI_NATIVE,
}

/**
 * One rung of the waterfall.
 *
 * Every rung speaks the OpenAI-compatible chat/completions wire format, which
 * is what makes rungs substitutable at all.
 */
data class Vendor(
    val name: String,
    val baseUrl: String,
    val model: String,
    val protocol: Protocol = Protocol.OPENAI_COMPAT,
    /**
     * Which stored credential this rung uses. Defaults to the vendor's own
     * name; two rungs that are the same account behind different protocols
     * share one, so the user is not asked for the same key twice.
     */
    val keyName: String? = null,
    /**
     * Whether this rung can search the web.
     *
     * Not cosmetic. Issue #1 established that web search is what carries the
     * boring-neighbourhood case, so a rung without it does not merely answer
     * worse -- it changes what the experiment is measuring. Recorded per run in
     * the evaluation log for exactly that reason.
     */
    val webSearch: Boolean,
    /**
     * Whether this rung understands `usage: {include: true}` and prices the
     * call back.
     *
     * "OpenAI-compatible" is not uniform. That field is an OpenRouter
     * extension, and Gemini's endpoint rejects the whole request over it
     * ("Unknown name \"usage\": Cannot find field") rather than ignoring it.
     * Vendor-specific extras belong on the vendor, never in the shared body.
     */
    val costReporting: Boolean = false,
) {
    /** The stored credential this rung uses. */
    val credential: String
        get() {
            val retval = keyName ?: name
            return retval
        }
}

/**
 * Providers the user can choose from. Nothing here is enabled by default and no
 * key is shipped -- the user picks the providers and supplies the keys, which
 * live only in [ApiKeyStore] on the device.
 *
 * Only the OpenRouter entry has had its endpoint and model id verified live.
 * The others are the conventional OpenAI-compatible endpoints for those
 * services; if one is wrong the rung reports it in the walk rather than failing
 * silently.
 */
val PROVIDER_CATALOG = listOf(
    Vendor(
        name = "openrouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "anthropic/claude-opus-5",
        webSearch = true,
        costReporting = true,
    ),
    Vendor(
        // Google's native endpoint, which does support google_search grounding.
        // Verified live: it issues real queries and returns sources.
        name = "gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        model = "gemini-3.8-flash",
        protocol = Protocol.GEMINI_NATIVE,
        webSearch = true,
    ),
    Vendor(
        // The same key against Google's OpenAI-compatible layer. Kept as a
        // fallback rung because that layer accepts only OpenAI-shaped tools and
        // so rejects grounding ("Unknown name \"google_search\" at 'tools[0]'").
        // Fine for text; cannot carry Tier 1.
        name = "gemini-compat",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        model = "gemini-3.8-flash",
        keyName = "gemini",
        webSearch = false,
    ),
    Vendor(
        name = "groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
        webSearch = false,
    ),
    Vendor(
        name = "deepseek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        webSearch = false,
    ),
)

/**
 * Which providers the user has turned on, and in what order they are tried.
 *
 * Order is the waterfall: index 0 is primary. Stored as a name list so adding,
 * reordering or dropping a provider is a data change; no call site names a
 * vendor.
 */
object Providers {

    private const val FILE = "virgil_providers"
    private const val KEY_ORDER = "enabled_order"

    fun enabled(context: Context): List<Vendor> {
        val stored = preferences(context).getString(KEY_ORDER, "") ?: ""
        val names = stored.split(",").filter { name -> name.isNotBlank() }
        val retval = names.mapNotNull { name ->
            PROVIDER_CATALOG.firstOrNull { vendor -> vendor.name == name }
        }
        return retval
    }

    fun isEnabled(context: Context, name: String): Boolean {
        val retval = enabled(context).any { vendor -> vendor.name == name }
        return retval
    }

    /** Enabling appends, so the order reflects the order the user turned them on. */
    fun setEnabled(context: Context, name: String, on: Boolean) {
        val current = enabled(context).map { vendor -> vendor.name }.toMutableList()
        current.remove(name)
        if (on) {
            current.add(name)
        }
        preferences(context).edit().putString(KEY_ORDER, current.joinToString(",")).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * A rung that can never serve is a config bug, not a runtime condition, so it
 * is surfaced rather than discovered later as "the app feels flaky".
 */
fun waterfallWarning(context: Context): String? {
    val chain = Providers.enabled(context)
    val keyless = chain.filter { vendor -> ApiKeyStore.get(context, vendor.credential) == null }
    val retval = when {
        chain.isEmpty() -> "No providers chosen. Pick at least one below."
        keyless.isNotEmpty() ->
            "No key for: ${keyless.map { vendor -> vendor.credential }.distinct().joinToString(", ")}. " +
                "Those rungs can never serve."
        chain.size == 1 -> "One provider, so there is no failover."
        else -> null
    }
    return retval
}

/**
 * Vendors temporarily skipped after failing.
 *
 * Without this a rate-limited or unfunded vendor is re-dialled on every single
 * run, so every run pays that vendor's latency to fail before anything answers.
 * Expiries persist so a restart does not un-learn them. Pruning happens on
 * read: no timer, no background work -- the first run after the window simply
 * stops seeing the vendor as parked.
 */
object VendorParking {

    private const val FILE = "virgil_parking"

    fun isParked(context: Context, name: String): Boolean {
        val until = preferences(context).getLong(name, 0L)
        val retval = until > System.currentTimeMillis()
        return retval
    }

    fun park(context: Context, name: String, seconds: Long) {
        val until = System.currentTimeMillis() + seconds * 1000
        preferences(context).edit().putLong(name, until).apply()
    }

    fun clear(context: Context, name: String) {
        preferences(context).edit().remove(name).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * How long a failure takes a vendor out of the chain.
 *
 * LiteLLM (which shmobster uses for the same job) cools on status alone and so
 * misses budget exhaustion, because vendors answer that with 400 or 402 rather
 * than 429. Hence the explicit budget window here.
 */
fun parkSecondsFor(status: Int, body: String): Long {
    val budgetMarkers = listOf(
        "insufficient", "credit", "quota", "billing", "payment required", "usage limit",
    )
    val looksLikeBudget = budgetMarkers.any { marker -> body.lowercase().contains(marker) }
    val retval = when {
        status == 402 || looksLikeBudget -> 3600L
        status == 429 -> 60L
        status == 401 || status == 403 -> 600L
        status >= 500 -> 60L
        else -> 0L
    }
    return retval
}
