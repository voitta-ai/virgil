package ai.voitta.virgil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    data object Resolving : UiState
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

    private val mutableHasKey = MutableStateFlow(ApiKeyStore.get(application) != null)
    val hasKey: StateFlow<Boolean> = mutableHasKey.asStateFlow()

    private val mutableLogCount = MutableStateFlow(EvalLog.entryCount(application))
    val logCount: StateFlow<Int> = mutableLogCount.asStateFlow()

    fun saveApiKey(value: String) {
        ApiKeyStore.set(getApplication(), value)
        mutableHasKey.value = ApiKeyStore.get(getApplication<Application>()) != null
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

            mutableState.value = UiState.Resolving

            val place = try {
                reverseGeocode(fix.lat, fix.lon)
            } catch (e: LookupFailed) {
                mutableState.value = UiState.Failed(e.message ?: "Address lookup failed.")
                return@launch
            }

            mutableState.value = UiState.Retrieving

            // An article lookup failure is not fatal. Narration has web search and
            // an empty candidate set is a normal result anyway.
            var candidates = emptyList<WikiCandidate>()
            var candidatesError: String? = null
            try {
                candidates = nearbyArticles(fix.lat, fix.lon)
            } catch (e: LookupFailed) {
                candidatesError = e.message
            }
            val retrievedAt = System.currentTimeMillis()

            val retrieval = Retrieval(
                fix = fix,
                place = place,
                candidates = candidates,
                candidatesError = candidatesError,
            )

            mutableState.value = UiState.Narrating(retrieval)

            val apiKey = ApiKeyStore.get(context)
            var blurb: Blurb? = null
            var blurbError: String? = null

            if (apiKey == null) {
                blurbError = "No API key set."
            } else {
                try {
                    blurb = narrate(apiKey, retrieval)
                } catch (e: NarrationFailed) {
                    blurbError = e.message ?: "Narration failed."
                }
            }
            val narratedAt = System.currentTimeMillis()

            val latency = Latency(
                fixMs = fixedAt - startedAt,
                retrieveMs = retrievedAt - fixedAt,
                narrateMs = narratedAt - retrievedAt,
                totalMs = narratedAt - startedAt,
            )

            EvalLog.append(context, EvalLog.buildEntry(retrieval, blurb, blurbError, latency))
            mutableLogCount.value = EvalLog.entryCount(context)

            mutableState.value = UiState.Ready(
                retrieval = retrieval,
                blurb = blurb,
                blurbError = blurbError,
                rating = null,
            )
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
