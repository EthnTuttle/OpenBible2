package com.schwegelbin.openbible.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.schwegelbin.openbible.R
import com.schwegelbin.openbible.logic.nostr.hexToByteArray
import com.schwegelbin.openbible.logic.nostr.toHexString
import com.schwegelbin.openbible.logic.ReadTextAlignment
import com.schwegelbin.openbible.logic.SchemeOption
import com.schwegelbin.openbible.logic.SplitScreen
import com.schwegelbin.openbible.logic.ThemeOption
import com.schwegelbin.openbible.logic.backupData
import com.schwegelbin.openbible.logic.getCheckAtStartup
import com.schwegelbin.openbible.logic.getColorSchemeInt
import com.schwegelbin.openbible.logic.getDownloadNotification
import com.schwegelbin.openbible.logic.getFontSize
import com.schwegelbin.openbible.logic.getMainThemeOptions
import com.schwegelbin.openbible.logic.getShowVerseNumbers
import com.schwegelbin.openbible.logic.getSplitScreenInt
import com.schwegelbin.openbible.logic.getTextAlignmentInt
import com.schwegelbin.openbible.logic.saveCheckAtStartup
import com.schwegelbin.openbible.logic.saveColorScheme
import com.schwegelbin.openbible.logic.saveDownloadNotification
import com.schwegelbin.openbible.logic.saveFontSize
import com.schwegelbin.openbible.logic.saveShowVerseNumbers
import com.schwegelbin.openbible.logic.saveSplitScreen
import com.schwegelbin.openbible.logic.saveTextAlignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToRead: () -> Unit,
    onThemeChange: (Boolean?, Boolean?, Boolean?) -> Unit
) {
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = { onNavigateToRead() }) {
                Icon(
                    Icons.Filled.Close, stringResource(R.string.close)
                )
            }
        })
    }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val styleLarge = MaterialTheme.typography.titleLarge
            val modLarge = Modifier.padding(bottom = 12.dp)
            val styleMedium = MaterialTheme.typography.titleMedium
            Text(stringResource(R.string.translation), style = styleLarge, modifier = modLarge)
            SettingsField(
                text = stringResource(R.string.check_at_startup),
                initialState = getCheckAtStartup(context),
                saveFunction = { checked ->
                    saveCheckAtStartup(context, checked)
                }
            )

            /* TODO: Implement Language Change
             * https://github.com/SchweGELBin/OpenBible2/issues/13
            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.locale), style = styleLarge, modifier = modLarge)
            Text(stringResource(R.string.language), style = styleMedium)
            LanguageButton(onLanguageChange)
            */

            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.colors), style = styleLarge, modifier = modLarge)
            Text(stringResource(R.string.color_theme), style = styleMedium)
            ThemeButton(onThemeChange)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text(stringResource(R.string.color_scheme), style = styleMedium)
                SchemeButton(onThemeChange)
            }

            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.bible_text), style = styleLarge, modifier = modLarge)
            Text(stringResource(R.string.alignment), style = styleMedium)
            ReadTextAlignmentButton()
            Text(stringResource(R.string.split_screen), style = styleMedium)
            SplitScreenButton()
            Text(stringResource(R.string.font_size), style = styleMedium)
            FontSizeSlider()
            SettingsField(
                text = stringResource(R.string.show_verse_number),
                initialState = getShowVerseNumbers(context),
                saveFunction = { checked ->
                    saveShowVerseNumbers(context, checked)
                }
            )
            /* TODO: Implement Infinite Scroll
             * https://github.com/SchweGELBin/OpenBible2/issues/16
            SettingsField(
                text = stringResource(R.string.infinite_scroll),
                initialState = getInfiniteScroll(context),
                saveFunction = { checked ->
                    saveInfiniteScroll(context, checked)
                }
            )
             */

            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.notifications), style = styleLarge, modifier = modLarge)
            SettingsField(
                text = stringResource(R.string.download),
                initialState = getDownloadNotification(context),
                saveFunction = { checked ->
                    saveDownloadNotification(context, checked)
                }
            )
            /* TODO: Implement Verse of the Day
             * https://github.com/SchweGELBin/OpenBible2/issues/19
            SettingsField(
                text = stringResource(R.string.verse_of_the_day),
                initialState = getVerseOfTheDay(context),
                saveFunction = { checked ->
                    saveVerseOfTheDay(context, checked)
                }
            )
             */

            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.backup), style = styleLarge, modifier = modLarge)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BackupButton(isUser = true, text = stringResource(R.string.documents))
                BackupButton(isData = true, text = stringResource(R.string.preferences))
            }
            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.nostr), style = styleLarge, modifier = modLarge)
            NostrSettingsSection()
            HorizontalDivider(Modifier.padding(12.dp))
            Text(stringResource(R.string.about_us), style = styleLarge, modifier = modLarge)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinkButton(
                    text = stringResource(R.string.source_repo),
                    url = "https://github.com/SchweGELBin/OpenBible2"
                )
                LinkButton(
                    text = stringResource(R.string.google_play),
                    url = "https://play.google.com/store/apps/details?id=com.schwegelbin.openbible"
                )
                LinkButton(
                    text = stringResource(R.string.contact),
                    url = "mailto:schwegelbin@gmail.com"
                )
                LinkButton(
                    text = stringResource(R.string.source_getbible),
                    url = "https://getbible.life/docs"
                )
            }
        }
    }
}

