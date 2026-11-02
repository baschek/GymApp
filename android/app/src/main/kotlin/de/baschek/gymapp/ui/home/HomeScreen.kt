@file:OptIn(ExperimentalMaterial3Api::class)

package de.baschek.gymapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.BuildConfig
import de.baschek.gymapp.R
import de.baschek.gymapp.data.Profile
import de.baschek.gymapp.sync.FailureKind
import de.baschek.gymapp.sync.SyncState
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.appViewModelFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Placeholder home screen: sync status, plans, update. All logic lives in [HomeViewModel]. */
@Composable
fun HomeScreen(onOpenPairing: () -> Unit, onOpenHistory: () -> Unit, onOpenWorkout: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = appViewModelFactory { HomeViewModel(it) })
    val profile by vm.profile.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val lastSync by vm.lastSyncAt.collectAsStateWithLifecycle()
    val authError by vm.authError.collectAsStateWithLifecycle()
    val notices by vm.notices.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val currentGym by vm.currentGym.collectAsStateWithLifecycle()
    val active by vm.activeSession.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val isTest = profile == Profile.TEST
    var startPlanId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { Telemetry.track("home", "view") }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !isTest, onClick = { vm.setProfile(Profile.PERSONAL) }, label = { Text(stringResource(R.string.home_profile_personal)) })
                FilterChip(selected = isTest, onClick = { vm.setProfile(Profile.TEST) }, label = { Text(stringResource(R.string.home_profile_test)) })
            }
        }
        if (isTest) {
            item { Text(stringResource(R.string.home_test_profile_banner), color = MaterialTheme.colorScheme.tertiary) }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_sync_title), style = MaterialTheme.typography.titleMedium)
                    if (!paired) {
                        Text(stringResource(R.string.home_sync_not_paired))
                        Button(onClick = onOpenPairing) { Text(stringResource(R.string.home_sync_pair)) }
                    } else {
                        Text(
                            lastSync?.let { stringResource(R.string.home_sync_last, formatTime(it)) }
                                ?: stringResource(R.string.home_sync_never),
                        )
                        if (syncState is SyncState.Running) Text(stringResource(R.string.home_sync_running))
                        (syncState as? SyncState.Failed)?.let { failed ->
                            Text(
                                stringResource(
                                    when (failed.kind) {
                                        FailureKind.UNAUTHORIZED -> R.string.home_sync_error_unauthorized
                                        FailureKind.NETWORK -> R.string.home_sync_error_network
                                        else -> R.string.home_sync_error_server
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (authError && syncState !is SyncState.Failed) {
                            Text(stringResource(R.string.home_sync_error_unauthorized), color = MaterialTheme.colorScheme.error)
                        }
                        val conflicts = notices.count { it.startsWith("conflict") }
                        val rejected = notices.count { it.startsWith("rejected") }
                        if (conflicts > 0) Text(stringResource(R.string.home_sync_notice_conflict, conflicts))
                        if (rejected > 0) Text(stringResource(R.string.home_sync_notice_rejected, rejected))
                        if (isTest) Text(stringResource(R.string.home_sync_test_disabled))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::syncNow, enabled = syncState !is SyncState.Running) {
                                Text(stringResource(R.string.home_sync_now))
                            }
                            TextButton(onClick = vm::unpair) { Text(stringResource(R.string.home_sync_unpair)) }
                        }
                    }
                }
            }
        }

        active?.let { session ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.home_active_session, session.value.planName), Modifier.weight(1f))
                        Button(onClick = { onOpenWorkout(session.id) }) { Text(stringResource(R.string.home_resume)) }
                    }
                }
            }
        }

        item { OutlinedButton(onClick = onOpenHistory) { Text(stringResource(R.string.home_history)) } }
        item { Text(stringResource(R.string.home_plans), style = MaterialTheme.typography.titleMedium) }
        if (plans.isEmpty()) {
            item { Text(stringResource(R.string.home_no_plans)) }
            if (isTest) item { OutlinedButton(onClick = vm::seedDemo) { Text(stringResource(R.string.home_seed_demo)) } }
        }
        items(plans, key = { it.id }) { plan ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(plan.value.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { startPlanId = plan.id }) {
                        Text(stringResource(R.string.home_start_session))
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_update_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_update_current, BuildConfig.VERSION_NAME))
                    when (val u = update) {
                        UpdateUi.Idle -> Unit
                        UpdateUi.Checking -> Text(stringResource(R.string.home_update_checking))
                        UpdateUi.UpToDate -> Text(stringResource(R.string.home_update_none))
                        UpdateUi.Downloading -> Text(stringResource(R.string.home_update_downloading))
                        is UpdateUi.NeedsPermission -> Text(stringResource(R.string.home_update_permission))
                        UpdateUi.Error -> Text(stringResource(R.string.home_update_error), color = MaterialTheme.colorScheme.error)
                        is UpdateUi.Available -> Text(stringResource(R.string.home_update_available, u.info.versionName))
                    }
                    val info = (update as? UpdateUi.Available)?.info ?: (update as? UpdateUi.NeedsPermission)?.info
                    if (info != null) {
                        Button(onClick = { vm.installUpdate(info) }) { Text(stringResource(R.string.home_update_download)) }
                    } else {
                        OutlinedButton(onClick = vm::checkUpdate, enabled = update != UpdateUi.Checking && update != UpdateUi.Downloading) {
                            Text(stringResource(R.string.home_update_check))
                        }
                    }
                }
            }
        }
    }
    startPlanId?.let { planId ->
        StartSessionDialog(
            gyms = gyms,
            preselected = currentGym,
            onDismiss = { startPlanId = null },
            onConfirm = { gymId ->
                startPlanId = null
                vm.startSession(planId, gymId, onOpenWorkout)
            },
        )
    }
}

private fun formatTime(instant: java.time.Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)
