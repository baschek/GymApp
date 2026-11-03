package de.baschek.gymapp.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.gymapp.core.model.MAX_ADDONS
import de.baschek.gymapp.core.model.RangeSpec
import de.baschek.gymapp.core.model.SetupParameter
import de.baschek.gymapp.core.model.WeightRange
import de.baschek.gymapp.core.model.rangeSpec
import de.baschek.gymapp.core.model.effectiveAddons
import de.baschek.gymapp.telemetry.Telemetry
import de.baschek.gymapp.ui.LocalSnack
import de.baschek.gymapp.ui.Navigator
import de.baschek.gymapp.ui.appViewModelFactory
import de.baschek.gymapp.ui.components.BackBar
import de.baschek.gymapp.ui.components.BadgeKind
import de.baschek.gymapp.ui.components.Banner
import de.baschek.gymapp.ui.components.GhostIconButton
import de.baschek.gymapp.ui.components.GymCardSurface
import de.baschek.gymapp.ui.components.GymField
import de.baschek.gymapp.ui.components.GymIcons
import de.baschek.gymapp.ui.components.Overline
import de.baschek.gymapp.ui.components.PinnedBottom
import de.baschek.gymapp.ui.components.PrimaryButton
import de.baschek.gymapp.ui.components.ScreenList
import de.baschek.gymapp.ui.components.SoftButton
import de.baschek.gymapp.ui.components.StepRow
import de.baschek.gymapp.ui.fmtNum
import de.baschek.gymapp.ui.gymLabel
import de.baschek.gymapp.ui.theme.Spacing
import de.baschek.gymapp.ui.tracked
import java.util.UUID

private const val SCREEN = "maschine"

private class ParamDraft(val id: String, name: String, value: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
}

