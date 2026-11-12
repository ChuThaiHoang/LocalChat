package com.example.localchat.llm

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An [IOException] whose message is already written for the user (Vietnamese). */
class DownloadException(message: String) : IOException(message)

private const val MAX_REDIRECTS = 8
private const val MIN_FREE_BYTES = 200L * 1024 * 1024

/**
 * Resumable HTTPS download of one file, written with plain JDK classes (no extra dependencies).
 *
 * - Redirects are followed by hand so the Hugging Face token is only ever sent to the host of the
 *   original URL and never to the CDN it redirects to.
 * - Data goes to [part] first and is renamed to [target] when complete. If [part] already exists the
 *   download resumes with an HTTP `Range` request.
 * - [cancel] can be called from any thread; it unblocks a pending read right away.
 */
class DownloadTask(
    private val url: String,
    private val part: File,
    private val target: File,
    private val token: String?,
) {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /**
     * Blocking: call it from a background thread.
     * @return true when the file is complete, false when cancelled (the partial file is kept).
     */
    fun run(onProgress: (downloaded: Long, total: Long) -> Unit): Boolean {
        try {
            return download(onProgress, allowRestart = true)
        } catch (e: IOException) {
            if (cancelled) return false
            throw e
        }
    }

    private fun download(onProgress: (Long, Long) -> Unit, allowRestart: Boolean): Boolean {
        var existing = if (part.exists()) part.length() else 0L
        val conn = open(existing)
        try {
            val code = conn.responseCode
            when (code) {
                200 -> existing = 0L // full body: fresh start, or the server ignored our Range header
                206 -> Unit // resuming
                416 -> {
                    // Our partial file does not match what the server has: start over once.
                    part.delete()
                    if (allowRestart) return download(onProgress, allowRestart = false)
                    throw DownloadException("Máy chủ từ chối tiếp tục tải. Hãy thử lại.")
                }
                401, 403 -> throw DownloadException(
                    "Máy chủ từ chối truy cập (HTTP $code). Với mô hình Gemma, hãy chấp nhận điều khoản " +
                        "trên trang mô hình ở Hugging Face và nhập access token ở mục cấu hình.",
                )
                404 -> throw DownloadException("Không tìm thấy tệp (HTTP 404). Hãy kiểm tra lại link.")
                else -> throw DownloadException("Tải thất bại (HTTP $code).")
            }

            val resuming = code == 206
            val total = totalSize(conn, existing, resuming)
            if (total > 0) {
                val needed = if (resuming) total - existing else total
                if ((part.parentFile?.usableSpace ?: Long.MAX_VALUE) < needed + MIN_FREE_BYTES) {
                    throw DownloadException("Không đủ dung lượng trống trên thiết bị.")
                }
            }

            FileOutputStream(part, resuming).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = existing
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) return false
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        val now = System.nanoTime()
                        if (now - lastReport > 250_000_000L) {
                            onProgress(downloaded, total)
                            lastReport = now
                        }
                    }
                    onProgress(downloaded, total)
                    if (total > 0 && downloaded != total) {
                        throw DownloadException("Tệp tải về chưa đầy đủ. Bấm Tải để tiếp tục.")
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw DownloadException("Không thể lưu tệp mô hình.")
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** Total size of the whole file in bytes, or -1 when the server does not say. */
    private fun totalSize(conn: HttpURLConnection, existing: Long, resuming: Boolean): Long {
        if (resuming) {
            // Content-Range: bytes 1000-2999/3000
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            if (total != null) return total
            val len = conn.contentLengthLong
            return if (len >= 0) existing + len else -1L
        }
        return conn.contentLengthLong
    }

    /** Opens the connection, following redirects manually (https only). */
    private fun open(rangeStart: Long): HttpURLConnection {
        var current = URL(url)
        val originHost = current.host
        repeat(MAX_REDIRECTS + 1) {
            if (!current.protocol.equals("https", ignoreCase = true)) {
                throw DownloadException("Chỉ hỗ trợ liên kết https.")
            }
            val conn = current.openConnection() as HttpURLConnection
            connection = conn
            if (cancelled) {
                conn.disconnect()
                throw IOException("cancelled")
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "LocalChatAI/1.0 (Android)")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (!token.isNullOrBlank() && current.host.equals(originHost, ignoreCase = true)) {
                conn.setRequestProperty("Authorization", "Bearer " + token.trim())
            }
            if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")

            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw DownloadException("Máy chủ chuyển hướng không hợp lệ.")
                current = URL(current, location)
            } else {
                return conn
            }
        }
        throw DownloadException("Quá nhiều lần chuyển hướng.")
    }
}
