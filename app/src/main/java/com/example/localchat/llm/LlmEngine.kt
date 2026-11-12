package com.example.localchat.llm

import android.content.Context
import android.os.Build
import com.example.localchat.data.BackendPref
import com.example.localchat.data.ChatSettings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Receives the streamed answer. Methods are called from a native thread. */
interface GenerationListener {
    /** A new piece of the answer and/or of the model's reasoning ("thinking") text. */
    fun onChunk(answer: String, thinking: String)

    /** [error] is null when the answer completed or when the user stopped it. */
    fun onFinished(error: Throwable?)
}

/**
 * Thin wrapper around the LiteRT-LM [Engine] / [Conversation] pair: loads a `.litertlm` model fully
 * on-device, keeps one conversation (chat history lives in the model's KV cache) and streams answers.
 *
 * Loading, resetting and unloading are serialised with a mutex and run off the main thread.
 */
class LlmEngine(context: Context) {

    sealed interface Status {
        data object NoModel : Status
        data class Loading(val modelName: String) : Status
        data class Ready(val modelName: String, val backend: String) : Status
        data class Failed(val message: String) : Status
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _status = MutableStateFlow<Status>(Status.NoModel)
    val status: StateFlow<Status> = _status.asStateFlow()

    // engine / loadedKey are only changed while holding [lock].
    private var engine: Engine? = null
    private var loadedKey: String? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var running: CompletableDeferred<Unit>? = null

    /** True when backend or context size changed since the model was loaded, so it must be reloaded. */
    fun engineSettingsChanged(s: ChatSettings): Boolean {
        val key = loadedKey
        return key != null && key != engineKey(s)
    }

    private fun engineKey(s: ChatSettings) = "${s.backend}|${s.maxTokens}"

    /** Loads [modelFile] and starts a fresh conversation. Never throws: failures end up in [status]. */
    suspend fun load(modelFile: File, displayName: String, settings: ChatSettings) {
        lock.withLock {
            withContext(Dispatchers.Default) {
                stopAndWait()
                closeNative()
                loadedKey = null
                _status.value = Status.Loading(displayName)
                try {
                    check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }) {
                        "Thiết bị không được hỗ trợ (cần CPU 64-bit arm64-v8a)."
                    }
                    val attempts = when (settings.backend) {
                        BackendPref.AUTO -> listOf(BackendPref.GPU, BackendPref.CPU)
                        BackendPref.GPU -> listOf(BackendPref.GPU)
                        BackendPref.CPU -> listOf(BackendPref.CPU)
                    }
                    var failure: Throwable? = null
                    for (pref in attempts) {
                        try {
                            val e = createEngine(modelFile, pref, settings.maxTokens)
                            engine = e
                            conversation = e.createConversation(conversationConfig(settings))
                            loadedKey = engineKey(settings)
                            _status.value = Status.Ready(displayName, pref.label)
                            return@withContext
                        } catch (t: Throwable) {
                            failure = t
                            closeNative()
                        }
                    }
                    throw failure ?: IllegalStateException("Không rõ nguyên nhân.")
                } catch (t: Throwable) {
                    _status.value = Status.Failed(describe(t))
                }
            }
        }
    }

    /** Starts a new chat with the already loaded model (cheap: no model reload). */
    suspend fun resetConversation(settings: ChatSettings) {
        lock.withLock {
            withContext(Dispatchers.Default) {
                val e = engine ?: return@withContext
                stopAndWait()
                runCatching { conversation?.close() }
                conversation = null
                try {
                    conversation = e.createConversation(conversationConfig(settings))
                } catch (t: Throwable) {
                    _status.value = Status.Failed(describe(t))
                }
            }
        }
    }

    /** Frees the model from memory. */
    suspend fun unload() {
        lock.withLock {
            withContext(Dispatchers.Default) {
                stopAndWait()
                closeNative()
                loadedKey = null
                _status.value = Status.NoModel
            }
        }
    }

    /** Called when the screen is gone for good; frees native memory in the background. */
    fun shutdown() {
        scope.launch {
            lock.withLock {
                stopAndWait()
                closeNative()
                loadedKey = null
            }
        }
    }

    /**
     * Sends [text] and streams the answer to [listener].
     * @return false when no conversation is ready or an answer is still being generated.
     */
    fun send(text: String, listener: GenerationListener): Boolean {
        val conv = conversation ?: return false
        val done = CompletableDeferred<Unit>()
        synchronized(this) {
            if (running != null) return false
            running = done
        }

        val finished = AtomicBoolean(false)
        fun finish(error: Throwable?) {
            if (!finished.compareAndSet(false, true)) return
            running = null
            done.complete(Unit)
            listener.onFinished(error)
        }

        try {
            conv.sendMessageAsync(
                text,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val answer = message.toString()
                        val thinking = message.channels.values.joinToString("")
                        if (answer.isNotEmpty() || thinking.isNotEmpty()) listener.onChunk(answer, thinking)
                    }

                    override fun onDone() {
                        finish(null)
                    }

                    override fun onError(throwable: Throwable) {
                        // cancelProcess() ends the stream with a CancellationException: not an error.
                        finish(if (throwable is CancellationException) null else throwable)
                    }
                },
            )
        } catch (t: Throwable) {
            finish(t)
        }
        return true
    }

    /** Stops the answer that is currently being generated (no-op when idle). */
    fun stop() {
        runCatching { conversation?.cancelProcess() }
    }

    private suspend fun stopAndWait() {
        val job = running ?: return
        runCatching { conversation?.cancelProcess() }
        withTimeoutOrNull(8_000) { job.await() }
        if (running === job) running = null // the native side never reported back: do not stay stuck
    }

    private fun closeNative() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }

    private fun createEngine(modelFile: File, pref: BackendPref, maxTokens: Int): Engine {
        val backend: Backend = if (pref == BackendPref.GPU) Backend.GPU() else Backend.CPU()
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            maxNumTokens = maxTokens.takeIf { it > 0 },
            // A writable directory makes the second and later loads faster.
            cacheDir = appContext.cacheDir.absolutePath,
        )
        val e = Engine(config)
        try {
            e.initialize() // can take 10+ seconds: this runs on a background dispatcher
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        return e
    }

    private fun conversationConfig(s: ChatSettings): ConversationConfig {
        val system = s.systemPrompt.trim().takeIf { it.isNotEmpty() }?.let { Contents.of(it) }
        // Without a custom sampler, LiteRT-LM uses the parameters recommended by the model file.
        val sampler = if (s.customSampler) {
            SamplerConfig(
                topK = s.topK,
                topP = s.topP.toDouble(),
                temperature = s.temperature.toDouble(),
                seed = Random.nextInt(1, Int.MAX_VALUE),
            )
        } else {
            null
        }
        return ConversationConfig(systemInstruction = system, samplerConfig = sampler)
    }

    private fun describe(t: Throwable): String = when (t) {
        is UnsatisfiedLinkError ->
            "Không nạp được thư viện native của LiteRT-LM. Thiết bị cần CPU 64-bit (arm64-v8a)."
        is OutOfMemoryError ->
            "Hết bộ nhớ khi nạp mô hình. Hãy chọn mô hình nhỏ hơn hoặc giảm độ dài ngữ cảnh."
        else -> "Không nạp được mô hình: ${t.message ?: t.javaClass.simpleName}"
    }
}
