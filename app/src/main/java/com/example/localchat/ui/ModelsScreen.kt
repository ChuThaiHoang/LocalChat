package com.example.localchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localchat.ChatViewModel
import com.example.localchat.InstalledModel
import com.example.localchat.Transfer
import com.example.localchat.llm.CatalogModel
import com.example.localchat.llm.LlmEngine
import com.example.localchat.llm.ModelCatalog
import java.util.Locale

/** File size as shown by Android itself: decimal units (1 GB = 1000 MB), local decimal separator. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val pattern = if (unit >= 2) "%.2f %s" else if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), pattern, value, units[unit])
}

private fun progressText(t: Transfer): String =
    if (t.total > 0) {
        "${formatBytes(t.done)} / ${formatBytes(t.total)} (${t.done * 100 / t.total}%)"
    } else {
        formatBytes(t.done)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ChatViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val partials by vm.partials.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val storedToken by vm.hfToken.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var link by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf(storedToken) }

    // .litertlm has no registered MIME type, so the picker has to accept any file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    LaunchedEffect(Unit) { vm.refreshModels() }

    val catalogFiles = remember { ModelCatalog.models.map { it.fileName }.toSet() }
    val installedFiles = installed.map { it.fileName }.toSet()
    // Downloads (or half-finished downloads) of files that are not in the built-in catalog.
    val strayFiles = (downloads.keys + partials.keys).filter { it !in catalogFiles && it !in installedFiles }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mô hình AI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- installed
            item(key = "hdr-installed") { SectionTitle("Đã có trên máy") }
            if (installed.isEmpty()) {
                item(key = "none-installed") {
                    Text(
                        "Chưa có mô hình nào. Hãy tải một mô hình bên dưới, hoặc nhập tệp .litertlm từ bộ nhớ máy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(installed, key = { "inst-${it.fileName}" }) { model ->
                InstalledCard(
                    model = model,
                    isActive = model.fileName == activeModel,
                    status = status,
                    onUse = {
                        vm.loadModel(model.fileName)
                        onBack()
                    },
                    onDelete = { pendingDelete = model },
                )
            }

            // ---- catalog
            item(key = "hdr-catalog") { SectionTitle("Tải về") }
            items(ModelCatalog.models, key = { "cat-${it.id}" }) { model ->
                CatalogCard(
                    model = model,
                    installed = model.fileName in installedFiles,
                    transfer = downloads[model.fileName],
                    partialBytes = partials[model.fileName] ?: 0L,
                    onDownload = { vm.download(model) },
                    onCancel = { vm.cancelDownload(model.fileName) },
                )
            }

            // ---- downloads of files from custom links
            if (strayFiles.isNotEmpty()) {
                item(key = "hdr-stray") { SectionTitle("Đang tải / tải dở") }
                items(strayFiles, key = { "stray-$it" }) { name ->
                    StrayCard(
                        fileName = name,
                        transfer = downloads[name],
                        partialBytes = partials[name] ?: 0L,
                        onCancel = { vm.cancelDownload(name) },
                        onDiscard = { vm.deleteModel(name) },
                    )
                }
            }

            // ---- custom model
            item(key = "hdr-custom") { SectionTitle("Thêm mô hình khác") }
            item(key = "custom") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Dùng được mọi tệp .litertlm, ví dụ trong huggingface.co/litert-community.",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        val activeImport = importing
                        if (activeImport != null) {
                            TransferRow(activeImport, onCancel = { vm.cancelImport() })
                        } else {
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                                Text("Nhập từ tệp trong máy")
                            }
                        }

                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Link tải (https://…/model.litertlm)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Button(
                            onClick = {
                                vm.downloadFromUrl(link)
                                link = ""
                            },
                            enabled = link.isNotBlank(),
                        ) { Text("Tải từ link") }
                    }
                }
            }

            // ---- Hugging Face token
            item(key = "hdr-token") { SectionTitle("Hugging Face token (tuỳ chọn)") }
            item(key = "token") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Một số mô hình Gemma yêu cầu đăng nhập. Chấp nhận điều khoản trên trang mô hình ở " +
                                "huggingface.co, tạo token quyền Read tại huggingface.co/settings/tokens rồi dán vào đây. " +
                                "Token chỉ được lưu trên máy và chỉ gửi tới huggingface.co.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                vm.setToken(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Access token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Xoá mô hình?") },
            text = {
                Text("${model.displayName} (${formatBytes(model.sizeBytes)}) sẽ bị xoá khỏi máy. Bạn có thể tải lại sau.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteModel(model.fileName)
                        pendingDelete = null
                    },
                ) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InstalledCard(
    model: InstalledModel,
    isActive: Boolean,
    status: LlmEngine.Status,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatBytes(model.sizeBytes)} · ${model.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isActive && status is LlmEngine.Status.Loading -> {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Đang nạp…")
                    }
                    isActive && status is LlmEngine.Status.Ready ->
                        Text(
                            "Đang dùng · ${status.backend}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    isActive && status is LlmEngine.Status.Failed ->
                        Button(onClick = onUse) { Text("Thử nạp lại") }
                    else -> Button(onClick = onUse) { Text("Dùng mô hình này") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Xoá mô hình")
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    model: CatalogModel,
    installed: Boolean,
    transfer: Transfer?,
    partialBytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    model.sizeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            when {
                installed -> Text(
                    "Đã tải về máy",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                transfer != null -> TransferRow(transfer, onCancel)
                else -> Button(onClick = onDownload) {
                    Text(if (partialBytes > 0) "Tiếp tục tải (đã có ${formatBytes(partialBytes)})" else "Tải về")
                }
            }
        }
    }
}

@Composable
private fun StrayCard(
    fileName: String,
    transfer: Transfer?,
    partialBytes: Long,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fileName, style = MaterialTheme.typography.titleSmall)
            if (transfer != null) {
                TransferRow(transfer, onCancel)
            } else {
                Text(
                    "Đã tải dở ${formatBytes(partialBytes)}. Dán lại đúng link đó ở mục \"Thêm mô hình khác\" " +
                        "để tiếp tục, hoặc xoá phần đã tải.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDiscard) { Text("Xoá phần đã tải") }
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: Transfer, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val fraction = transfer.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progressText(transfer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Dừng") }
        }
    }
}