@Composable
fun NostrSettingsSection() {
    val context = LocalContext.current
    val hasKey = remember { mutableStateOf(com.schwegelbin.openbible.logic.nostr.hasKeypair(context)) }
    val npub = remember { mutableStateOf(com.schwegelbin.openbible.logic.nostr.getPublicKeyHex(context)) }
    val showImportDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val styleMedium = MaterialTheme.typography.titleMedium

    // Amber result launcher
    val amberLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Amber returns the public key in the result
        val pubkey = result.data?.getStringExtra("pubkey")
        if (pubkey != null && pubkey.length == 64) {
            // Store as pubkey-only (no private key - signing will use Amber)
            com.schwegelbin.openbible.logic.nostr.storePublicKeyOnly(context, pubkey)
            hasKey.value = true
            npub.value = pubkey
            Toast.makeText(context, "Connected to Amber", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to get public key from Amber", Toast.LENGTH_SHORT).show()
        }
    }

    // -- Identity --
    Text(stringResource(R.string.nostr_identity), style = styleMedium)
    if (hasKey.value && npub.value != null) {
        val hexPubkey = npub.value!!
        val npubBech32 = com.schwegelbin.openbible.logic.nostr.hexToNpub(hexPubkey)

        // Clickable npub - tap to copy (copies bech32 format)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("npub", npubBech32))
                    Toast.makeText(context, "Copied npub to clipboard", Toast.LENGTH_SHORT).show()
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = npubBech32.take(12) + "..." + npubBech32.takeLast(8),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.nostr_tap_to_copy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { showQrDialog.value = true }) {
                Text(stringResource(R.string.nostr_show_qr))
            }
            OutlinedButton(onClick = {
                com.schwegelbin.openbible.logic.nostr.deleteKeypair(context)
                hasKey.value = false
                npub.value = null
            }) { Text(stringResource(R.string.nostr_logout)) }
        }

        // QR Code Dialog - shows npub in bech32 format
        if (showQrDialog.value) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showQrDialog.value = false },
                title = { Text(stringResource(R.string.nostr_your_profile)) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        com.schwegelbin.openbible.ui.components.QrCode(
                            content = npubBech32,
                            size = 200.dp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = npubBech32.take(20) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showQrDialog.value = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }
    } else {
        Text(
            text = stringResource(R.string.nostr_no_key),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                val (_, pub) = com.schwegelbin.openbible.logic.nostr.getOrCreateKeypair(context)
                hasKey.value = true
                npub.value = pub
            }) { Text(stringResource(R.string.nostr_generate_key)) }
            OutlinedButton(onClick = {
                showImportDialog.value = true
            }) { Text(stringResource(R.string.nostr_import_key)) }
        }
        Spacer(Modifier.height(4.dp))
        val amberSigner = remember { com.schwegelbin.openbible.logic.nostr.AmberSigner(context, "") }
        OutlinedButton(
            onClick = {
                if (amberSigner.isAvailable()) {
                    try {
                        val intent = amberSigner.createGetPublicKeyIntent()
                        amberLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to launch Amber: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Amber is not installed", Toast.LENGTH_SHORT).show()
                }
            }
        ) { Text(stringResource(R.string.nostr_connect_amber)) }
    }

    // Import nsec dialog
    if (showImportDialog.value) {
        val keyInput = remember { mutableStateOf("") }
        val error = remember { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showImportDialog.value = false },
            title = { Text(stringResource(R.string.nostr_import_key)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = keyInput.value,
                        onValueChange = { keyInput.value = it; error.value = false },
                        label = { Text(stringResource(R.string.nostr_import_key_hint)) },
                        singleLine = true,
                        isError = error.value,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error.value) {
                        Text(
                            stringResource(R.string.nostr_import_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    try {
                        val raw = keyInput.value.trim()
                        // Accept both nsec (bech32) and hex format
                        val hex = when {
                            raw.startsWith("nsec1") -> {
                                com.schwegelbin.openbible.logic.nostr.nsecToHex(raw)
                                    ?: throw IllegalArgumentException("Invalid nsec")
                            }
                            raw.length == 64 && raw.all { it in "0123456789abcdefABCDEF" } -> {
                                raw.lowercase()
                            }
                            else -> throw IllegalArgumentException("Invalid key format")
                        }
                        val pubkey = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(hex.hexToByteArray())
                        val xonly = pubkey.copyOfRange(1, 33)
                        val pubHex = xonly.toHexString()
                        com.schwegelbin.openbible.logic.nostr.storeKeypair(context, hex, pubHex)
                        hasKey.value = true
                        npub.value = pubHex
                        showImportDialog.value = false
                    } catch (_: Exception) {
                        error.value = true
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showImportDialog.value = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Spacer(Modifier.height(12.dp))

    // -- Local relay URL --
    Text(stringResource(R.string.nostr_local_relay), style = styleMedium)
    val localRelay = remember { mutableStateOf(com.schwegelbin.openbible.logic.getLocalRelayUrl(context)) }
    OutlinedTextField(
        value = localRelay.value,
        onValueChange = {
            localRelay.value = it
            com.schwegelbin.openbible.logic.saveLocalRelayUrl(context, it)
        },
        label = { Text(stringResource(R.string.nostr_local_relay)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(Modifier.height(12.dp))

    // -- Public relays (add/remove list with status indicators) --
    Text(stringResource(R.string.nostr_public_relays), style = styleMedium)
    val relays = remember {
        mutableStateOf(com.schwegelbin.openbible.logic.getPublicRelays(context).toMutableList())
    }
    val relayStates = remember {
        mutableStateOf<Map<String, com.schwegelbin.openbible.logic.nostr.RelayState>>(emptyMap())
    }

    // Test relay connections
    LaunchedEffect(relays.value) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val states = mutableMapOf<String, com.schwegelbin.openbible.logic.nostr.RelayState>()
            relays.value.forEach { url ->
                states[url] = com.schwegelbin.openbible.logic.nostr.RelayState.CONNECTING
            }
            relayStates.value = states.toMap()

            relays.value.forEach { url ->
                try {
                    val relay = com.schwegelbin.openbible.logic.nostr.Relay(url)
                    relay.connect()
                    // Wait for connection result
                    kotlinx.coroutines.withTimeoutOrNull(5000L) {
                        relay.state.collect { state ->
                            if (state == com.schwegelbin.openbible.logic.nostr.RelayState.CONNECTED ||
                                state == com.schwegelbin.openbible.logic.nostr.RelayState.DISCONNECTED) {
                                relayStates.value = relayStates.value + (url to state)
                                relay.disconnect()
                                return@collect
                            }
                        }
                    } ?: run {
                        relayStates.value = relayStates.value + (url to com.schwegelbin.openbible.logic.nostr.RelayState.DISCONNECTED)
                    }
                } catch (_: Exception) {
                    relayStates.value = relayStates.value + (url to com.schwegelbin.openbible.logic.nostr.RelayState.DISCONNECTED)
                }
            }
        }
    }

    relays.value.forEachIndexed { index, relay ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            val state = relayStates.value[relay] ?: com.schwegelbin.openbible.logic.nostr.RelayState.DISCONNECTED
            val statusColor = when (state) {
                com.schwegelbin.openbible.logic.nostr.RelayState.CONNECTED -> Color(0xFF4CAF50) // Green
                com.schwegelbin.openbible.logic.nostr.RelayState.CONNECTING -> Color(0xFFFFC107) // Yellow
                com.schwegelbin.openbible.logic.nostr.RelayState.DISCONNECTED -> Color(0xFFF44336) // Red
            }
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                text = relay.removePrefix("wss://").removePrefix("ws://"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            IconButton(onClick = {
                val updated = relays.value.toMutableList()
                updated.removeAt(index)
                relays.value = updated
                com.schwegelbin.openbible.logic.savePublicRelays(context, updated.toSet())
            }) {
                Icon(Icons.Filled.Close, stringResource(R.string.delete))
            }
        }
    }
    val newRelay = remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = newRelay.value,
            onValueChange = { newRelay.value = it },
            label = { Text(stringResource(R.string.nostr_relay_hint)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedButton(
            onClick = {
                val url = newRelay.value.trim()
                if (url.startsWith("wss://") || url.startsWith("ws://")) {
                    val updated = relays.value.toMutableList()
                    updated.add(url)
                    relays.value = updated
                    com.schwegelbin.openbible.logic.savePublicRelays(context, updated.toSet())
                    newRelay.value = ""
                }
            },
            modifier = Modifier.padding(start = 8.dp)
        ) { Text(stringResource(R.string.nostr_add_relay)) }
    }

    Spacer(Modifier.height(8.dp))

    // -- Auto-publish to public relays --
    SettingsField(
        text = stringResource(R.string.nostr_auto_publish),
        initialState = com.schwegelbin.openbible.logic.getAutoPublish(context),
        saveFunction = { checked ->
            com.schwegelbin.openbible.logic.saveAutoPublish(context, checked)
        }
    )
}

@Composable
fun SettingsField(text: String, initialState: Boolean, saveFunction: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(top = 15.dp)
                .weight(1f)
        )
        val isChecked = remember { mutableStateOf(initialState) }
        Switch(checked = isChecked.value, onCheckedChange = {
            isChecked.value = it
            saveFunction(isChecked.value)
        })
    }
}

@Composable
fun LinkButton(text: String, url: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri())
        context.startActivity(intent)
    }) { Text(text) }
}

@Composable
fun ReadTextAlignmentButton() {
    val context = LocalContext.current
    val selectedIndex = remember { mutableIntStateOf(getTextAlignmentInt(context)) }
    val options = ReadTextAlignment.entries

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val label = when (option) {
                ReadTextAlignment.Start -> stringResource(R.string.alignment_start)
                ReadTextAlignment.Justify -> stringResource(R.string.alignment_justify)
            }
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = {
                    selectedIndex.intValue = index
                    saveTextAlignment(context, option)
                },
                selected = index == selectedIndex.intValue
            ) { Text(label) }
        }
    }
}

