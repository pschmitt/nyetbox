package dev.pschmitt.nyetbox.ui.generic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.pschmitt.nyetbox.ui.common.NyetboxCard
import dev.pschmitt.nyetbox.ui.common.NyetboxListItem
import dev.pschmitt.nyetbox.ui.directory.AppIcons

/** NetBox component models that can be attached to a device. */
data class DeviceComponentKind(
    val label: String,
    val endpointPath: String,
    val icon: ImageVector,
)

val deviceComponentKinds =
    listOf(
        component("Interface", "api/dcim/interfaces/"),
        component("Front port", "api/dcim/front-ports/"),
        component("Rear port", "api/dcim/rear-ports/"),
        component("Console port", "api/dcim/console-ports/"),
        component("Power port", "api/dcim/power-ports/"),
        component("Power outlet", "api/dcim/power-outlets/"),
        component("Module bay", "api/dcim/module-bays/"),
        component("Device bay", "api/dcim/device-bays/"),
        component("Inventory item", "api/dcim/inventory-items/"),
    )

private fun component(label: String, endpointPath: String) =
    DeviceComponentKind(label, endpointPath, AppIcons.forEndpointPath(endpointPath))

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AddComponentScreen(
    onBack: () -> Unit,
    onComponentClick: (DeviceComponentKind) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add component") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    "Choose the component type to add to this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            items(deviceComponentKinds, key = DeviceComponentKind::endpointPath) { component ->
                NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    NyetboxListItem(
                        leadingContent = { Icon(component.icon, contentDescription = null) },
                        headlineContent = { Text(component.label) },
                        supportingContent = {
                            Text("Create a ${component.label.lowercase()} on this device")
                        },
                        modifier = Modifier.clickable { onComponentClick(component) },
                    )
                }
            }
        }
    }
}
