package ai.voitta.virgil

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Asked for after the first blurb, not at launch.
 *
 * Prompting on launch asks the user to decide about notifications before they
 * have seen what the app does, and a prompt that appears unbidden gets
 * dismissed at random -- observed doing exactly that in testing. Never
 * blocking: a refused notification costs the notification, not the blurb.
 */
@Composable
private fun AskForNotifications(afterFirstBlurb: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(afterFirstBlurb) {
        if (afterFirstBlurb &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifier.allowed(context)
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

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
    val missingKeys by viewModel.missingKeys.collectAsState()
    val enabledProviders by viewModel.enabledProviders.collectAsState()
    val speech by viewModel.speech.collectAsState()
    val warning by viewModel.warning.collectAsState()
    val logCount by viewModel.logCount.collectAsState()
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

    val busy = state is UiState.Locating ||
        state is UiState.Retrieving ||
        state is UiState.Narrating

    val delivered = state as? UiState.Ready
    AskForNotifications(afterFirstBlurb = delivered?.blurb != null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Virgil", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(16.dp))

        ProviderChooser(
            enabled = enabledProviders,
            onToggle = { name, on -> viewModel.setProviderEnabled(name, on) },
        )
        Spacer(Modifier.height(16.dp))

        for (vendor in missingKeys) {
            ApiKeyEntry(
                vendor = vendor,
                onSave = { value -> viewModel.saveApiKey(vendor.credential, value) },
            )
            Spacer(Modifier.height(16.dp))
        }

        val currentWarning = warning
        if (currentWarning != null) {
            Text(
                currentWarning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
        }

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

        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            is UiState.Idle -> Unit

            is UiState.Locating -> Progress("Getting a fix...")

            is UiState.Retrieving -> Progress("Looking up where that is...")

            is UiState.Narrating -> Progress("Working out what to tell you...")

            is UiState.Ready -> Result(
                ready = current,
                speech = speech,
                onStop = { viewModel.stopSpeaking() },
                onTogglePlayback = { viewModel.togglePlayback() },
                onRate = { rating -> viewModel.rate(rating) },
            )

            is UiState.Failed -> Text(
                text = current.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        if (logCount > 0) {
            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { exportLog(context) }) {
                Text("Export log ($logCount)")
            }
        }
    }
}

/**
 * The user picks the providers. Nothing is enabled by default and no key ships
 * with the app -- the waterfall is whatever they turn on, tried in the order
 * they turned it on.
 */
@Composable
private fun ProviderChooser(enabled: List<String>, onToggle: (String, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(enabled.isEmpty()) }

    val summary = if (enabled.isEmpty()) "none chosen" else enabled.joinToString(" -> ")
    Text(
        "Providers: $summary",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clickable { expanded = !expanded },
    )

    if (!expanded) {
        return
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Tried top to bottom, in the order you turn them on.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))

    for (vendor in PROVIDER_CATALOG) {
        val on = enabled.contains(vendor.name)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = on, onCheckedChange = { checked -> onToggle(vendor.name, checked) })
            Spacer(Modifier.height(0.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(vendor.name, style = MaterialTheme.typography.bodyMedium)
                val capability = if (vendor.webSearch) "web search" else "no web search"
                Text(
                    "${vendor.model}  -  $capability",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ApiKeyEntry(vendor: Vendor, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Virgil calls ${vendor.credential} with your own key.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { entered -> value = entered },
            label = { Text("${vendor.credential} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSave(value) },
            enabled = value.isNotBlank(),
        ) {
            Text("Save key")
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
private fun Result(
    ready: UiState.Ready,
    speech: SpeechState,
    onStop: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRate: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        val blurb = ready.blurb
        if (blurb != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    blurb.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
            // Shown for as long as there is a blurb, not only while it is
            // talking: after a stop, play is how you hear it again without
            // spending another location fix and another narration.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = speech != SpeechState.IDLE,
                ) {
                    Text("Stop")
                }
                OutlinedButton(onClick = onTogglePlayback) {
                    Text(
                        when (speech) {
                            SpeechState.SPEAKING -> "Pause"
                            SpeechState.PAUSED -> "Resume"
                            SpeechState.IDLE -> "Play"
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            RatingRow(rating = ready.rating, onRate = onRate)
            Spacer(Modifier.height(12.dp))
            Text(costLine(blurb), style = MaterialTheme.typography.bodySmall)
        }

        val blurbError = ready.blurbError
        if (blurbError != null) {
            Text(
                blurbError,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        WhatItUsed(ready.retrieval)
    }
}

@Composable
private fun RatingRow(rating: String?, onRate: (String) -> Unit) {
    if (rating != null) {
        Text("Rated: $rating", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in listOf("interesting", "meh", "wrong")) {
            OutlinedButton(onClick = { onRate(option) }) {
                Text(option)
            }
        }
    }
}

/**
 * Required for judging hallucination: the blurb cannot be assessed without
 * seeing what the model was actually given.
 */
@Composable
private fun WhatItUsed(retrieval: Retrieval) {
    var expanded by remember { mutableStateOf(false) }

    val label = if (expanded) "hide what it used" else "what it used (${retrieval.candidates.size})"
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clickable { expanded = !expanded },
    )

    if (!expanded) {
        return
    }

    Spacer(Modifier.height(12.dp))

    val place = retrieval.place
    val fix = retrieval.fix

    val headline = place.street ?: place.neighbourhood ?: place.city ?: "Unnamed place"
    Text(headline, style = MaterialTheme.typography.titleMedium)
    val region = listOfNotNull(place.city, place.state, place.postcode).joinToString(", ")
    if (region.isNotEmpty()) {
        Text(region, style = MaterialTheme.typography.bodyMedium)
    }
    Text(coordinateLine(fix), style = MaterialTheme.typography.bodySmall)
    if (fix.stale) {
        Text(
            "Last known location, not a fresh fix.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(12.dp))

    val candidatesError = retrieval.candidatesError
    if (candidatesError != null) {
        Text(
            candidatesError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    if (retrieval.candidates.isEmpty()) {
        Text("Nothing within 10 km.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    for (candidate in retrieval.candidates) {
        Text(
            "${candidate.title}  -  ${distanceLabel(candidate.distanceM)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        val intro = candidate.intro
        if (intro != null) {
            Text(intro.take(200), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun exportLog(context: Context) {
    val file = EvalLog.file(context)
    if (!file.exists()) {
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "application/json"
    intent.putExtra(Intent.EXTRA_STREAM, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Export evaluation log"))
}

private fun costLine(blurb: Blurb): String {
    val cost = if (blurb.costUsd != null) {
        String.format(Locale.US, "$%.4f", blurb.costUsd)
    } else {
        "cost not reported"
    }
    val search = if (blurb.webSearchAvailable) "web search on" else "NO WEB SEARCH"
    val retval = "${blurb.vendor} / ${blurb.model}  -  " +
        "${blurb.inputTokens} in / ${blurb.outputTokens} out, $search, $cost"
    return retval
}

private fun distanceLabel(metres: Double): String {
    val retval = if (metres.isNaN()) {
        "distance unknown"
    } else if (metres < 1000) {
        String.format(Locale.US, "%.0f m", metres)
    } else {
        String.format(Locale.US, "%.1f km", metres / 1000)
    }
    return retval
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
