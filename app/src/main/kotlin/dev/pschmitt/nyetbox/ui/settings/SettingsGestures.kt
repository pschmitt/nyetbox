package dev.pschmitt.nyetbox.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureShortcut
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.ui.common.iconForGestureAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun GestureShortcutRow(
    shortcut: GestureShortcut,
    action: GestureAction,
    target: GestureTarget?,
    models: List<dev.pschmitt.nyetbox.data.db.NetBoxModelEntity>,
    objectChoices: (endpointPath: String, query: String) -> Flow<List<NetBoxObjectEntity>>,
    onActionSelected: (GestureAction) -> Unit,
    onTargetSelected: (dev.pschmitt.nyetbox.data.db.NetBoxModelEntity) -> Unit,
    onDetailTargetSelected: (NetBoxObjectEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var targetPickerVisible by remember { mutableStateOf(false) }
    val actionLabel =
        target?.let { configured -> "${action.label}: ${configured.label}" } ?: action.label
    SettingsListItem(
        modifier = Modifier.clickable { expanded = true },
        leadingContent = {
            Icon(
                when {
                    shortcut.label.contains("down", ignoreCase = true) ->
                        Icons.Default.KeyboardArrowDown
                    shortcut.label.contains("up", ignoreCase = true) ->
                        Icons.Default.KeyboardArrowUp
                    shortcut.label.contains("left", ignoreCase = true) ->
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    shortcut.label.contains("right", ignoreCase = true) ->
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    else -> Icons.Default.TouchApp
                },
                contentDescription = null,
            )
        },
        headlineContent = { Text(shortcut.label) },
        supportingContent = { Text(actionLabel) },
        trailingContent = {
            Box {
                Icon(Icons.Default.ExpandMore, contentDescription = null)
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    GestureAction.entries.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.label) },
                            leadingIcon = {
                                Icon(iconForGestureAction(candidate), contentDescription = null)
                            },
                            onClick = {
                                onActionSelected(candidate)
                                expanded = false
                                if (
                                    candidate == GestureAction.AddSpecific ||
                                        candidate == GestureAction.ListSpecific ||
                                        candidate == GestureAction.DetailSpecific
                                ) {
                                    targetPickerVisible = true
                                }
                            },
                        )
                    }
                }
            }
        },
    )
    if (targetPickerVisible) {
        ActionTargetPickerDialog(
            action = action,
            models = models,
            objectChoices = objectChoices,
            onDismiss = { targetPickerVisible = false },
            onModelSelected = { model ->
                onTargetSelected(model)
                targetPickerVisible = false
            },
            onObjectSelected = { obj ->
                onDetailTargetSelected(obj)
                targetPickerVisible = false
            },
        )
    }
}

/**
 * Two-step target picker shared by the gesture-shortcut editor and the nav-bar customizer: choose
 * an item type, then (for [GestureAction.DetailSpecific] only) a specific cached instance of that
 * type. [onModelSelected] fires for the terminal choice of every other action; [onObjectSelected]
 * fires only once a specific instance has been picked.
 */
@Composable
internal fun ActionTargetPickerDialog(
    action: GestureAction,
    models: List<NetBoxModelEntity>,
    objectChoices: (endpointPath: String, query: String) -> Flow<List<NetBoxObjectEntity>>,
    onDismiss: () -> Unit,
    onModelSelected: (NetBoxModelEntity) -> Unit,
    onObjectSelected: (NetBoxObjectEntity) -> Unit,
) {
    var targetQuery by remember { mutableStateOf("") }
    var detailModel by remember { mutableStateOf<NetBoxModelEntity?>(null) }
    val filteredModels = models.filter { model ->
        targetQuery.isBlank() ||
            model.modelLabel.contains(targetQuery, ignoreCase = true) ||
            model.appLabel.contains(targetQuery, ignoreCase = true)
    }
    val filteredObjects by
        remember(detailModel?.endpointPath, targetQuery) {
                detailModel?.let { objectChoices(it.endpointPath, targetQuery) }
                    ?: flowOf(emptyList())
            }
            .collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (action == GestureAction.DetailSpecific && detailModel != null) {
                    "Choose cached ${detailModel!!.modelLabel.lowercase()}"
                } else {
                    "Choose item type"
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = targetQuery,
                    onValueChange = { targetQuery = it },
                    label = {
                        Text(
                            if (action == GestureAction.DetailSpecific && detailModel != null) {
                                "Search cached items"
                            } else {
                                "Search item types"
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (action == GestureAction.DetailSpecific && detailModel != null) {
                    if (filteredObjects.isEmpty()) {
                        Text(
                            "No matching cached items",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(filteredObjects, key = { it.id }) { obj ->
                                SettingsListItem(
                                    modifier = Modifier.clickable { onObjectSelected(obj) },
                                    leadingContent = {
                                        Icon(Icons.Default.Storage, contentDescription = null)
                                    },
                                    headlineContent = { Text(obj.display) },
                                    supportingContent = { obj.secondaryLine?.let { Text(it) } },
                                )
                            }
                        }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        filteredModels.forEach { model ->
                            SettingsListItem(
                                modifier =
                                    Modifier.clickable {
                                        if (action == GestureAction.DetailSpecific) {
                                            detailModel = model
                                            targetQuery = ""
                                        } else {
                                            onModelSelected(model)
                                        }
                                    },
                                leadingContent = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                },
                                headlineContent = { Text(model.modelLabel) },
                                supportingContent = { Text(model.appLabel) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
