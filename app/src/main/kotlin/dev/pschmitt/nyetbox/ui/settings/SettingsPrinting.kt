package dev.pschmitt.nyetbox.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.pschmitt.nyetbox.data.repository.PrintSettings
import dev.pschmitt.nyetbox.printing.BrotherLabelRenderer
import dev.pschmitt.nyetbox.printing.BrotherPrinter
import dev.pschmitt.nyetbox.printing.PairedPrinter

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
internal fun PrintingSettingsSection(
    settings: PrintSettings,
    onUpdate: ((PrintSettings) -> PrintSettings) -> Unit,
    onSetDefaultPrinter: (String, String) -> Unit,
    onClearDefaultPrinter: () -> Unit,
) {
    val context = LocalContext.current
    var hasBluetoothPermission by remember { mutableStateOf(canReadBluetooth(context)) }
    var pairedPrinters by remember { mutableStateOf<List<PairedPrinter>>(emptyList()) }
    var printerMenuExpanded by remember { mutableStateOf(false) }
    var qrSizeMenuExpanded by remember { mutableStateOf(false) }
    var copiesText by remember(settings.copies) { mutableStateOf(settings.copies.toString()) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasBluetoothPermission = canReadBluetooth(context)
        }

    LaunchedEffect(hasBluetoothPermission) {
        pairedPrinters =
            if (hasBluetoothPermission) {
                context
                    .getSystemService<BluetoothManager>()
                    ?.adapter
                    ?.let { BrotherPrinter.pairedPrinters(it.bondedDevices) }
                    .orEmpty()
            } else {
                emptyList()
            }
    }

    val defaultPrinterLabel =
        settings.defaultPrinterName
            ?: settings.defaultPrinterAddress
            ?: "No default printer selected"
    val previewText =
        if (settings.longLabel) {
            "Example device\nASSET-0001\nSN-EXAMPLE"
        } else {
            "ASSET-0001"
        }
    val previewBitmap =
        remember(
            settings.invertColors,
            settings.verticalText,
            settings.longLabel,
            settings.qrSize,
        ) {
            runCatching {
                    BrotherLabelRenderer.preview(
                        objectUrl = "https://netbox.example/dcim/devices/1/",
                        labelText = previewText,
                        invert = settings.invertColors,
                        vertical = settings.verticalText,
                        qrSize = settings.qrSize,
                    )
                }
                .getOrNull()
        }
    androidx.compose.runtime.DisposableEffect(previewBitmap) {
        onDispose { previewBitmap?.recycle() }
    }
    SettingsGroupCard(
        title = "Printing",
        icon = Icons.Default.Print,
    ) {
        Text(
            "Preview of the current label settings using example content. It works without a printer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (previewBitmap == null) {
            Text(
                "The label preview is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Label preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(144.dp).padding(12.dp),
            )
        }
        SettingsListItem(
            modifier = Modifier.clickable { printerMenuExpanded = true },
            leadingContent = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
            headlineContent = { Text("Default printer") },
            supportingContent = { Text(defaultPrinterLabel) },
            trailingContent = {
                Box {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                    DropdownMenu(
                        expanded = printerMenuExpanded,
                        onDismissRequest = { printerMenuExpanded = false },
                    ) {
                        pairedPrinters.forEach { printer ->
                            DropdownMenuItem(
                                text = { Text("${printer.name} (${printer.address})") },
                                leadingIcon = {
                                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                                },
                                onClick = {
                                    onSetDefaultPrinter(printer.name, printer.address)
                                    printerMenuExpanded = false
                                },
                            )
                        }
                        if (settings.defaultPrinterAddress != null) {
                            DropdownMenuItem(
                                text = { Text("Clear default printer") },
                                leadingIcon = {
                                    Icon(Icons.Default.Clear, contentDescription = null)
                                },
                                onClick = {
                                    onClearDefaultPrinter()
                                    printerMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            },
        )
        if (!hasBluetoothPermission) {
            OutlinedButton(
                onClick = { permissionLauncher.launch(settingsBluetoothPermissions()) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Allow Bluetooth to choose a printer")
            }
        } else if (pairedPrinters.isEmpty()) {
            Text(
                "Pair a Brother P-touch printer in the print dialog before choosing it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        SettingsToggleItem(
            checked = settings.invertColors,
            onCheckedChange = { value -> onUpdate { it.copy(invertColors = value) } },
            leadingContent = { Icon(Icons.Default.InvertColors, contentDescription = null) },
            headlineContent = { Text("Invert print colors") },
            supportingContent = { Text("Disable if printing on black tape") },
        )
        SettingsToggleItem(
            checked = settings.verticalText,
            onCheckedChange = { value -> onUpdate { it.copy(verticalText = value) } },
            leadingContent = { Icon(Icons.Default.TextFields, contentDescription = null) },
            headlineContent = { Text("Vertical label text") },
            supportingContent = { Text("Rotate text for narrow labels") },
        )
        SettingsToggleItem(
            checked = settings.longLabel,
            onCheckedChange = { value -> onUpdate { it.copy(longLabel = value) } },
            leadingContent = { Icon(Icons.Default.FormatSize, contentDescription = null) },
            headlineContent = { Text("Long label") },
            supportingContent = { Text("Use the extended name, asset tag, and serial layout") },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = copiesText,
                onValueChange = { value ->
                    copiesText = value.filter(Char::isDigit).take(1)
                    copiesText
                        .toIntOrNull()
                        ?.takeIf { it in 1..9 }
                        ?.let { copies -> onUpdate { it.copy(copies = copies) } }
                },
                label = { Text("Copies") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box {
                OutlinedButton(onClick = { qrSizeMenuExpanded = true }) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("QR ${settings.qrSize}px")
                }
                DropdownMenu(
                    expanded = qrSizeMenuExpanded,
                    onDismissRequest = { qrSizeMenuExpanded = false },
                ) {
                    listOf(48, 56, 64).forEach { size ->
                        DropdownMenuItem(
                            text = { Text("${size}px") },
                            leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                            onClick = {
                                onUpdate { it.copy(qrSize = size) }
                                qrSizeMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun canReadBluetooth(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

internal fun settingsBluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
