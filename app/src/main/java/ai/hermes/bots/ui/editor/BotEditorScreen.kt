package ai.hermes.bots.ui.editor

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.ui.components.FaceAvatar
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
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
                title = { Text(if (ui.isEdit) "Edit bot" else "New bot") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                val conn = connections.firstOrNull { it.id == ui.createOnConnectionId }
                ExposedDropdownMenuBox(expanded = false, onExpandedChange = {}) {
                    OutlinedTextField(
                        value = conn?.label ?: ui.createOnConnectionId,
                        onValueChange = {},
                        label = { Text("Create on") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                Column {
                    Text("Connection", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.save() }, enabled = !ui.saving && (ui.isEdit || ui.name.isNotBlank())) {
                    Text(if (ui.saving) "Saving…" else "Save")
                }
            }
            if (ui.skills.isNotEmpty()) {
                Text("Skills (disabled when unchecked)", style = MaterialTheme.typography.labelMedium)
                ui.skills.forEach { skill ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = skill.enabled,
                            onClick = { vm.toggleSkill(skill.name, !skill.enabled) },
                            label = { Text(skill.name) },
                        )
                        Spacer(Modifier.size(8.dp))
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
