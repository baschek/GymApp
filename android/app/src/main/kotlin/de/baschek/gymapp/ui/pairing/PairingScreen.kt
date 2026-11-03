package de.baschek.gymapp.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked

private const val SCREEN = "koppeln"

/** Pairing with the sync server: address and one-time code. */
@Composable
fun PairingScreen(nav: Navigator) {
    val vm: PairingViewModel = viewModel(factory = appViewModelFactory { PairingViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }
    LaunchedEffect(state) { if (state is PairingUiState.Done) nav.pop() }

    val busy = state is PairingUiState.Busy
    ScreenList {
        item { BackBar("Mit Server verbinden", tracked(SCREEN, "back") { nav.pop() }) }
        item {
            GymCardSurface {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.s3)) {
                    GymField(url, { url = it }, "Server-Adresse (https://…)", keyboardType = KeyboardType.Uri, enabled = !busy)
                    GymField(code, { code = it }, "Kopplungscode", enabled = !busy)
                    (state as? PairingUiState.Failed)?.let {
                        Text(
                            when (it.reason) {
                                PairingFailure.URL -> "Ungültige Adresse. Es ist nur HTTPS erlaubt."
                                PairingFailure.CODE -> "Der Code ist falsch oder abgelaufen."
                                PairingFailure.UNAVAILABLE -> "Koppeln ist gerade nicht möglich. Erzeuge am Server einen neuen Kopplungscode; ist noch ein altes Gerät aktiv, muss es dort zuerst getrennt werden."
                                PairingFailure.RATE -> "Zu viele Versuche. Bitte später erneut versuchen."
                                PairingFailure.NETWORK -> "Server nicht erreichbar."
                                PairingFailure.OTHER -> "Verbindung fehlgeschlagen."
                            },
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    PrimaryButton(
                        if (busy) "Verbinde …" else "Verbinden", tracked(SCREEN, "pair") { vm.pair(url, code) },
                        enabled = !busy && url.isNotBlank() && code.isNotBlank(), height = 56.dp,
                    )
                }
            }
        }
    }
}
