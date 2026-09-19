package ai.hermes.bots.ui.editor

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.HealthEntry
import ai.hermes.bots.data.HealthState
import ai.hermes.bots.data.ModelCatalog
import ai.hermes.bots.data.ProviderOption
import ai.hermes.bots.ui.components.FaceAvatar
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BotEditorScreen(
    editConnectionId: String?,
    editName: String?,
    onBack: () -> Unit,
    vm: BotEditorViewModel = viewModel(
        key = "editor:${editConnectionId ?: "new"}:${editName ?: "new"}",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ai.hermes.bots.HermesBotsApp
                BotEditorViewModel(app, editConnectionId, editName)
            }
        },
    ),
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val connections by vm.connections.collectAsState()
    val health by vm.health.collectAsState()
    val healthMap by vm.healthMap.collectAsState()
    val rosterRows by vm.rosterRows.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var keyDialogFor by remember { mutableStateOf<ProviderOption?>(null) }
    val dirty by vm.dirty.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }

    // A stray swipe or tap on the back arrow must not throw away an authored SOUL.md.
    BackHandler(enabled = dirty && !ui.saving && !ui.saved) { confirmDiscard = true }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val image = loadAvatarFromUri(context, uri)
            if (image != null) {
                vm.setPickedAvatar(image)
            } else {
                scope.launch { snackbar.showSnackbar("Couldn't use that photo — try a different one.") }
            }
        }
    }

    // D: no-pin saves pop immediately (as before); pin saves stay in the saved phase and
    // auto-pop shortly after a ✓ Working verdict so the user sees it land.
    LaunchedEffect(ui.saved) { if (ui.saved && !ui.savedPhase) onBack() }
    LaunchedEffect(ui.savedPhase, health?.state) {
        if (ui.savedPhase && health?.state == HealthState.WORKING) {
            delay(1_500)
            onBack()
        }
    }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(ui.error) {
        ui.error?.let { raw ->
            val text = ai.hermes.bots.ui.util.Humanize.friendlyError(raw, ui.name.ifBlank { "this bot" })
                ?: "Something went wrong. Check the gateway and try again."
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        when {
                            ui.savedPhase -> "Saved ✓"
                            ui.isEdit -> "Edit bot"
                            else -> "New bot"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (dirty && !ui.saving && !ui.saved) confirmDiscard = true else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    val doneMode = ui.savedPhase && !ui.pinChanged
                    Button(
                        onClick = { if (doneMode) onBack() else vm.save() },
                        enabled = doneMode || (!ui.saving && (ui.isEdit || (ui.name.isNotBlank() && ui.nameProblem == null))),
                    ) {
                        Text(
                            when {
                                ui.saving -> "Saving…"
                                doneMode -> "Done"
                                else -> "Save"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (ui.loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val picked = ui.pickedAvatar
                val bmp = remember(picked?.bytes) {
                    picked?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Avatar",
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                } else {
                    FaceAvatar(name = ui.name.ifBlank { "new" }, size = 64.dp)
                }
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose avatar") }
            }
            if (!ui.isEdit) {
                OutlinedTextField(
                    value = ui.name,
                    onValueChange = { n -> vm.setName(n) },
                    label = { Text("Name") },
                    isError = ui.nameProblem != null,
                    supportingText = {
                        val problem = ui.nameProblem
                        when {
                            problem != null -> Text(
                                problem,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            ui.nameSlug.isNotBlank() -> Text(
                                "Will be created as \"${ui.nameSlug}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                StartFromDropdown(
                    startFrom = ui.startFrom,
                    donorNames = rosterRows
                        .filter { it.bot.connectionId == ui.createOnConnectionId }
                        .map { it.bot.displayName?.takeIf { dn -> dn.isNotBlank() } ?: it.bot.name },
                ) { chosen -> vm.startFrom(chosen) }
                Column {
                    Text("Create on", style = MaterialTheme.typography.labelMedium)
                    // FlowRow so every gateway choice stays visible and thumb-sized — a
                    // single scrolling line hides options with no affordance.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        connections.forEach { c ->
                            FilterChip(
                                selected = c.id == ui.createOnConnectionId,
                                onClick = { vm.setCreateOn(c.id) },
                                label = { Text(c.label) },
                            )
                        }
                    }
                }
            }
            ModelSection(
                ui = ui,
                health = health,
                healthMap = healthMap,
                rosterRows = rosterRows,
                onProvider = { vm.setProvider(it) },
                onModel = { m -> vm.set { st -> st.copy(model = m) } },
                onAdoptPin = { p, m -> vm.adoptPinFrom(p, m) },
                onManualEntry = { vm.setManualEntry(true) },
                onBackToList = { vm.setManualEntry(false) },
                onProviderText = { n -> vm.set { it.copy(provider = n) } },
                onModelText = { n -> vm.set { it.copy(model = n) } },
                onRefreshCatalog = { vm.loadOptions(refresh = true) },
                onRetryOptions = { vm.loadOptions() },
                onResolvePinUnlisted = { keep -> vm.resolvePinUnlisted(keep) },
                onAddKey = { keyDialogFor = it },
                onRetest = { vm.retest() },
            )
            OutlinedTextField(
                value = ui.description,
                onValueChange = { n -> vm.set { it.copy(description = n) } },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = ui.soul,
                onValueChange = { n -> vm.set { it.copy(soul = n) } },
                label = { Text("SOUL.md") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            SectionDropdown(
                sectionId = ui.sectionId,
                existingSections = rosterRows.mapNotNull { it.bot.sectionId }.filter { it.isNotBlank() }.distinct().sorted(),
                onPick = { s -> vm.set { it.copy(sectionId = s) } },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ui.hidden, onClick = { vm.set { it.copy(hidden = !it.hidden) } }, label = { Text("Hidden") })
            }
            ui.relayCapable?.let { capable ->
                Text(
                    if (capable) {
                        "Can message bots on other gateways"
                    } else {
                        "Can't message other bots yet — Save to enable it"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (capable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            ChipGroupSection(
                "Skills",
                ui.skills.map { ChipToggle(it.name, it.enabled) },
                onToggle = { name, on -> vm.toggleSkill(name, on) },
                onSetAll = vm::setAllSkills,
            )
            ChipGroupSection(
                "Toolsets",
                ui.toolsets.map { ChipToggle(it.name, it.enabled) },
                onToggle = { name, on -> vm.toggleToolset(name, on) },
                onSetAll = vm::setAllToolsets,
            )
            ChipGroupSection(
                "MCP servers",
                ui.mcpServers.map { ChipToggle(it.name, it.enabled) },
                onToggle = { name, on -> vm.toggleMcpServer(name, on) },
                onSetAll = vm::setAllMcpServers,
            )
        }
    }

    keyDialogFor?.let { provider ->
        AddKeyDialog(
            providerName = ModelCatalog.displayName(provider),
            onDismiss = { keyDialogFor = null },
            onSave = { key ->
                vm.saveKey(provider.slug, key)
                keyDialogFor = null
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this bot haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

private data class ChipToggle(val name: String, val enabled: Boolean)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipGroupSection(
    title: String,
    rows: List<ChipToggle>,
    onToggle: (String, Boolean) -> Unit,
    onSetAll: ((Boolean) -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    val onCount = rows.count { it.enabled }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(
            "$onCount of ${rows.size} on",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (onSetAll != null) {
            // Bulk strip / bulk restore — the phone-friendly way to rebuild a skill set
            // from scratch instead of tapping every chip.
            TextButton(
                enabled = onCount > 0,
                onClick = { onSetAll(false) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("None") }
            TextButton(
                enabled = onCount < rows.size,
                onClick = { onSetAll(true) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("All") }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            FilterChip(
                selected = row.enabled,
                onClick = { onToggle(row.name, !row.enabled) },
                label = { Text(row.name) },
            )
        }
    }
}

/** Section picker — tap, don't type. Offers every section already in use on the fleet,
 * a "Bots (no section)" default, and a "New section…" escape hatch for genuinely new
 * names (the only case that still needs the keyboard).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionDropdown(
    sectionId: String,
    existingSections: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var typingNew by remember { mutableStateOf(false) }
    if (typingNew) {
        OutlinedTextField(
            value = sectionId,
            onValueChange = onPick,
            label = { Text("New section name") },
            supportingText = { Text("Tap ✕ to pick an existing section instead") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = {
                    typingNew = false
                    onPick("")
                }) { Icon(Icons.Filled.Close, contentDescription = "Pick from list instead") }
            },
        )
        return
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = sectionId.ifBlank { "Bots (no section)" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Section") },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Bots (no section)") },
                onClick = {
                    onPick("")
                    expanded = false
                },
            )
            existingSections.forEach { section ->
                DropdownMenuItem(
                    text = { Text(section) },
                    onClick = {
                        onPick(section)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("New section…") },
                onClick = {
                    onPick("")
                    typingNew = true
                    expanded = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartFromDropdown(startFrom: String?, donorNames: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = startFrom ?: "Fresh bot",
            onValueChange = {},
            readOnly = true,
            label = { Text("Start from") },
            supportingText = {
                Text(
                    "Copy an existing bot's setup, or start fresh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Fresh bot") }, onClick = { onPick(""); expanded = false })
            donorNames.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(name); expanded = false })
            }
        }
    }
}

/**
 * P4: the model section is PRIMARY — always visible, no "Advanced" disclosure. Provider
 * dropdown (authenticated first) → model dropdown scoped to the provider with search →
 * inline key paste / warnings / verification row.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModelSection(
    ui: EditorUiState,
    health: HealthEntry?,
    healthMap: Map<String, HealthEntry>,
    rosterRows: List<ai.hermes.bots.data.RosterEntry>,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onAdoptPin: (String, String) -> Unit,
    onManualEntry: () -> Unit,
    onBackToList: () -> Unit,
    onProviderText: (String) -> Unit,
    onModelText: (String) -> Unit,
    onRefreshCatalog: () -> Unit,
    onRetryOptions: () -> Unit,
    onResolvePinUnlisted: (Boolean) -> Unit,
    onAddKey: (ProviderOption) -> Unit,
    onRetest: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val summary = ai.hermes.bots.ui.util.Humanize.model("${ui.provider}/${ui.model}")
            Text("Model", style = MaterialTheme.typography.titleSmall)
            Text(
                summary ?: "Pick the model this bot runs on",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ui.optionsError?.let { err ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetryOptions) { Text("Retry") }
                }
            }
            if (ui.optionsLoading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Loading providers…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // A4: the loaded pin isn't offered on this gateway — keep the raw value or clear it.
            if (ui.pinUnlisted) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Saved pin isn't offered by this gateway",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        "The bot's saved provider \"${ui.pinProvider.orEmpty()}\" isn't in this gateway's list." +
                            " Keep it if you know it works, or clear it and pick from the list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onResolvePinUnlisted(true) }) { Text("Keep") }
                        TextButton(onClick = { onResolvePinUnlisted(false) }) { Text("Clear") }
                    }
                }
            }

            val models = ModelCatalog.modelsFor(ui.providers, ui.provider)
            if (ui.manualEntry) {
                // Desktop-parity escape hatch: free-text provider/model, stacked full-width —
                // two half-width columns side by side are cramped thumb territory on a phone.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ui.provider,
                        onValueChange = onProviderText,
                        label = { Text("Provider") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = ui.model,
                        onValueChange = onModelText,
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                TextButton(onClick = onBackToList) { Text("Back to list") }
            } else {
                ProviderDropdown(
                    selectedSlug = ui.provider,
                    providers = ui.providers,
                    connectionId = ui.createOnConnectionId,
                    healthMap = healthMap,
                    pinWorking = health?.state == HealthState.WORKING,
                    onSelect = onProvider,
                    onManual = onManualEntry,
                )
                // A1: adopt another bot's proven pin in one tap.
                val exclude = when {
                    ui.isEdit -> ui.name
                    ui.savedPhase -> ui.nameSlug
                    else -> null
                }
                val candidates = EditorModel.sameAsCandidates(rosterRows, ui.createOnConnectionId, exclude)
                if (candidates.isNotEmpty()) {
                    Text("Copy a working setup from another bot", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        candidates.forEach { (name, p, m) ->
                            FilterChip(
                                selected = false,
                                onClick = { onAdoptPin(p, m) },
                                label = { Text("Same as $name") },
                            )
                        }
                    }
                }
                ModelDropdown(
                    enabled = ui.provider.isNotBlank(),
                    models = models,
                    selectedModel = ui.model,
                    connectionId = ui.createOnConnectionId,
                    providerSlug = ui.provider,
                    healthMap = healthMap,
                    unlistedProvider = ui.manualEntry ||
                        ui.providers.firstOrNull { it.slug == ui.provider }?.models.isNullOrEmpty(),
                    onSelect = onModel,
                )
                // R8: an authenticated provider with no listed models is real (openrouter) —
                // offer a forced server-side catalog rebuild.
                val providerRow = ui.providers.firstOrNull { it.slug == ui.provider }
                if (ui.provider.isNotBlank() && models.isEmpty() && providerRow?.authenticated == true &&
                    !ui.optionsLoading && ui.optionsError == null
                ) {
                    OutlinedButton(onClick = onRefreshCatalog) { Text("No models listed — Refresh catalog") }
                }
            }

            // A2: bare custom:* model ids 400 at turn time on vendor-prefixed endpoints.
            if (!ui.manualEntry && ModelCatalog.needsVendorPrefixWarning(ui.provider, ui.model)) {
                Text(
                    "This endpoint expects vendor-prefixed ids — e.g. vendor/model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            // P7: inline key paste for unauthenticated api-key providers; guidance otherwise.
            val selected = ui.providers.firstOrNull { it.slug == ui.provider }
            if (selected != null && !selected.authenticated) {
                when {
                    selected.slug.trim().lowercase() == "custom" -> HelperLine(
                        "Custom providers are keyed on the gateway itself — managed by whoever runs it",
                    )
                    selected.slug.trim().lowercase().startsWith("custom") -> HelperLine(
                        "This gateway's custom provider is keyed on the gateway host",
                    )
                    selected.authType == "api_key" && !selected.keyEnv.isNullOrBlank() -> {
                        HelperLine("No key on the gateway yet")
                        OutlinedButton(onClick = { onAddKey(selected) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Add key")
                        }
                    }
                    else -> HelperLine(
                        "Sign-in provider — connect it once on the gateway, then it shows ready here",
                    )
                }
            }

            HealthRow(
                entry = health,
                hasSavedPin = ui.pinProvider != null && ui.pinModel != null,
                candidatePin = !ui.isEdit && ui.provider.isNotBlank() && ui.model.isNotBlank(),
                onRetest = onRetest,
            )
        }
    }
}

@Composable
private fun HelperLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    selectedSlug: String,
    providers: List<ProviderOption>,
    connectionId: String,
    healthMap: Map<String, HealthEntry>,
    pinWorking: Boolean,
    onSelect: (String) -> Unit,
    onManual: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // Probe-backed grouping: only rows that verifiably work lead the list (the gateway's
    // authenticated flag lies — live probes found "no credentials" / "out of quota" /
    // "model not supported" providers wearing ✓). Everything else hides behind an expander.
    val (ready, more) = remember(providers, healthMap, connectionId) {
      EditorModel.splitProviders(providers, healthMap, connectionId)
    }
    val ordered = if (showMore) ready + more else ready
    val selected = providers.firstOrNull { it.slug == selectedSlug }
    val label = when {
        selected != null -> ModelCatalog.displayName(selected) + if (EditorModel.showNoKeyBadge(selected, pinWorking)) " · No key" else ""
        selectedSlug.isNotBlank() -> selectedSlug
        else -> "Choose a provider"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            supportingText = if (selected != null && EditorModel.showGatewayWarning(selected)) {
                {
                    // A2: gateway notice as the supporting line once selected — only when it
                    // reads as humane copy (setup commands leak through otherwise; the
                    // key/sign-in guidance block under the field covers those).
                    Text(
                        selected.warning.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (ordered.isEmpty()) {
                DropdownMenuItem(text = { Text("Checking what works on this gateway…") }, enabled = false, onClick = {})
            }
            ordered.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    ModelCatalog.displayName(p) + if (EditorModel.showNoKeyBadge(p, pinWorking = false)) " · No key" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (p.slug == selectedSlug) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                            val hint = EditorModel.providerHealthHint(healthMap, connectionId, p.slug)
                            val probe = EditorModel.providerProbe(healthMap, connectionId, p.slug)
                            val sub = buildList {
                                if (p.isCurrent) add("Current on gateway")
                                when (probe?.state) {
                                    HealthState.TESTING -> add("Checking…")
                                    else -> hint?.let { add(it) }
                                }
                            }
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = if (p.authenticated) {
                        { Icon(Icons.Filled.Check, contentDescription = "Key on gateway", tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    trailingIcon = if (EditorModel.showGatewayWarning(p)) {
                        { Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.secondary) }
                    } else {
                        null
                    },
                    onClick = { onSelect(p.slug); expanded = false },
                )
            }
            if (more.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            if (showMore) "Hide the other ${more.size} providers" else "Show ${more.size} more providers (need setup)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { showMore = !showMore },
                )
                if (showMore) {
                    more.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(ModelCatalog.displayName(p), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        EditorModel.moreReason(p, healthMap, connectionId),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = { onSelect(p.slug); expanded = false },
                        )
                    }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Enter manually…") }, onClick = { onManual(); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    enabled: Boolean,
    models: List<String>,
    selectedModel: String,
    connectionId: String,
    providerSlug: String,
    healthMap: Map<String, HealthEntry>,
    unlistedProvider: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selectedModel.ifBlank { "Pick a model" },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Model") },
            supportingText = {
                // An unlisted (e.g. bare custom) provider legitimately has no catalog rows —
                // the entered id is still used verbatim, so say that instead of a bare "none".
                Text(
                    when {
                        models.isNotEmpty() -> "${models.size} models"
                        unlistedProvider -> "Not in the gateway's catalog — kept as entered"
                        else -> "No models listed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false; query = "" },
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search models") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val filtered = if (query.isBlank()) models else models.filter { it.contains(query.trim(), ignoreCase = true) }
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text("No models listed") }, enabled = false, onClick = {})
            } else {
                // Column+scroll, not LazyColumn: the menu popup measures content intrinsically
                // and lazy lists (SubcomposeLayout) crash on that query. Rows are cheap texts;
                // cap the rendered window and ask for a search beyond it.
                val shown = filtered.take(MAX_RENDERED_MODELS)
                Column(
                    Modifier
                        .height((shown.size.coerceAtMost(6) * 48).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    shown.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m, style = MaterialTheme.typography.bodyLarge)
                                    EditorModel.modelHealthHint(healthMap, connectionId, providerSlug, m)?.let { hint ->
                                        Text(
                                            hint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            onClick = { onSelect(m); expanded = false; query = "" },
                        )
                    }
                    if (filtered.size > MAX_RENDERED_MODELS) {
                        DropdownMenuItem(
                            text = { Text("Type to search ${filtered.size} models") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_RENDERED_MODELS = 100

/** P9/A5: the verification verdict row under the model dropdown. */
@Composable
private fun HealthRow(
    entry: HealthEntry?,
    hasSavedPin: Boolean,
    candidatePin: Boolean,
    onRetest: () -> Unit,
) {
    val state = entry?.state
    val showRow = entry != null || hasSavedPin || candidatePin
    if (!showRow) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        when (state) {
            HealthState.TESTING -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Testing model…", style = MaterialTheme.typography.bodySmall)
            }
            HealthState.WORKING -> {
                Text(
                    "✓ Working · ${EditorModel.latencySeconds(entry?.latencyMs ?: 0L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HealthState.FAILED -> {
                Text(
                    entry?.reason ?: "The model did not respond",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HealthState.UNTESTED, null -> {
                when {
                    hasSavedPin -> {
                        Text(
                            entry?.reason ?: "Not tested yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetest, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retest") }
                    }
                    candidatePin -> Text(
                        "Save to test this model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddKeyDialog(providerName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add key for $providerName") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored on the gateway, never in the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (key.isNotBlank()) onSave(key.trim()) },
                enabled = key.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}

private fun loadAvatarFromUri(context: android.content.Context, uri: android.net.Uri): AvatarImage? = try {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    val edge = minOf(decoded.width, decoded.height)
    val scale = 512f / edge.coerceAtLeast(1)
    val target = Math.min(512, Math.max(1, Math.round(edge * scale).toInt()))
    val scaled = Bitmap.createScaledBitmap(decoded, target, target, true)
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    AvatarImage("image/jpeg", out.toByteArray())
} catch (e: Exception) {
    null
}
