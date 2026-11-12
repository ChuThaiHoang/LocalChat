package com.example.localchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localchat.ChatMessage
import com.example.localchat.ChatViewModel
import com.example.localchat.data.BackendPref
import com.example.localchat.llm.LlmEngine

private val SUGGESTIONS = listOf(
    "Giải thích AI chạy local là gì và lợi ích của nó",
    "Viết giúp mình email xin nghỉ phép một ngày",
    "Gợi ý thực đơn cơm nhà 3 ngày cho 2 người",
    "Viết hàm Python kiểm tra số nguyên tố",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    snackbar: SnackbarHostState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val current = status
    val ready = current is LlmEngine.Status.Ready && !busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Local Chat AI", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        StatusLine(current)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newChat() }, enabled = ready) {
                        Icon(Icons.Default.Add, contentDescription = "Cuộc trò chuyện mới")
                    }
                    TextButton(onClick = onOpenModels) { Text("Mô hình") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Cài đặt")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    current is LlmEngine.Status.Loading -> LoadingState(current.modelName)
                    current is LlmEngine.Status.NoModel -> NoModelState(onOpenModels)
                    current is LlmEngine.Status.Failed -> FailedState(
                        message = current.message,
                        canRetryWithCpu = settings.backend != BackendPref.CPU,
                        onRetryWithCpu = { vm.retryWithCpu() },
                        onOpenModels = onOpenModels,
                    )
                    messages.isEmpty() -> WelcomeState(onPick = { vm.send(it) })
                    else -> MessageList(messages)
                }
            }

            InputBar(
                text = draft,
                onTextChange = onDraftChange,
                canSend = ready && !generating && draft.isNotBlank(),
                generating = generating,
                placeholder = when {
                    current is LlmEngine.Status.Loading -> "Đang nạp mô hình…"
                    !ready -> "Chọn mô hình để bắt đầu…"
                    else -> "Nhập tin nhắn…"
                },
                onSend = {
                    vm.send(draft)
                    onDraftChange("")
                },
                onStop = { vm.stop() },
            )
        }
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun StatusLine(status: LlmEngine.Status) {
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when (status) {
        LlmEngine.Status.NoModel -> "Chưa chọn mô hình" to scheme.onSurfaceVariant
        is LlmEngine.Status.Loading -> "Đang nạp ${status.modelName}…" to scheme.onSurfaceVariant
        is LlmEngine.Status.Ready -> "${status.modelName} · ${status.backend} · offline" to scheme.primary
        is LlmEngine.Status.Failed -> "Lỗi khi nạp mô hình" to scheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- states

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        content = content,
    )
}

@Composable
private fun NoModelState(onOpenModels: () -> Unit) {
    CenteredColumn {
        Text("Chưa có mô hình", style = MaterialTheme.typography.titleLarge)
        Text(
            "Hãy tải một mô hình AI về máy (chỉ cần một lần, cần có mạng). " +
                "Sau đó bạn có thể trò chuyện hoàn toàn offline.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenModels) { Text("Chọn / tải mô hình") }
    }
}

@Composable
private fun LoadingState(modelName: String) {
    CenteredColumn {
        CircularProgressIndicator()
        Text("Đang nạp $modelName…", style = MaterialTheme.typography.titleMedium)
        Text(
            "Lần đầu có thể mất vài chục giây. Các lần sau sẽ nhanh hơn.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedState(
    message: String,
    canRetryWithCpu: Boolean,
    onRetryWithCpu: () -> Unit,
    onOpenModels: () -> Unit,
) {
    CenteredColumn {
        Text(
            "Không nạp được mô hình",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(message, textAlign = TextAlign.Center)
        if (canRetryWithCpu) {
            Button(onClick = onRetryWithCpu) { Text("Thử lại bằng CPU") }
        }
        OutlinedButton(onClick = onOpenModels) { Text("Chọn mô hình khác") }
    }
}

@Composable
private fun WelcomeState(onPick: (String) -> Unit) {
    CenteredColumn {
        Text("Xin chào! 👋", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Mình chạy hoàn toàn trên điện thoại của bạn: không cần mạng, không gửi dữ liệu đi đâu cả.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        for (suggestion in SUGGESTIONS) {
            OutlinedCard(onClick = { onPick(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                Text(suggestion, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- messages

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    // The list is reversed (index 0 = newest message, drawn at the bottom). That keeps the view pinned to
    // the end while an answer grows, without touching it when the user has scrolled up to read.
    LaunchedEffect(messages.size) { listState.animateScrollToItem(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages.asReversed(), key = { it.id }) { message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        msg.isError -> scheme.errorContainer
        msg.fromUser -> scheme.primaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        msg.isError -> scheme.onErrorContainer
        msg.fromUser -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (msg.fromUser) 48.dp else 0.dp,
                end = if (msg.fromUser) 0.dp else 24.dp,
            ),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (msg.fromUser) 18.dp else 4.dp,
                bottomEnd = if (msg.fromUser) 4.dp else 18.dp,
            ),
        ) {
            val body: @Composable () -> Unit = {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (msg.thinking.isNotBlank()) ThinkingSection(msg)

                    when {
                        msg.fromUser || msg.isError ->
                            Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                        msg.text.isNotEmpty() -> MarkdownText(msg.text)
                        msg.streaming && msg.thinking.isBlank() -> TypingIndicator()
                    }

                    if (!msg.fromUser && !msg.isError && !msg.streaming && msg.text.isNotBlank()) {
                        Text(
                            "Sao chép",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { copyToClipboard(context, msg.text) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            // Selecting text while it is still changing is fragile, so only finished messages are selectable.
            if (msg.streaming) body() else SelectionContainer { body() }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Đang trả lời…", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Collapsible "thinking" text of reasoning models. Open while the model is still thinking. */
@Composable
private fun ThinkingSection(msg: ChatMessage) {
    var expanded by rememberSaveable(msg.id) { mutableStateOf(false) }
    val live = msg.streaming && msg.text.isEmpty()

    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (live) "Đang suy nghĩ…" else "Suy nghĩ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded || live) {
            Text(
                msg.thinking.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- input

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    generating: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (generating) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
                    Box(Modifier.size(14.dp).background(LocalContentColor.current, RoundedCornerShape(3.dp)))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi")
                }
            }
        }
    }
}
