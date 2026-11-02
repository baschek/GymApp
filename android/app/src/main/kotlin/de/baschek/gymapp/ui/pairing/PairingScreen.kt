package de.baschek.gymapp.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.R
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.appViewModelFactory

@Composable
fun PairingScreen(onDone: () -> Unit) {
    val vm: PairingViewModel = viewModel(factory = appViewModelFactory { PairingViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { Telemetry.track("pairing", "view") }
    LaunchedEffect(state) { if (state is PairingUiState.Done) onDone() }

    val busy = state is PairingUiState.Busy
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.pairing_url)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it }, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.pairing_code)) },
            modifier = Modifier.fillMaxWidth(),
        )
        (state as? PairingUiState.Failed)?.let {
            Text(
                stringResource(
                    when (it.reason) {
                        PairingFailure.URL -> R.string.pairing_error_url
                        PairingFailure.CODE -> R.string.pairing_error_code
                        PairingFailure.UNAVAILABLE -> R.string.pairing_error_unavailable
                        PairingFailure.RATE -> R.string.pairing_error_rate
                        PairingFailure.NETWORK -> R.string.pairing_error_network
                        PairingFailure.OTHER -> R.string.pairing_error_other
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { vm.pair(url, code) }, enabled = !busy && url.isNotBlank() && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.pairing_busy else R.string.pairing_submit))
        }
        TextButton(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.common_back)) }
    }
}
