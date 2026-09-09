package ai.hermes.bots.ui.editor

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.ui.components.FaceAvatar
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory


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
    val snackbar = remember { SnackbarHostState() }
    var modelMenu by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setPickedAvatar(loadAvatarFromUri(context, uri))
    }

    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(if (ui.isEdit) "Edit bot" else "New bot") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                    Button(onClick = { vm.save() }, enabled = !ui.saving && (ui.isEdit || ui.name.isNotBlank())) {
                        Text(if (ui.saving) "Saving…" else "Save")
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
                    onValueChange = { n -> vm.set { it.copy(name = n) } },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ui.cloneFrom,
                    onValueChange = { n -> vm.set { it.copy(cloneFrom = n) } },
                    label = { Text("Clone from (optional profile name)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = ui.description,
                onValueChange = { n -> vm.set { it.copy(description = n) } },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            if (!ui.isEdit) {
                Column {
                    Text("Create on", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        connections.take(3).forEach { c ->
                            FilterChip(
                                selected = c.id == ui.createOnConnectionId,
                                onClick = { vm.set { it.copy(createOnConnectionId = c.id) } },
                                label = { Text(c.label) },
                            )
                        }
                    }
                }
            }
            var advanced by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { advanced = !advanced }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Text(
                    if (ui.provider.isBlank() && ui.model.isBlank()) {
                        "Model — tap Advanced to set"
                    } else {
                        ai.hermes.bots.ui.util.Humanize.model("${ui.provider}/${ui.model}")
                            ?: "${ui.provider}/${ui.model}".ifBlank { "Model" }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Advanced",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ui.provider,
                            onValueChange = { n -> vm.set { it.copy(provider = n) } },
                            label = { Text("Provider") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = ui.model,
                            onValueChange = { n -> vm.set { it.copy(model = n) } },
                            label = { Text("Model") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedButton(onClick = { vm.loadModelOptions() }) { Text("Load model options") }
                    if (ui.modelOptions.isNotEmpty()) {
                        ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { modelMenu = it }) {
                            OutlinedTextField(
                                value = "${ui.provider}/${ui.model}".ifBlank { "pick a model" },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Pick from loaded options") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu) },
                            )
                            ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                ui.modelOptions.take(50).forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text("${opt.provider} / ${opt.model}") },
                                        onClick = {
                                            vm.set { it.copy(provider = opt.provider, model = opt.model) }
                                            modelMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = ui.soul,
                onValueChange = { n -> vm.set { it.copy(soul = n) } },
                label = { Text("SOUL.md") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            OutlinedTextField(
                value = ui.sectionId,
                onValueChange = { n -> vm.set { it.copy(sectionId = n) } },
                label = { Text("Section") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
            if (ui.skills.isNotEmpty()) {
                val onCount = ui.skills.count { it.enabled }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Skills", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "$onCount of ${ui.skills.size} on",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.skills.forEach { skill ->
                        FilterChip(
                            selected = skill.enabled,
                            onClick = { vm.toggleSkill(skill.name, !skill.enabled) },
                            label = { Text(skill.name) },
                        )
                    }
                }
            }
        }
    }
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
