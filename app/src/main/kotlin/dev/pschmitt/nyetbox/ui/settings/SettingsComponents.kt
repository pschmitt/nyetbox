package dev.pschmitt.nyetbox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsListItem(
    modifier: Modifier = Modifier,
    leadingContent: (@Composable (() -> Unit))? = null,
    overlineContent: (@Composable (() -> Unit))? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable (() -> Unit))? = null,
    trailingContent: (@Composable (() -> Unit))? = null,
) {
    ListItem(
        modifier = modifier,
        leadingContent = leadingContent,
        overlineContent = overlineContent,
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun SettingsToggleItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable (() -> Unit))? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable (() -> Unit))? = null,
) {
    SettingsListItem(
        modifier =
            modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        leadingContent = leadingContent,
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * The header is itself a [SettingsListItem] - the same primitive [content] rows normally use - so
 * the header's icon and title always line up horizontally with whatever's below it, instead of the
 * two independently guessing at a matching inset. Deliberately understated (small label-style
 * title, no subtitle) since it's just a section label, not content in its own right.
 */
@Composable
internal fun SettingsGroupCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    headerContent: (@Composable (() -> Unit))? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SettingsListItem(
                leadingContent =
                    headerContent
                        ?: {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                headlineContent = {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            content()
        }
    }
}

@Composable
internal fun SettingsSingleItemCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}
