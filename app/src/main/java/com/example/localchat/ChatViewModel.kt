package com.example.localchat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localchat.data.BackendPref
import com.example.localchat.data.ChatSettings
import com.example.localchat.data.SettingsStore
import com.example.localchat.llm.CatalogModel
import com.example.localchat.llm.DownloadException
import com.example.localchat.llm.DownloadTask
import com.example.localchat.llm.GenerationListener
import com.example.localchat.llm.LlmEngine
import com.example.localchat.llm.MODEL_EXT
import com.example.localchat.llm.ModelCatalog
import com.example.localchat.llm.ModelStore
import com.example.localchat.llm.safeFileName
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /** Reasoning text streamed by "thinking" models (empty for other models). */
    val thinking: String = "",
    val streaming: Boolean = false,
    val isError: Boolean = false,
)

data class InstalledModel(val fileName: String, val displayName: String, val sizeBytes: Long)

/** Progress of a download or import. [total] is -1 when unknown. */
data class Transfer(val done: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val prefs = SettingsStore(app)
    private val llm = LlmEngine(app)
    private val ids = AtomicLong(1)

    val status: StateFlow<LlmEngine.Status> = llm.status

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** True while the model is being (re)loaded or a new chat is being prepared. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<ChatSettings> = _settings.asStateFlow()

    private val _hfToken = MutableStateFlow(prefs.hfToken)
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _partials = MutableStateFlow<Map<String, Long>>(emptyMap())
    val partials: StateFlow<Map<String, Long>> = _partials.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val downloads: StateFlow<Map<String, Transfer>> = _downloads.asStateFlow()

    private val _importing = MutableStateFlow<Transfer?>(null)
    val importing: StateFlow<Transfer?> = _importing.asStateFlow()

    private val _activeModel = MutableStateFlow<String?>(null)
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    // One-shot messages for the snackbar. A channel buffers them until the UI collects.
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = noticeChannel.receiveAsFlow()

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private var importJob: Job? = null

    init {
        refreshModels()
        val interrupted = prefs.loadInProgress
        if (interrupted != null) {
            // The app died while loading a model (out of memory, or a GPU driver crash): do not retry
            // automatically, and fall back to the safe CPU backend for the next attempt.
            prefs.loadInProgress = null
            if (_settings.value.backend != BackendPref.CPU) {
                updateSettings { it.copy(backend = BackendPref.CPU) }
                notice(
                    "Lần nạp mô hình trước bị dừng đột ngột (thường do GPU không tương thích hoặc thiếu RAM). " +
                        "Mình đã chuyển sang CPU. Vào \"Mô hình\" và chọn lại mô hình để nạp.",
                )
            } else {
                notice(
                    "Lần nạp mô hình trước bị dừng đột ngột (thường do thiếu RAM). Hãy giảm độ dài ngữ cảnh " +
                        "trong Cài đặt hoặc chọn mô hình nhỏ hơn.",
                )
            }
        } else {
            prefs.lastModel?.takeIf { store.fileFor(it).exists() }?.let { loadModel(it) }
        }
    }

    // ---------------------------------------------------------------- chat

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _generating.value || _busy.value) return
        if (status.value !is LlmEngine.Status.Ready) return

        _messages.update {
            it + ChatMessage(ids.getAndIncrement(), fromUser = true, text = text) +
                ChatMessage(ids.getAndIncrement(), fromUser = false, text = "", streaming = true)
        }
        _generating.value = true

        viewModelScope.launch(Dispatchers.Default) {
            val started = llm.send(
                text,
                object : GenerationListener {
                    override fun onChunk(answer: String, thinking: String) {
                        updateLast { it.copy(text = it.text + answer, thinking = it.thinking + thinking) }
                    }

                    override fun onFinished(error: Throwable?) {
                        updateLast { m ->
                            when {
                                error == null -> m.copy(streaming = false)
                                m.text.isBlank() -> m.copy(streaming = false, isError = true, text = friendly(error))
                                else -> m.copy(
                                    streaming = false,
                                    isError = true,
                                    text = m.text + "\n\n⚠️ " + friendly(error),
                                )
                            }
                        }
                        _generating.value = false
                    }
                },
            )
            if (!started) {
                _messages.update { it.dropLast(2) }
                _generating.value = false
                notice("Mô hình chưa sẵn sàng. Hãy thử lại sau giây lát.")
            }
        }
    }

    fun stop() = llm.stop()

    fun newChat() {
        val settings = _settings.value
        val active = _activeModel.value
        _busy.value = true
        viewModelScope.launch {
            try {
                if (active != null && llm.engineSettingsChanged(settings)) {
                    // Backend or context size changed: the model has to be loaded again.
                    prefs.loadInProgress = active
                    llm.load(store.fileFor(active), displayName(active), settings)
                    prefs.loadInProgress = null
                } else {
                    llm.resetConversation(settings)
                }
                _messages.value = emptyList()
                _generating.value = false
            } finally {
                _busy.value = false
            }
        }
    }

    private fun updateLast(transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> if (list.isEmpty()) list else list.dropLast(1) + transform(list.last()) }
    }

    private fun friendly(t: Throwable): String =
        "Lỗi khi tạo câu trả lời: ${t.message ?: t.javaClass.simpleName}. " +
            "Nếu cuộc trò chuyện đã quá dài, hãy bắt đầu cuộc trò chuyện mới."

    // ---------------------------------------------------------------- models

    fun refreshModels() {
        _installed.value = store.installed().map { InstalledModel(it.name, displayName(it.name), it.length()) }
        _partials.value = store.partials()
    }

    private fun displayName(fileName: String): String =
        ModelCatalog.models.firstOrNull { it.fileName == fileName }?.name ?: fileName.removeSuffix(MODEL_EXT)

    fun loadModel(fileName: String) {
        val file = store.fileFor(fileName)
        if (!file.exists()) {
            notice("Không tìm thấy tệp mô hình.")
            return
        }
        _activeModel.value = fileName
        prefs.lastModel = fileName
        _busy.value = true
        viewModelScope.launch {
            try {
                prefs.loadInProgress = fileName
                llm.load(file, displayName(fileName), _settings.value)
                prefs.loadInProgress = null
                _messages.value = emptyList()
                _generating.value = false
            } finally {
                _busy.value = false
            }
        }
    }

    fun retryWithCpu() {
        updateSettings { it.copy(backend = BackendPref.CPU) }
        _activeModel.value?.let { loadModel(it) }
    }

    fun deleteModel(fileName: String) {
        tasks[fileName]?.cancel()
        viewModelScope.launch {
            if (_activeModel.value == fileName) {
                llm.unload()
                _activeModel.value = null
                prefs.lastModel = null
                _messages.value = emptyList()
                _generating.value = false
            }
            withContext(Dispatchers.IO) { store.delete(fileName) }
            refreshModels()
        }
    }

    // ---------------------------------------------------------------- downloads / import

    fun download(model: CatalogModel) = startDownload(model.url, model.fileName)

    fun downloadFromUrl(rawUrl: String) {
        val url = rawUrl.trim()
        val parsed = runCatching { URL(url) }.getOrNull()
        if (parsed == null || !parsed.protocol.equals("https", ignoreCase = true)) {
            notice("Link phải bắt đầu bằng https://")
            return
        }
        val name = safeFileName(parsed.path.substringAfterLast('/'))
        if (!name.endsWith(MODEL_EXT, ignoreCase = true)) {
            notice("Link phải trỏ tới một tệp có đuôi $MODEL_EXT (định dạng LiteRT-LM).")
            return
        }
        startDownload(url, name)
    }

    private fun startDownload(url: String, fileName: String) {
        if (tasks.containsKey(fileName)) return
        val task = DownloadTask(url, store.partialFor(fileName), store.fileFor(fileName), _hfToken.value)
        tasks[fileName] = task
        _downloads.update { it + (fileName to Transfer(store.partialBytes(fileName), -1)) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val finished = task.run { done, total ->
                    _downloads.update { it + (fileName to Transfer(done, total)) }
                }
                if (finished) notice("Đã tải xong ${displayName(fileName)}.")
            } catch (e: DownloadException) {
                notice(e.message ?: "Tải thất bại.")
            } catch (e: IOException) {
                notice("Mất kết nối khi tải. Bấm Tải để tiếp tục từ chỗ đã dừng.")
            } catch (e: Exception) {
                notice("Lỗi khi tải: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                tasks.remove(fileName)
                _downloads.update { it - fileName }
                refreshModels()
            }
        }
    }

    fun cancelDownload(fileName: String) {
        tasks[fileName]?.cancel()
    }

    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            try {
                _importing.value = Transfer(0, -1)
                val file = store.importFrom(uri) { copied, total -> _importing.value = Transfer(copied, total) }
                notice("Đã nhập ${file.name}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Không nhập được tệp: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _importing.value = null
                refreshModels()
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (ChatSettings) -> ChatSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.save(updated)
    }

    fun setToken(token: String) {
        _hfToken.value = token
        prefs.hfToken = token
    }

    private fun notice(message: String) {
        noticeChannel.trySend(message)
    }

    override fun onCleared() {
        tasks.values.forEach { it.cancel() }
        llm.shutdown()
    }
}
