package de.baschek.gymapp.ui.more

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.model.MuscleMapping
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.LocalSnack
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.Routes
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.ChevronIcon
import de.baschek.gymapp.ui.components.Group
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Hairline
import de.baschek.gymapp.ui.components.ListRow
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.StatusBadge
import de.baschek.gymapp.ui.gymChoices
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.tracked
import de.baschek.gymapp.ui.theme.Spacing

private const val SCREEN = "uebungen"

/** Übungen und Maschinen: search, muscle filter and the exercise list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(nav: Navigator) {
    val vm: ExercisesViewModel = viewModel(factory = appViewModelFactory { ExercisesViewModel(it) })
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val filtered = remember(catalog, query, group) {
        val q = query.trim().lowercase()
        catalog.filter { e ->
            (q.isEmpty() || e.value.name.lowercase().contains(q) || e.value.aliases.orEmpty().any { it.lowercase().contains(q) }) &&
                (group == null || group in MuscleMapping.groupsOf(e.value))
        }.sortedBy { it.value.name.lowercase() }
    }
    val withProfile = profiles.map { it.value.exerciseId }.toSet()

    ScreenList {
        item { BackBar("Übungen und Maschinen", tracked(SCREEN, "back") { nav.pop() }) }
        item { GymField(query, { query = it }, "Übung suchen") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                FilterChip(group == null, tracked(SCREEN, "filter_all") { group = null }, label = { Text("Alle") })
                MuscleGroup.ALL.forEach { g ->
                    FilterChip(group == g, tracked(SCREEN, "filter_group") { group = if (group == g) null else g }, label = { Text(MuscleGroup.label(g)) })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                GymCardSurface {
                    Text(if (catalog.isEmpty()) "Noch keine Übungen" else "Keine Übung gefunden", style = MaterialTheme.typography.headlineSmall)
                    if (catalog.isEmpty()) {
                        Text(
                            "Der Übungskatalog kommt mit der ersten Synchronisierung.", Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { ex ->
                Group {
                    ListRow(
                        ex.value.name, minHeight = 68.dp,
                        subtitle = listOfNotNull(MuscleMapping.groupsOf(ex.value).firstOrNull()?.let { MuscleGroup.label(it) }, if (ex.id in withProfile) "Maschinenprofil" else null).joinToString(" · "),
                        onClick = tracked(SCREEN, "open_exercise") { nav.push(Routes.exercise(ex.id)) },
                        leading = { Initial(ex.value.name) },
                        trailing = { ChevronIcon() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Initial(name: String, size: Int = 56) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Exercise detail: photo (device only), notes and the machine profile per gym. */
@Composable
fun ExerciseDetailScreen(exerciseId: String, nav: Navigator) {
    val vm: ExerciseDetailViewModel = viewModel(key = "ex-$exerciseId", factory = appViewModelFactory { ExerciseDetailViewModel(it, exerciseId) })
    val ex by vm.exercise.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    val context = LocalContext.current
    var notes by rememberSaveable(ex?.id) { mutableStateOf(ex?.value?.notes.orEmpty()) }
    var photoVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Telemetry.track(SCREEN + "_detail", "view") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> vm.photoFile().outputStream().use { input.copyTo(it) } }
            }.onSuccess { photoVersion++; snack("Foto hinzugefügt") }.onFailure { snack("Foto konnte nicht gespeichert werden") }
        }
    }
    val photo = remember(photoVersion, exerciseId) { vm.photoFile().takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }

    ScreenList {
        item { BackBar(ex?.value?.name ?: "Übung", tracked(SCREEN + "_detail", "back") { nav.pop() }) }
        val e = ex?.value
        if (e == null) {
            item { Text("Diese Übung wurde nicht gefunden.", style = MaterialTheme.typography.bodyLarge) }
        } else {
            item {
                GymCardSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (photo != null) {
                            Image(photo.asImageBitmap(), contentDescription = "Foto von ${e.name}", contentScale = ContentScale.Crop, modifier = Modifier.size(160.dp).clip(RoundedCornerShape(14.dp)))
                        } else {
                            Initial(e.name, 96)
                        }
                        SoftButton(
                            "Foto hinzufügen", tracked(SCREEN + "_detail", "add_photo") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            icon = GymIcons.Camera,
                        )
                    }
                }
            }
            item {
                Group {
                    ListRow("Muskelgruppe", trailing = {
                        Text(MuscleMapping.groupsOf(e).joinToString(", ") { MuscleGroup.label(it) }.ifEmpty { "–" }, style = MaterialTheme.typography.bodyLarge)
                    })
                }
            }
            item {
                GymCardSurface {
                    GymField(notes, { notes = it }, "Notiz", singleLine = false)
                    if (notes.trim() != e.notes.orEmpty()) {
                        PrimaryButton("Notiz speichern", tracked(SCREEN + "_detail", "save_notes") { vm.saveNotes(notes) }, Modifier.padding(top = Spacing.s3), height = 52.dp)
                    }
                }
            }
            item {
                Column {
                    Overline("Maschinenprofil je Studio", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    Group {
                        val choices = gymChoices(gyms)
                        choices.forEachIndexed { i, gymId ->
                            if (i > 0) Hairline()
                            val has = profiles.any { it.value.gymId == gymId && it.value.exerciseId == exerciseId && (it.value.availableWeightsKg?.isNotEmpty() == true || it.value.weightRanges?.isNotEmpty() == true) }
                            ListRow(
                                gymLabel(gymId, gyms), minHeight = 64.dp,
                                onClick = tracked(SCREEN + "_detail", "open_machine") { nav.push(Routes.machine(gymId, exerciseId)) },
                                trailing = {
                                    if (has) StatusBadge(BadgeKind.OK, GymIcons.Ok, "Vorhanden") else StatusBadge(BadgeKind.WARN, GymIcons.Warn, "Fehlt")
                                    ChevronIcon()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
