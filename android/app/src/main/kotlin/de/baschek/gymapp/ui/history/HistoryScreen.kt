@file:OptIn(ExperimentalMaterial3Api::class)

package de.baschek.gymapp.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.OTHER_GYM_ID
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.GymDot
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.gymLabel

/** Placeholder history: completed sessions with gym, "zu prüfen" badge and gym reassignment. */
@Composable
fun HistoryScreen(onClose: () -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = appViewModelFactory { HistoryViewModel(it) })
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingGym by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { Telemetry.track("history", "view") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.common_back)) }
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (sessions.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
        items(sessions, key = { it.id }) { stored ->
            val s = stored.value
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GymDot(s.gymId)
                        Column(Modifier.weight(1f)) {
                            Text(s.planName, style = MaterialTheme.typography.titleSmall)
                            Text("${s.effectiveTime.take(10)} · ${gymLabel(s.gymId, gyms)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!s.reviewFlags.isNullOrEmpty()) {
                            AssistChip(onClick = { }, label = { Text(stringResource(R.string.history_badge_review)) })
                        }
                        TextButton(onClick = {
                            openId = if (openId == stored.id) null else stored.id
                            pendingGym = null
                        }) { Text(stringResource(R.string.history_details)) }
                    }
                    if (openId == stored.id) {
                        Text(stringResource(R.string.history_change_gym), style = MaterialTheme.typography.labelLarge)
                        val options = gyms.map { it.id } + OTHER_GYM_ID
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { id ->
                                FilterChip(
                                    selected = (pendingGym ?: s.gymId) == id, onClick = { pendingGym = id },
                                    label = { Text(gymLabel(id, gyms)) },
                                )
                            }
                        }
                        val target = pendingGym
                        if (target != null && target != s.gymId) {
                            Text(stringResource(R.string.history_recalc_hint), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { vm.changeGym(stored.id, target); pendingGym = null }) {
                                Text(stringResource(R.string.history_change_gym_confirm, gymLabel(target, gyms)))
                            }
                        }
                        if (!s.reviewFlags.isNullOrEmpty()) {
                            OutlinedButton(onClick = { vm.markReviewed(stored.id) }) { Text(stringResource(R.string.history_mark_reviewed)) }
                        }
                    }
                }
            }
        }
    }
}