@Composable
fun SplitScreenButton() {
    val context = LocalContext.current
    val selectedIndex = remember { mutableIntStateOf(getSplitScreenInt(context)) }
    val options = SplitScreen.entries

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val label = when (option) {
                SplitScreen.Off -> stringResource(R.string.off)
                SplitScreen.Vertical -> stringResource(R.string.vertical)
                SplitScreen.Horizontal -> stringResource(R.string.horizontal)
            }
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = {
                    selectedIndex.intValue = index
                    saveSplitScreen(context, option)
                },
                selected = index == selectedIndex.intValue
            ) { Text(label) }
        }
    }
}

@Composable
fun ThemeButton(onThemeChange: (Boolean?, Boolean?, Boolean?) -> Unit) {
    val context = LocalContext.current
    val selectedIndex = remember { mutableIntStateOf(getColorSchemeInt(context, true)) }
    val options = ThemeOption.entries

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val label = when (option) {
                ThemeOption.System -> stringResource(R.string.theme_system)
                ThemeOption.Dark -> stringResource(R.string.theme_dark)
                ThemeOption.Light -> stringResource(R.string.theme_light)
                ThemeOption.Amoled -> stringResource(R.string.theme_amoled)
            }
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = {
                    selectedIndex.intValue = index
                    val (darkTheme, dynamicColor, amoled) = getMainThemeOptions(
                        context, themeOption = option
                    )
                    onThemeChange(darkTheme, dynamicColor, amoled)
                    saveColorScheme(context, theme = option)
                },
                selected = index == selectedIndex.intValue
            ) { Text(label) }
        }
    }
}

