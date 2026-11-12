package com.example.localchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localchat.ChatViewModel
import com.example.localchat.data.BackendPref
import com.example.localchat.data.DEFAULT_SYSTEM_PROMPT
import java.util.Locale
import kotlin.math.roundToInt

/** Context sizes offered in the settings. 0 lets the model file decide. */
private val CONTEXT_CHOICES = listOf(2048, 4096, 8192, 16384, 0)

private fun twoDecimals(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)

/**
 * Every change is saved immediately. Backend / context size only take effect when the model is
 * reloaded, and the other options when a new chat starts: both happen when "Áp dụng" is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit, onApply: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Text and sliders keep their own copy so typing and dragging stay smooth; each change is also saved.
    var prompt by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var temperature by remember { mutableFloatStateOf(settings.temperature) }
    var topK by remember { mutableFloatStateOf(settings.topK.toFloat()) }
    var topP by remember { mutableFloatStateOf(settings.topP) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsGroup(
                title = "Bộ xử lý",
                hint = "Tự động: thử GPU trước, nếu lỗi thì chuyển sang CPU. Nếu ứng dụng bị đóng đột ngột " +
                    "khi nạp mô hình, hãy chọn CPU.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pref in BackendPref.entries) {
                        FilterChip(
                            selected = settings.backend == pref,
                            onClick = { vm.updateSettings { it.copy(backend = pref) } },
                            label = { Text(pref.label) },
                        )
                    }
                }
            }

            SettingsGroup(
                title = "Độ dài ngữ cảnh (token)",
                hint = "Ngữ cảnh lớn nhớ được cuộc trò chuyện dài hơn nhưng tốn nhiều RAM hơn. " +
                    "\"Mặc định\" dùng giá trị do tệp mô hình quy định.",
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (size in CONTEXT_CHOICES) {
                        FilterChip(
                            selected = settings.maxTokens == size,
                            onClick = { vm.updateSettings { it.copy(maxTokens = size) } },
                            label = { Text(if (size == 0) "Mặc định" else size.toString()) },
                        )
                    }
                }
            }

            SettingsGroup(
                title = "Cách sinh văn bản",
                hint = "Tắt: dùng thông số do mô hình khuyến nghị (nên để vậy). " +
                    "Bật để tự chỉnh độ sáng tạo.",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tuỳ chỉnh thông số",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.customSampler,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(customSampler = on) } },
                    )
                }
                if (settings.customSampler) {
                    LabeledSlider(
                        label = "Nhiệt độ (càng cao càng sáng tạo)",
                        valueText = twoDecimals(temperature),
                        value = temperature,
                        range = 0f..2f,
                    ) { v ->
                        temperature = v
                        vm.updateSettings { it.copy(temperature = v) }
                    }
                    LabeledSlider(
                        label = "Top-K (số lựa chọn xét mỗi bước)",
                        valueText = topK.roundToInt().toString(),
                        value = topK,
                        range = 1f..100f,
                    ) { v ->
                        topK = v
                        vm.updateSettings { it.copy(topK = v.roundToInt().coerceAtLeast(1)) }
                    }
                    LabeledSlider(
                        label = "Top-P (ngưỡng xác suất tích luỹ)",
                        valueText = twoDecimals(topP),
                        value = topP,
                        range = 0f..1f,
                    ) { v ->
                        topP = v
                        vm.updateSettings { it.copy(topP = v) }
                    }
                }
            }

            SettingsGroup(
                title = "Chỉ dẫn hệ thống (system prompt)",
                hint = "Mô tả vai trò và cách trả lời của AI. Có hiệu lực từ cuộc trò chuyện mới.",
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { text ->
                        prompt = text
                        vm.updateSettings { it.copy(systemPrompt = text) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                )
                TextButton(
                    onClick = {
                        prompt = DEFAULT_SYSTEM_PROMPT
                        vm.updateSettings { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) }
                    },
                ) { Text("Khôi phục mặc định") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                    Text("Áp dụng và bắt đầu cuộc trò chuyện mới")
                }
                Text(
                    "Đổi bộ xử lý hoặc độ dài ngữ cảnh sẽ nạp lại mô hình (có thể mất vài chục giây).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