/** Machine profile of one exercise in one gym: levels as range + step, add-on weights (0 to 2), setup values, brand and model. */
@Composable
fun MachineScreen(gymId: String, exerciseId: String, nav: Navigator) {
    val vm: MachineViewModel = viewModel(key = "m-$gymId-$exerciseId", factory = appViewModelFactory { MachineViewModel(it, gymId, exerciseId) })
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val gyms by vm.gyms.collectAsStateWithLifecycle()
    val exercise by vm.exercise.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    LaunchedEffect(Unit) { Telemetry.track(SCREEN, "view") }

    val existing = profiles.firstOrNull { it.value.gymId == gymId && it.value.exerciseId == exerciseId }?.value
    val initialRange = existing?.rangeSpec()
    val hasLevels = initialRange != null

    // Draft values start from the stored profile (re-created only when the profile first appears).
    val key = existing != null
    var rangeDirty by remember(key) { mutableStateOf(false) }
    var lo by remember(key) { mutableStateOf(initialRange?.minKg ?: 5.0) }
    var hi by remember(key) { mutableStateOf(initialRange?.maxKg ?: 100.0) }
    var step by remember(key) { mutableStateOf(initialRange?.stepKg ?: 5.0) }
    val addons = remember(key) { (existing?.effectiveAddons().orEmpty()).toMutableStateList() }
    val params = remember(key) { (existing?.setupParameters.orEmpty().map { ParamDraft(it.id, it.name, it.value) }).toMutableStateList() }
    var brand by remember(key) { mutableStateOf(existing?.machineBrand.orEmpty()) }
    var model by remember(key) { mutableStateOf(existing?.machineModel.orEmpty()) }
    val spec = RangeSpec(lo, hi, step)

    Box(Modifier.fillMaxSize()) {
        ScreenList(bottomBar = false, pinnedHeight = 100.dp) {
            item { BackBar("Maschine bearbeiten", tracked(SCREEN, "back") { nav.pop() }) }
            item {
                Text(
                    "${exercise?.value?.name ?: "Übung"} im Studio „${gymLabel(gymId, gyms)}“. Gilt nur in diesem Studio.",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!hasLevels && !rangeDirty) {
                item {
                    Banner(BadgeKind.WARN, GymIcons.Warn, "Profil fehlt", text = "Lege Gewichtsstufen an, dann schlägt die App nur Gewichte vor, die es wirklich gibt.")
                }
            }
            item {
                GymCardSurface {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Overline("Gewichtsstufen")
                        Text("${spec.count} Stufen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StepRow(
                        "Von (kg)", fmtNum(lo),
                        { lo = (lo - step).coerceAtLeast(0.0); rangeDirty = true },
                        { lo = (lo + step).coerceAtMost(hi - step).coerceAtLeast(0.0); rangeDirty = true },
                    )
                    StepRow("Bis (kg)", fmtNum(hi), { hi = (hi - step).coerceAtLeast(lo + step); rangeDirty = true }, { hi += step; rangeDirty = true })
                    val delta = if (step >= 5) 5.0 else 0.5
                    StepRow("Schritt (kg)", fmtNum(step), { step = (step - delta).coerceAtLeast(0.5); rangeDirty = true }, { step += delta; rangeDirty = true })
                }
            }
            item {
                GymCardSurface {
                    Overline("Zusatzgewichte (höchstens $MAX_ADDONS)")
                    addons.forEachIndexed { i, a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            StepRow(
                                "+ Zusatz ${i + 1} (kg)", fmtNum(a), { addons[i] = (a - 0.25).coerceAtLeast(0.25) }, { addons[i] = a + 0.25 },
                                Modifier.weight(1f),
                            )
                            GhostIconButton(GymIcons.Close, "Zusatzgewicht ${i + 1} entfernen", tracked(SCREEN, "remove_addon") { addons.removeAt(i) })
                        }
                    }
                    if (addons.size < MAX_ADDONS) {
                        SoftButton(
                            "Zusatzgewicht", tracked(SCREEN, "add_addon") { addons.add(if (1.25 in addons) 2.5 else 1.25) },
                            Modifier.padding(top = Spacing.s2), icon = GymIcons.Plus,
                        )
                    }
                }
            }
            item {
                GymCardSurface {
                    Overline("Einstellung am Gerät")
                    params.forEachIndexed { i, p ->
                        Row(Modifier.fillMaxWidth().padding(top = Spacing.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                            Box(Modifier.weight(1f)) { GymField(p.name, { p.name = it }, "Name") }
                            Box(Modifier.width(96.dp)) { GymField(p.value, { p.value = it }, "Wert") }
                            GhostIconButton(GymIcons.Close, "Parameter ${p.name} entfernen", tracked(SCREEN, "remove_param") { params.removeAt(i) })
                        }
                    }
                    SoftButton("Parameter", tracked(SCREEN, "add_param") { params.add(ParamDraft(UUID.randomUUID().toString(), "", "")) }, Modifier.padding(top = Spacing.s2), icon = GymIcons.Plus)
                    Box(Modifier.padding(top = Spacing.s3)) { GymField(brand, { brand = it }, "Hersteller") }
                    Box(Modifier.padding(top = Spacing.s2)) { GymField(model, { model = it }, "Modell") }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            PinnedBottom {
                PrimaryButton(
                    "Maschine speichern",
                    tracked(SCREEN, "save") {
                        vm.save({ mp ->
                            val rangeId = mp.weightRanges?.firstOrNull()?.id ?: UUID.randomUUID().toString()
                            mp.copy(
                                weightRanges = if (rangeDirty) listOf(WeightRange(rangeId, lo, hi, step)) else mp.weightRanges,
                                availableWeightsKg = if (rangeDirty) null else mp.availableWeightsKg,
                                addonWeightsKg = addons.toList().takeIf { it.isNotEmpty() },
                                setupParameters = params.filter { it.name.isNotBlank() }.map { SetupParameter(it.id, it.name.trim(), it.value.trim()) }.takeIf { it.isNotEmpty() },
                                machineBrand = brand.trim().ifEmpty { null },
                                machineModel = model.trim().ifEmpty { null },
                            )
                        }) {
                            snack("Gespeichert, wird synchronisiert")
                            nav.pop()
                        }
                    },
                    height = Spacing.TouchPrimary,
                    enabled = rangeDirty || hasLevels || addons.isNotEmpty() || params.isNotEmpty() || brand.isNotBlank() || model.isNotBlank(),
                )
            }
        }
    }
}
