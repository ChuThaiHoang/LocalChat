package com.example.localchat.data

import android.content.Context
import androidx.core.content.edit

const val DEFAULT_SYSTEM_PROMPT =
    "Bạn là một trợ lý AI hữu ích, thân thiện, chạy ngay trên điện thoại của người dùng. " +
        "Hãy trả lời ngắn gọn, chính xác. Mặc định dùng tiếng Việt, trừ khi người dùng viết bằng ngôn ngữ khác."

/** Which inference backend LiteRT-LM should use. */
enum class BackendPref(val label: String) {
    AUTO("Tự động"),
    GPU("GPU"),
    CPU("CPU"),
}

data class ChatSettings(
    val backend: BackendPref = BackendPref.AUTO,
    /** Size of the KV cache (input + output tokens). 0 = let the model decide. */
    val maxTokens: Int = 4096,
    /** When false, the sampler parameters recommended by the model file are used. */
    val customSampler: Boolean = false,
    val temperature: Float = 0.8f,
    val topK: Int = 20,
    val topP: Float = 0.95f,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
)

/** Small SharedPreferences wrapper (private to the app; backups are disabled in the manifest). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("local_chat_settings", Context.MODE_PRIVATE)

    fun load(): ChatSettings {
        val d = ChatSettings()
        val backendName = prefs.getString(K_BACKEND, d.backend.name) ?: d.backend.name
        return ChatSettings(
            backend = runCatching { BackendPref.valueOf(backendName) }.getOrDefault(d.backend),
            maxTokens = prefs.getInt(K_MAX_TOKENS, d.maxTokens),
            customSampler = prefs.getBoolean(K_CUSTOM_SAMPLER, d.customSampler),
            temperature = prefs.getFloat(K_TEMPERATURE, d.temperature),
            topK = prefs.getInt(K_TOP_K, d.topK),
            topP = prefs.getFloat(K_TOP_P, d.topP),
            systemPrompt = prefs.getString(K_SYSTEM_PROMPT, d.systemPrompt) ?: d.systemPrompt,
        )
    }

    fun save(s: ChatSettings) {
        prefs.edit {
            putString(K_BACKEND, s.backend.name)
            putInt(K_MAX_TOKENS, s.maxTokens)
            putBoolean(K_CUSTOM_SAMPLER, s.customSampler)
            putFloat(K_TEMPERATURE, s.temperature)
            putInt(K_TOP_K, s.topK)
            putFloat(K_TOP_P, s.topP)
            putString(K_SYSTEM_PROMPT, s.systemPrompt)
        }
    }

    /** Optional Hugging Face access token, only used to download gated models. */
    var hfToken: String
        get() = prefs.getString(K_HF_TOKEN, "") ?: ""
        set(value) {
            prefs.edit { putString(K_HF_TOKEN, value.trim()) }
        }

    /** File name of the model that was loaded last, so it can be reloaded on the next launch. */
    var lastModel: String?
        get() = prefs.getString(K_LAST_MODEL, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(K_LAST_MODEL) else putString(K_LAST_MODEL, value)
            }
        }

    /**
     * File name of a model whose loading started but never finished (the process died meanwhile,
     * typically from running out of memory). Written synchronously so it survives a native crash.
     */
    var loadInProgress: String?
        get() = prefs.getString(K_LOADING, null)
        set(value) {
            prefs.edit(commit = true) {
                if (value == null) remove(K_LOADING) else putString(K_LOADING, value)
            }
        }

    private companion object {
        const val K_BACKEND = "backend"
        const val K_MAX_TOKENS = "max_tokens"
        const val K_CUSTOM_SAMPLER = "custom_sampler"
        const val K_TEMPERATURE = "temperature"
        const val K_TOP_K = "top_k"
        const val K_TOP_P = "top_p"
        const val K_SYSTEM_PROMPT = "system_prompt"
        const val K_HF_TOKEN = "hf_token"
        const val K_LAST_MODEL = "last_model"
        const val K_LOADING = "load_in_progress"
    }
}
