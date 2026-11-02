@file:OptIn(ExperimentalMaterial3Api::class)

package de.baschek.gymapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.baschek.gymapp.R
import de.baschek.gymapp.core.model.Gym
import de.baschek.gymapp.core.model.OTHER_GYM_ID
import de.baschek.gymapp.data.Stored
import de.baschek.gymapp.ui.GymDot
import de.baschek.gymapp.ui.gymLabel

/**
 * The gym is required to start a session. The last used gym is preselected, but the
 * session only starts after an explicit tap on the confirm button naming the gym.
 */
@Composable
fun StartSessionDialog(
    gyms: List<Stored<Gym>>,
    preselected: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val options = gyms.map { it.id } + OTHER_GYM_ID
    var selected by rememberSaveable { mutableStateOf(preselected?.takeIf { it in options }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_gym_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.start_gym_message))
                options.forEach { id ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == id, role = Role.RadioButton, onClick = { selected = id }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = selected == id, onClick = null)
                        GymDot(id)
                        Text(gymLabel(id, gyms))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text(selected?.let { stringResource(R.string.start_gym_confirm, gymLabel(it, gyms)) } ?: stringResource(R.string.start_gym_choose))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
