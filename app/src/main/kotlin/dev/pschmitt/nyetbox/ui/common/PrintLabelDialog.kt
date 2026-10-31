package dev.pschmitt.nyetbox.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.printing.BrotherLabelRenderer
import dev.pschmitt.nyetbox.printing.BrotherPrinter
import dev.pschmitt.nyetbox.printing.NearbyPrinter
import dev.pschmitt.nyetbox.printing.PairedPrinter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PrintLabelRequest(
    val objectUrl: String,
    val labelText: String,
    val longLabelText: String? = null,
)

private data class PrinterOption(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val pairedPrinter: PairedPrinter?,
)

// Bluetooth calls are all reached after hasBluetoothPermission is true; lint cannot follow the
// Compose callback and the permission result through the local reload/pairing lambdas.
@SuppressLint("MissingPermission")
@Composable
fun PrintLabelDialog(
    request: PrintLabelRequest,
    onDismiss: () -> Unit,
    settingsViewModel: PrintSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedPrintSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }
    var bluetoothEnabled by remember {
        mutableStateOf(bluetoothAdapter(context)?.isEnabled == true)
    }
    var printers by remember { mutableStateOf<List<PairedPrinter>>(emptyList()) }
    var nearbyPrinters by remember { mutableStateOf<List<NearbyPrinter>>(emptyList()) }
    var selected by remember { mutableStateOf<PairedPrinter?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }
    var pairingAddress by remember { mutableStateOf<String?>(null) }
    var printState by remember { mutableStateOf<PrintOperationState>(PrintOperationState.Idle) }
    var invertColors by
        remember(savedPrintSettings.invertColors) {
            mutableStateOf(savedPrintSettings.invertColors)
        }
    var verticalText by
        remember(savedPrintSettings.verticalText) {
            mutableStateOf(savedPrintSettings.verticalText)
        }
    var longLabel by
        remember(savedPrintSettings.longLabel) { mutableStateOf(savedPrintSettings.longLabel) }
    var copiesText by
        remember(savedPrintSettings.copies) { mutableStateOf(savedPrintSettings.copies.toString()) }
    var qrSize by
        remember(savedPrintSettings.qrSize) { mutableIntStateOf(savedPrintSettings.qrSize) }
    var qrSizeMenuExpanded by remember { mutableStateOf(false) }
    val pairedAddresses = printers.mapTo(mutableSetOf()) { it.address }
    val printerOptions = buildList {
        printers.forEach { printer ->
            add(
                PrinterOption(
                    name = printer.name,
                    address = printer.address,
                    device = printer.device,
                    pairedPrinter = printer,
                )
            )
        }
        nearbyPrinters
            .filterNot { it.address in pairedAddresses }
            .forEach { printer ->
                add(
                    PrinterOption(
                        name = printer.name,
                        address = printer.address,
                        device = printer.device,
                        pairedPrinter = null,
                    )
                )
            }
    }
    val copyCount = copiesText.toIntOrNull()?.takeIf { it in 1..9 }
    val previewText =
        if (longLabel) request.longLabelText ?: request.labelText else request.labelText
    val previewBitmap =
        remember(request.objectUrl, previewText, invertColors, verticalText, qrSize) {
            runCatching {
                    BrotherLabelRenderer.preview(
                        request.objectUrl,
                        previewText,
                        invert = invertColors,
                        vertical = verticalText,
                        qrSize = qrSize,
                    )
                }
                .getOrNull()
        }
    DisposableEffect(previewBitmap) { onDispose { previewBitmap?.recycle() } }
    val selectedPrinterIsVisible =
        selected?.let { current -> nearbyPrinters.any { it.address == current.address } } == true
    val selectedPrinterNotVisible =
        hasPermission &&
            bluetoothEnabled &&
            selected != null &&
            printers.isNotEmpty() &&
            !isDiscovering &&
            !selectedPrinterIsVisible
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasPermission = hasBluetoothPermission(context)
        }

    fun selectPrinter(printer: PairedPrinter) {
        selected = printer
        settingsViewModel.setDefaultPrinter(printer.name, printer.address)
    }

    fun reloadPrinters() {
        if (!hasPermission) return
        val adapter = bluetoothAdapter(context)
        bluetoothEnabled = adapter?.isEnabled == true
        if (!bluetoothEnabled) {
            printers = emptyList()
            nearbyPrinters = emptyList()
            isDiscovering = false
            return
        }
        printers = adapter?.let { BrotherPrinter.pairedPrinters(it.bondedDevices) }.orEmpty()
        selected =
            printers.firstOrNull { it.address == savedPrintSettings.defaultPrinterAddress }
                ?: selected?.takeIf { current -> printers.any { it.address == current.address } }
                ?: printers.firstOrNull()
        nearbyPrinters = emptyList()
        isDiscovering =
            adapter?.let {
                runCatching {
                        if (it.isDiscovering) it.cancelDiscovery()
                        it.startDiscovery()
                    }
                    .getOrDefault(false)
            } ?: false
    }

    DisposableEffect(context, hasPermission) {
        if (!hasPermission) {
            onDispose {}
        } else {
            val receiver =
                object : BroadcastReceiver() {
                    @SuppressLint("MissingPermission")
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device =
                                    IntentCompat.getParcelableExtra(
                                        intent,
                                        BluetoothDevice.EXTRA_DEVICE,
                                        BluetoothDevice::class.java,
                                    )
                                val printer = device?.let(BrotherPrinter::nearbyPrinter) ?: return
                                nearbyPrinters =
                                    (nearbyPrinters.filterNot { it.address == printer.address } +
                                            printer)
                                        .sortedBy { it.name.lowercase() }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> isDiscovering = false
                            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                                bluetoothEnabled = bluetoothAdapter(context)?.isEnabled == true
                                if (bluetoothEnabled) reloadPrinters()
                            }
                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                val device =
                                    IntentCompat.getParcelableExtra(
                                        intent,
                                        BluetoothDevice.EXTRA_DEVICE,
                                        BluetoothDevice::class.java,
                                    )
                                when (device?.bondState) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        pairingAddress = null
                                        val paired =
                                            BrotherPrinter.pairedPrinters(setOf(device))
                                                .firstOrNull()
                                        if (paired != null) {
                                            printers =
                                                (printers.filterNot {
                                                        it.address == paired.address
                                                    } + paired)
                                                    .sortedBy { it.name.lowercase() }
                                            selectPrinter(paired)
                                        }
                                    }
                                    BluetoothDevice.BOND_NONE ->
                                        if (device.address == pairingAddress) pairingAddress = null
                                }
                            }
                        }
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
            // Bluetooth discovery broadcasts originate from the system Bluetooth service, so a
            // NOT_EXPORTED receiver would not receive them on some Android releases.
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { bluetoothAdapter(context)?.cancelDiscovery() }
            }
        }
    }

    LaunchedEffect(hasPermission) { reloadPrinters() }
    LaunchedEffect(isDiscovering) {
        if (isDiscovering) {
            // A few vendor Bluetooth stacks omit ACTION_DISCOVERY_FINISHED. Do not leave the
            // dialog showing an endless search in that case.
            delay(20_000)
            isDiscovering = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!printState.isPrinting) onDismiss() },
        icon = { Icon(Icons.Default.Print, contentDescription = null) },
        title = { Text("Print device label") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabelPreview(previewBitmap)
                if (!hasPermission) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Text("Bluetooth permission is needed to find paired Brother printers.")
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(bluetoothPermissions()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Allow Bluetooth")
                    }
                } else if (!bluetoothEnabled) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Text("Bluetooth is turned off.")
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            // Some vendor stacks do not emit ACTION_STATE_CHANGED to an app-owned
                            // receiver. Re-read the adapter after the system dialog returns so the
                            // printer list does not remain stuck in the disabled state.
                            scope.launch {
                                delay(750)
                                reloadPrinters()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Turn on Bluetooth")
                    }
                } else {
                    if (printerOptions.isEmpty()) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Text("No Brother P-touch printer was found yet.")
                    } else {
                        Text(
                            "Available Brother printers",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LazyColumn(modifier = Modifier.height(180.dp)) {
                            items(printerOptions, key = { it.address }) { option ->
                                val pairedPrinter = option.pairedPrinter
                                if (pairedPrinter != null) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth().clickable {
                                                selectPrinter(pairedPrinter)
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = selected?.address == option.address,
                                            onClick = { selectPrinter(pairedPrinter) },
                                        )
                                        Column {
                                            Text(option.name)
                                            Text(
                                                option.address,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(option.name)
                                            Text(
                                                option.address,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                pairingAddress = option.address
                                                val started =
                                                    runCatching {
                                                            option.device.createBond()
                                                        }
                                                        .getOrDefault(false)
                                                if (!started) pairingAddress = null
                                            },
                                            enabled = pairingAddress == null,
                                        ) {
                                            Icon(Icons.Default.Bluetooth, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                if (pairingAddress == option.address) "Pairing…"
                                                else "Pair"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isDiscovering) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.size(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Searching for nearby Brother printers…")
                        }
                    }
                    TextButton(onClick = ::reloadPrinters) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan again")
                    }
                    if (selectedPrinterNotVisible) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${selected?.name} is paired but is not currently visible. " +
                                    "You can still try printing.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "The label contains the cached device URL as a QR code and its asset tag.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = copiesText,
                            onValueChange = { value ->
                                copiesText = value.filter(Char::isDigit).take(1)
                                copiesText
                                    .toIntOrNull()
                                    ?.takeIf { it in 1..9 }
                                    ?.let { copies ->
                                        settingsViewModel.update { it.copy(copies = copies) }
                                    }
                            },
                            label = { Text("Copies") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = copyCount == null,
                            modifier = Modifier.width(110.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            OutlinedButton(onClick = { qrSizeMenuExpanded = true }) {
                                Text("QR ${qrSize}px")
                            }
                            DropdownMenu(
                                expanded = qrSizeMenuExpanded,
                                onDismissRequest = { qrSizeMenuExpanded = false },
                            ) {
                                listOf(48, 56, 64).forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text("${size}px") },
                                        onClick = {
                                            qrSize = size
                                            settingsViewModel.update { it.copy(qrSize = size) }
                                            qrSizeMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (request.longLabelText != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Long label")
                                Text(
                                    "Print name, asset tag, and serial like printlabel --long",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = longLabel,
                                onCheckedChange = { value ->
                                    longLabel = value
                                    settingsViewModel.update { it.copy(longLabel = value) }
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Invert print colors")
                            Text(
                                "Disable if printing on black tape",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = invertColors,
                            onCheckedChange = { value ->
                                invertColors = value
                                settingsViewModel.update { it.copy(invertColors = value) }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Vertical label text")
                            Text(
                                "Rotate the text for narrow labels",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = verticalText,
                            onCheckedChange = { value ->
                                verticalText = value
                                settingsViewModel.update { it.copy(verticalText = value) }
                            },
                        )
                    }
                }
                if (printState.isPrinting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Printing…")
                    }
                }
                printState.message?.let { message ->
                    Text(
                        message,
                        color =
                            if (message == "Printed") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !printState.isPrinting) { Text("Close") }
        },
        confirmButton = {
            Button(
                onClick = {
                    val printer = selected ?: return@Button
                    val count = copyCount ?: return@Button
                    // Android advises canceling discovery before opening an RFCOMM socket; an
                    // active inquiry can otherwise starve the connection and make a reachable
                    // bonded P-touch look offline.
                    bluetoothAdapter(context)?.cancelDiscovery()
                    printState = PrintOperationState.Printing
                    scope.launch {
                        val result = runCatching {
                            val text =
                                if (longLabel) request.longLabelText ?: request.labelText
                                else request.labelText
                            BrotherLabelRenderer.render(
                                    request.objectUrl,
                                    text,
                                    invert = invertColors,
                                    vertical = verticalText,
                                    qrSize = qrSize,
                                )
                                .also { label ->
                                    repeat(count) {
                                        BrotherPrinter.print(printer, label).getOrThrow()
                                    }
                                }
                        }
                        result
                            .onSuccess { onDismiss() }
                            .onFailure { error ->
                                printState =
                                    PrintOperationState.Failed(
                                        printerFailureMessage(printer.name, error)
                                    )
                            }
                    }
                },
                enabled =
                    selected != null &&
                        !printState.isPrinting &&
                        hasPermission &&
                        copyCount != null,
            ) {
                Icon(Icons.Default.Print, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Print")
            }
        },
    )
}

@Composable
private fun LabelPreview(bitmap: android.graphics.Bitmap?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Preview", style = MaterialTheme.typography.titleSmall)
        if (bitmap == null) {
            Text(
                "The label preview is unavailable for this device URL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Label preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(96.dp).padding(8.dp),
                )
            }
        }
    }
}

private fun bluetoothPermissions(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasBluetoothPermission(context: Context): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

private fun printerFailureMessage(printerName: String, error: Throwable): String {
    val details = error.message.orEmpty()
    return when {
        details.contains("timeout", ignoreCase = true) ||
            details.contains("socket", ignoreCase = true) ||
            details.contains("page_timeout", ignoreCase = true) ->
            "Couldn't reach $printerName. Make sure it is powered on and nearby, then try again."
        else -> details.ifBlank { "Printing failed - try again" }
    }
}
