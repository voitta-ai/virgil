package ai.voitta.virgil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Everything the structured sources know about the point. */
data class Retrieval(
    val fix: Fix,
    val place: Place,
    val candidates: List<WikiCandidate>,
    /** Set when the article lookup failed. Not fatal -- narration can proceed. */
    val candidatesError: String?,
)

sealed interface UiState {
    data object Idle : UiState
    data object Locating : UiState
    data object Retrieving : UiState
    data class Narrating(val retrieval: Retrieval) : UiState
    data class Ready(
        val retrieval: Retrieval,
        val blurb: Blurb?,
        val blurbError: String?,
        val rating: String?,
    ) : UiState
    data class Failed(val message: String) : UiState
}

class VirgilViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val mutableEnabled = MutableStateFlow(
        Providers.enabled(application).map { vendor -> vendor.name }
    )
    val enabledProviders: StateFlow<List<String>> = mutableEnabled.asStateFlow()

    private val mutableMissingKeys = MutableStateFlow(vendorsWithoutKeys())
    val missingKeys: StateFlow<List<Vendor>> = mutableMissingKeys.asStateFlow()

    private val mutableWarning = MutableStateFlow(waterfallWarning(application))
    val warning: StateFlow<String?> = mutableWarning.asStateFlow()

    private val mutableLogCount = MutableStateFlow(EvalLog.entryCount(application))
    val logCount: StateFlow<Int> = mutableLogCount.asStateFlow()

    private val mutableSpeaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = mutableSpeaking.asStateFlow()

    /**
     * The engine binds a service, so it is created once and released in
     * onCleared. Its callbacks arrive on a binder thread; StateFlow tolerates
     * that, and Compose collects on the main thread regardless.
     */
    private val speaker = Speaker(application) { speaking ->
        mutableSpeaking.value = speaking
    }

    fun stopSpeaking() {
        speaker.stop()
    }

    override fun onCleared() {
        super.onCleared()
        speaker.shutdown()
    }

    private fun vendorsWithoutKeys(): List<Vendor> {
        val context = getApplication<Application>()
        val retval = Providers.enabled(context)
            .filter { vendor -> ApiKeyStore.get(context, vendor.credential) == null }
            .distinctBy { vendor -> vendor.credential }
        return retval
    }

    fun setProviderEnabled(name: String, on: Boolean) {
        val context = getApplication<Application>()
        Providers.setEnabled(context, name, on)
        mutableEnabled.value = Providers.enabled(context).map { vendor -> vendor.name }
        mutableMissingKeys.value = vendorsWithoutKeys()
        mutableWarning.value = waterfallWarning(context)
    }

    fun saveApiKey(credential: String, value: String) {
        val context = getApplication<Application>()
        ApiKeyStore.set(context, credential, value)
        // A freshly supplied key deserves an immediate try, not the tail of an
        // old park window -- on every rung that uses it.
        PROVIDER_CATALOG
            .filter { vendor -> vendor.credential == credential }
            .forEach { vendor -> VendorParking.clear(context, vendor.name) }
        mutableMissingKeys.value = vendorsWithoutKeys()
        mutableWarning.value = waterfallWarning(context)
    }

    fun whereAmI() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val startedAt = System.currentTimeMillis()

            mutableState.value = UiState.Locating

            val fix = try {
                currentFix(context)
            } catch (e: SecurityException) {
                mutableState.value = UiState.Failed("Location permission was revoked.")
                return@launch
            }

            if (fix == null) {
                mutableState.value =
                    UiState.Failed("No location fix. Check that location is turned on.")
                return@launch
            }
            val fixedAt = System.currentTimeMillis()

            mutableState.value = UiState.Retrieving

            // Both lookups hit different hosts and neither needs the other's
            // answer, so they run together. Serially they were the single
            // largest slice of wall clock.
            val gathered = try {
                coroutineScope {
                    val geocode = async { reverseGeocode(fix.lat, fix.lon) }
                    val articles = async {
                        try {
                            Pair(nearbyArticles(fix.lat, fix.lon), null as String?)
                        } catch (e: LookupFailed) {
                            // Not fatal: narration has the web, and an empty
                            // candidate set is a normal result anyway.
                            Pair(emptyList<WikiCandidate>(), e.message)
                        }
                    }
                    Pair(geocode.await(), articles.await())
                }
            } catch (e: LookupFailed) {
                mutableState.value = UiState.Failed(e.message ?: "Address lookup failed.")
                return@launch
            }
            val retrievedAt = System.currentTimeMillis()

            val retrieval = Retrieval(
                fix = fix,
                place = gathered.first,
                candidates = gathered.second.first,
                candidatesError = gathered.second.second,
            )

            mutableState.value = UiState.Narrating(retrieval)

            var blurb: Blurb? = null
            var blurbError: String? = null
            var attempts = emptyList<VendorAttempt>()
            try {
                blurb = narrate(context, retrieval)
                attempts = blurb.attempts
            } catch (e: NarrationFailed) {
                blurbError = e.message ?: "Narration failed."
                attempts = e.attempts
            }
            val narratedAt = System.currentTimeMillis()

            val latency = Latency(
                fixMs = fixedAt - startedAt,
                retrieveMs = retrievedAt - fixedAt,
                narrateMs = narratedAt - retrievedAt,
                totalMs = narratedAt - startedAt,
            )

            EvalLog.append(
                context,
                EvalLog.buildEntry(retrieval, blurb, blurbError, latency, attempts),
            )
            mutableLogCount.value = EvalLog.entryCount(context)
            mutableWarning.value = waterfallWarning(context)

            mutableState.value = UiState.Ready(
                retrieval = retrieval,
                blurb = blurb,
                blurbError = blurbError,
                rating = null,
            )

            // Delivery: spoken, and posted as a notification. The notification is
            // redundant behind a button but is the primary surface once the
            // trigger goes passive in v0.2.
            if (blurb != null) {
                speaker.speak(blurb.text)
                withContext(Dispatchers.IO) {
                    Notifier.post(context, blurb.text, retrieval.place)
                }
            }
        }
    }

    fun rate(rating: String) {
        val current = mutableState.value
        if (current !is UiState.Ready) {
            return
        }
        EvalLog.rateLast(getApplication(), rating)
        mutableState.value = current.copy(rating = rating)
    }

    fun permissionDenied() {
        mutableState.value = UiState.Failed(
            "Virgil needs your location to tell you about it. " +
                "Grant location access in Settings."
        )
    }
}
