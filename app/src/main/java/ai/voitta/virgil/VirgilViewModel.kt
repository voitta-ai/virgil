package ai.voitta.virgil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Locating : UiState
    data object Resolving : UiState
    data class Ready(val fix: Fix, val place: Place) : UiState
    data class Failed(val message: String) : UiState
}

class VirgilViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    fun whereAmI() {
        viewModelScope.launch {
            mutableState.value = UiState.Locating

            val fix = try {
                currentFix(getApplication<Application>())
            } catch (e: SecurityException) {
                mutableState.value = UiState.Failed("Location permission was revoked.")
                return@launch
            }

            if (fix == null) {
                mutableState.value =
                    UiState.Failed("No location fix. Check that location is turned on.")
                return@launch
            }

            mutableState.value = UiState.Resolving

            try {
                val place = reverseGeocode(fix.lat, fix.lon)
                mutableState.value = UiState.Ready(fix, place)
            } catch (e: LookupFailed) {
                mutableState.value = UiState.Failed(e.message ?: "Address lookup failed.")
            }
        }
    }

    fun permissionDenied() {
        mutableState.value = UiState.Failed(
            "Virgil needs your location to tell you about it. " +
                "Grant location access in Settings."
        )
    }
}
