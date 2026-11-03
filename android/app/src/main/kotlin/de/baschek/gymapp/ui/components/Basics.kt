package de.baschek.gymapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDecoration
import de.baschek.gymapp.ui.theme.GymTextStyles
import de.baschek.gymapp.ui.theme.Spacing

/** Small upper-case caption above a block ("GEWICHT"). */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier, style = GymTextStyles.overline, color = color)
}

/** Rounded surface for a block of content; clickable when [onClick] is given. */
@Composable
fun GymCardSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = null,
    padding: PaddingValues = PaddingValues(Spacing.s4),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Spacing.RadiusCard)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

/** A list container: rounded card whose rows are separated by [Hairline]s. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Spacing.RadiusCard), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(content = content)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A row of a [Group]: title, optional subtitle, leading and trailing slots. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = Spacing.TouchMin,
    leading: (@Composable () -> Unit)? = null,
    titleSuffix: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    strike: Boolean = false,
) {
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().then(click).heightIn(min = minHeight).padding(horizontal = Spacing.s4, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                Text(
                    title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (strike) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleSuffix?.invoke()
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun ChevronIcon() {
    Icon(GymIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
}

@Composable
fun TickIcon() {
    Icon(GymIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
}

/** The one primary action of a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 60.dp,
) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        enabled = enabled,
        shape = RoundedCornerShape(Spacing.RadiusCard),
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.primary, contentColor = cs.onPrimary,
            disabledContainerColor = cs.primary.copy(alpha = 0.35f), disabledContentColor = cs.onPrimary.copy(alpha = 0.7f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp))
    }
}

/** Secondary button on a raised surface. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = Spacing.Touch,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = Spacing.s4),
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.5f), disabledContentColor = content.copy(alpha = 0.5f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Text link; [height] is 48 dp outside training and 56 dp in training. */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = Spacing.Touch,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick, modifier = modifier.heightIn(min = height), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = color.copy(alpha = 0.4f)),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** Round icon button (steppers, wheel neighbours). Always has a TalkBack label. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Spacing.TouchMin,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.4f), disabledContentColor = content.copy(alpha = 0.35f),
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}

/** Plain icon button without a background (rows, bars). */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = Spacing.Touch,
) {
    IconButton(onClick = onClick, modifier = modifier.size(size), enabled = enabled) {
        Icon(
            icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
        )
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Checkbox row (56 dp): the whole row toggles; the box uses the accent when checked. */
@Composable
fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.TouchMin)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                .background(if (checked) cs.primary else Color.Transparent)
                .then(if (checked) Modifier else Modifier.border(BorderStroke(1.5.dp, cs.onSurfaceVariant), RoundedCornerShape(8.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(GymIcons.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
    }
}

@Composable
fun GymSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        colors = SwitchDefaults.colors(
            checkedThumbColor = cs.onPrimary, checkedTrackColor = cs.primary, checkedBorderColor = cs.primary,
            uncheckedThumbColor = cs.onSurfaceVariant, uncheckedTrackColor = cs.surfaceContainerHigh, uncheckedBorderColor = cs.onSurfaceVariant,
        ),
    )
}

/** Filled text field in the card style. */
@Composable
fun GymField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    TextField(
        value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth(), enabled = enabled, singleLine = singleLine,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        shape = RoundedCornerShape(Spacing.RadiusMd),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
            disabledContainerColor = cs.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            focusedTextColor = cs.onSurface, unfocusedTextColor = cs.onSurface,
        ),
    )
}

/** Stepper row: label, − value +. 48 dp buttons, labelled for TalkBack. */
@Composable
fun StepRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    minusDescription: String = "$label verringern",
    plusDescription: String = "$label erhöhen",
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RoundIconButton(GymIcons.Minus, minusDescription, onMinus, size = Spacing.Touch, enabled = minusEnabled)
        Text(
            value, Modifier.widthIn(min = 64.dp).semantics { contentDescription = "$label: $value" },
            style = GymTextStyles.number.copy(fontSize = 17.sp), textAlign = TextAlign.Center,
        )
        RoundIconButton(GymIcons.Plus, plusDescription, onPlus, size = Spacing.Touch, enabled = plusEnabled)
    }
}

/** Hides a decorative composable from TalkBack. */
fun Modifier.decorative(): Modifier = clearAndSetSemantics { }
