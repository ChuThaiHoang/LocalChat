package com.example.localchat.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val MODEL_EXT = ".litertlm"
private const val PART_EXT = ".part"
private const val MIN_FREE_BYTES = 200L * 1024 * 1024

/** Keeps only characters that are safe in a file name. */
fun safeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    return base.replace(Regex("[^A-Za-z0-9._()\\- ]"), "_").trim().ifEmpty { "model$MODEL_EXT" }
}

/** Model files live in the app's private storage (`filesDir/models`), so no permission is needed. */
class ModelStore(private val context: Context) {

    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(name: String): File = File(dir, name)

    fun partialFor(name: String): File = File(dir, name + PART_EXT)

    fun installed(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(MODEL_EXT, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Unfinished downloads: file name (without `.part`) -> bytes already downloaded. */
    fun partials(): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(PART_EXT) }?.forEach {
            result[it.name.removeSuffix(PART_EXT)] = it.length()
        }
        return result
    }

    fun partialBytes(name: String): Long = partialFor(name).let { if (it.exists()) it.length() else 0L }

    fun delete(name: String) {
        fileFor(name).delete()
        partialFor(name).delete()
    }

    /**
     * Copies a model picked with the system file picker into private storage.
     * LiteRT-LM needs a real file path, so the content URI cannot be used directly.
     */
    suspend fun importFrom(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver

            var displayName: String? = null
            var total = -1L
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) total = c.getLong(sizeIdx)
                }
            }

            val name = safeFileName(displayName ?: uri.lastPathSegment ?: "model$MODEL_EXT")
            require(name.endsWith(MODEL_EXT, ignoreCase = true)) {
                "Chỉ hỗ trợ tệp có đuôi $MODEL_EXT (định dạng LiteRT-LM)."
            }
            if (total > 0) {
                require(dir.usableSpace > total + MIN_FREE_BYTES) { "Không đủ dung lượng trống để nhập tệp." }
            }

            val part = partialFor(name)
            val target = fileFor(name)
            try {
                val input = resolver.openInputStream(uri) ?: error("Không mở được tệp đã chọn.")
                input.use { src ->
                    part.outputStream().use { dst ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = src.read(buffer)
                            if (n < 0) break
                            dst.write(buffer, 0, n)
                            copied += n
                            val now = System.nanoTime()
                            if (now - lastReport > 200_000_000L) {
                                onProgress(copied, total)
                                lastReport = now
                            }
                        }
                        onProgress(copied, total)
                    }
                }
                if (target.exists()) target.delete()
                check(part.renameTo(target)) { "Không thể lưu tệp mô hình." }
                target
            } catch (t: Throwable) {
                part.delete()
                throw t
            }
        }
}
