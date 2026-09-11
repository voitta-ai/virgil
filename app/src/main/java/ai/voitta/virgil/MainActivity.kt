package ai.voitta.virgil

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VirgilScreen()
                }
            }
        }
    }
}

@Composable
private fun VirgilScreen(viewModel: VirgilViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val anyGranted = results.values.any { granted -> granted }
        if (anyGranted) {
            viewModel.whereAmI()
        } else {
            viewModel.permissionDenied()
        }
    }

    val busy = state is UiState.Locating || state is UiState.Resolving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Virgil", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                if (hasLocationPermission(context)) {
                    viewModel.whereAmI()
                } else {
                    permissionLauncher.launch(LOCATION_PERMISSIONS)
                }
            },
            enabled = !busy,
        ) {
            Text("Where am I?")
        }

        Spacer(Modifier.height(32.dp))

        when (val current = state) {
            is UiState.Idle -> Unit

            is UiState.Locating -> Progress("Getting a fix...")

            is UiState.Resolving -> Progress("Looking up the address...")

            is UiState.Ready -> Located(current)

            is UiState.Failed -> Text(
                text = current.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Progress(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Located(ready: UiState.Ready) {
    val place = ready.place
    val fix = ready.fix

    Column(modifier = Modifier.fillMaxWidth()) {
        val headline = place.street ?: place.neighbourhood ?: place.city ?: "Unnamed place"
        Text(headline, style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(4.dp))

        val region = listOfNotNull(place.city, place.state, place.postcode).joinToString(", ")
        if (region.isNotEmpty()) {
            Text(region, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(16.dp))

        Text(coordinateLine(fix), style = MaterialTheme.typography.bodySmall)

        if (fix.stale) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Last known location, not a fresh fix.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (place.displayName.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(place.displayName, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun coordinateLine(fix: Fix): String {
    val retval = String.format(
        Locale.US,
        "%.5f, %.5f  +/- %.0f m",
        fix.lat,
        fix.lon,
        fix.accuracyM,
    )
    return retval
}

private fun hasLocationPermission(context: Context): Boolean {
    val retval = LOCATION_PERMISSIONS.any { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    return retval
}