@Composable
fun SchemeButton(onThemeChange: (Boolean?, Boolean?, Boolean?) -> Unit) {
    val context = LocalContext.current
    val selectedIndex = remember { mutableIntStateOf(getColorSchemeInt(context, false)) }
    val options = SchemeOption.entries

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val label = when (option) {
                SchemeOption.Static -> stringResource(R.string.scheme_static)
                SchemeOption.Dynamic -> stringResource(R.string.scheme_dynamic)
            }
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = {
                    selectedIndex.intValue = index
                    val (darkTheme, dynamicColor, amoled) = getMainThemeOptions(
                        context, schemeOption = option
                    )
                    onThemeChange(darkTheme, dynamicColor, amoled)
                    saveColorScheme(context, scheme = option)
                },
                selected = index == selectedIndex.intValue
            ) { Text(label) }
        }
    }
}

@Composable
fun FontSizeSlider() {
    val context = LocalContext.current
    val sliderPosition = remember { mutableStateOf(getFontSize(context)) }
    RangeSlider(
        value = sliderPosition.value,
        steps = 9,
        onValueChange = { range -> sliderPosition.value = range },
        valueRange = 1f..2f,
        onValueChangeFinished = { saveFontSize(context, sliderPosition.value) },
    )
}

@Composable
fun BackupButton(isUser: Boolean = false, isData: Boolean = false, text: String) {
    val context = LocalContext.current
    val clicked = remember { mutableStateOf(false) }
    OutlinedButton(onClick = { clicked.value = true }) { Text(text) }
    if (clicked.value) {
        clicked.value = false
        backupData(context, user = isUser, data = isData)
    }
}