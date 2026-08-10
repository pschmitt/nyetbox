package dev.pschmitt.nyetbox.ui.onboarding

import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.api.NAMED_API_TOKEN_PREFIX
import dev.pschmitt.nyetbox.data.api.composeNamedApiToken
import dev.pschmitt.nyetbox.data.api.parseNamedApiToken
import dev.pschmitt.nyetbox.scanner.NetBoxTarget

private enum class TokenEntryMode {
    Split,
    Full,
}

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onScanSetupClick: () -> Unit,
    initialSetup: NetBoxTarget.Setup? = null,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var baseUrl by
        remember(initialSetup?.baseUrl) { mutableStateOf(initialSetup?.baseUrl.orEmpty()) }
    val parsedInitialToken =
        remember(initialSetup?.token) { parseNamedApiToken(initialSetup?.token.orEmpty()) }
    var tokenMode by
        remember(initialSetup?.token) {
            mutableStateOf(
                if (parsedInitialToken?.prefix == NAMED_API_TOKEN_PREFIX) {
                    TokenEntryMode.Split
                } else {
                    TokenEntryMode.Full
                }
            )
        }
    var tokenName by
        remember(initialSetup?.token) { mutableStateOf(parsedInitialToken?.name.orEmpty()) }
    var tokenValue by
        remember(initialSetup?.token) {
            mutableStateOf(
                when (parsedInitialToken?.prefix) {
                    NAMED_API_TOKEN_PREFIX -> parsedInitialToken.value
                    else -> initialSetup?.token.orEmpty()
                }
            )
        }
    var tokenVisible by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.restoreBackup(it) }
        }
    val tokenToSubmit =
        if (tokenMode == TokenEntryMode.Split) {
            // Keep the field forgiving while editing. The server still provides the final
            // validation, and this preserves the old test/setup flow for an intentionally invalid
            // token entered without a name.
            composeNamedApiToken(tokenName, tokenValue) ?: tokenValue.trim()
        } else {
            tokenValue.trim()
        }

    fun acceptTokenInput(input: String) {
        val parsed = parseNamedApiToken(input)
        if (parsed == null) {
            tokenValue = input
        } else if (parsed.prefix == NAMED_API_TOKEN_PREFIX) {
            tokenName = parsed.name
            tokenValue = parsed.value
            tokenMode = TokenEntryMode.Split
        } else {
            tokenValue = input.trim()
            tokenMode = TokenEntryMode.Full
        }
    }

    val passwordRequiredState = uiState as? OnboardingUiState.PasswordRequired
    if (passwordRequiredState != null) {
        AlertDialog(
            onDismissRequest = {
                restorePassword = ""
                viewModel.consumeRestoredBackup()
            },
            title = { Text("Password required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This settings backup is password-protected.")
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("Backup password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.restoreBackup(passwordRequiredState.uri, restorePassword)
                        restorePassword = ""
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        restorePassword = ""
                        viewModel.consumeRestoredBackup()
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // ic_launcher is an <adaptive-icon> (background + foreground layers) - painterResource() only
    // supports VectorDrawables and raster assets, not that wrapper format, and throws at runtime.
    // Rendering it through a Drawable -> Bitmap first works for any drawable type.
    val appIconBitmap = remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.asImageBitmap()
    }

    LaunchedEffect(uiState) {
        (uiState as? OnboardingUiState.Success)?.let { success ->
            if (success.restoredBackup) {
                Toast.makeText(context, "Welcome back!", Toast.LENGTH_SHORT).show()
            }
            onDone()
        }
    }

    // A setup QR code is already a complete set of credentials. Start validation as soon as the
    // scanner hands it back instead of making the user re-enter the fields and press Connect.
    LaunchedEffect(initialSetup?.baseUrl, initialSetup?.token) {
        initialSetup?.let { viewModel.connect(it.baseUrl, it.token) }
    }

    Scaffold { padding ->
        Column(
            modifier =
                Modifier.padding(padding)
                    .padding(24.dp)
                    .fillMaxSize()
                    // Edge-to-edge (enableEdgeToEdge() in MainActivity) opts out of the legacy
                    // windowSoftInputMode=adjustResize behavior, so without this the keyboard
                    // overlaps the fields below the fold instead of the content shifting up.
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            verticalArrangement = Arrangement.Center,
        ) {
            if (appIconBitmap != null) {
                Image(
                    bitmap = appIconBitmap,
                    contentDescription = null,
                    modifier =
                        Modifier.size(64.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Connect to NetBox",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter your NetBox instance URL and an API token. Generate a token under your " +
                    "NetBox profile → API Tokens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("NetBox URL") },
                placeholder = { Text("https://netbox.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val tokensUrl = baseUrl.trim().trimEnd('/') + "/user/api-tokens/"
                            context.startActivity(Intent(Intent.ACTION_VIEW, tokensUrl.toUri()))
                        },
                        enabled = baseUrl.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open API tokens page",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-url"),
            )
            Spacer(Modifier.height(12.dp))
            Text("Token format", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tokenMode == TokenEntryMode.Split,
                    onClick = {
                        tokenMode = TokenEntryMode.Split
                        parseNamedApiToken(tokenValue)?.let { parsed ->
                            tokenName = parsed.name
                            tokenValue = parsed.value
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    label = { Text("Split fields") },
                )
                FilterChip(
                    selected = tokenMode == TokenEntryMode.Full,
                    onClick = {
                        tokenValue = composeNamedApiToken(tokenName, tokenValue) ?: tokenValue
                        tokenMode = TokenEntryMode.Full
                    },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    label = { Text("Full token") },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (tokenMode == TokenEntryMode.Split) {
                    "Recommended: enter the token name and secret separately. We’ll send " +
                        "nbt_<name>.<token>."
                } else {
                    "Paste a complete token for an existing connection or an older NetBox instance."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (tokenMode == TokenEntryMode.Split) {
                OutlinedTextField(
                    value = tokenName,
                    onValueChange = { tokenName = it },
                    label = { Text("Token name") },
                    placeholder = { Text("home-phone") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tokenValue,
                    onValueChange = ::acceptTokenInput,
                    label = { Text("Token") },
                    placeholder = { Text("Paste the token secret") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    singleLine = true,
                    visualTransformation =
                        if (tokenVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TokenTrailingActions(
                            tokenVisible = tokenVisible,
                            onToggleVisibility = { tokenVisible = !tokenVisible },
                            onPaste = {
                                val clipboard = context.getSystemService<ClipboardManager>()
                                clipboard
                                    ?.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.text
                                    ?.toString()
                                    ?.let(::acceptTokenInput)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-token"),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Uses: nbt_${tokenName.ifBlank { "<name>" }}.${if (tokenValue.isBlank()) "<token>" else "••••••••"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                OutlinedTextField(
                    value = tokenValue,
                    onValueChange = { tokenValue = it },
                    label = { Text("Full API token") },
                    placeholder = { Text("nbt_token-name.secret") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    singleLine = true,
                    visualTransformation =
                        if (tokenVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TokenTrailingActions(
                            tokenVisible = tokenVisible,
                            onToggleVisibility = { tokenVisible = !tokenVisible },
                            onPaste = {
                                val clipboard = context.getSystemService<ClipboardManager>()
                                clipboard
                                    ?.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.text
                                    ?.toString()
                                    ?.let { tokenValue = it }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-token"),
                )
            }
            Spacer(Modifier.height(16.dp))
            val errorState = uiState as? OnboardingUiState.Error
            if (errorState != null) {
                Text(
                    errorState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { viewModel.connect(baseUrl, tokenToSubmit) },
                enabled = uiState !is OnboardingUiState.Validating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState is OnboardingUiState.Validating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Connect")
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onScanSetupClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan connection setup QR code")
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    restoreLauncher.launch(
                        arrayOf("application/octet-stream", "application/json", "*/*")
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Restore settings backup")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Nyetbox is an independent project and is not affiliated with NetBox Labs in any way, shape, or form.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TokenTrailingActions(
    tokenVisible: Boolean,
    onToggleVisibility: () -> Unit,
    onPaste: () -> Unit,
) {
    Row {
        IconButton(onClick = onToggleVisibility) {
            Icon(
                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (tokenVisible) "Hide token" else "Show token",
            )
        }
        IconButton(onClick = onPaste) {
            Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
        }
    }
}
