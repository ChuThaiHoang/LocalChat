package com.example.localchat.ui

/** A run of inline text that shares one style. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
)

/** Block-level pieces of a chat answer. */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class ListItem(val indent: Int, val marker: String, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Table(val lines: List<String>) : MdBlock
    data object Rule : MdBlock
}

/**
 * A small, forgiving Markdown reader for streamed chat answers. It has no dependencies and uses no
 * Android classes, so it is unit-testable on the JVM.
 *
 * Answers arrive token by token, so half-written Markdown is normal: an unclosed `**`, `` ` `` or code
 * fence is kept as plain text (or as an open code block) instead of failing.
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    /** Characters that can be escaped with a backslash. */
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
    private const val FENCE = "```"

    // ------------------------------------------------------------------ blocks

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        val table = ArrayList<String>()
        val quote = ArrayList<String>()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
            if (table.isNotEmpty()) {
                blocks += MdBlock.Table(table.toList())
                table.clear()
            }
            if (quote.isNotEmpty()) {
                blocks += MdBlock.Quote(parseInline(quote.joinToString("\n")))
                quote.clear()
            }
        }

        // Which buffer is being filled: 0 = none, 1 = paragraph, 2 = table, 3 = quote.
        var open = 0
        fun begin(kind: Int) {
            if (open != kind) {
                flush()
                open = kind
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                trimmed.startsWith(FENCE) -> {
                    begin(0)
                    val language = trimmed.removePrefix(FENCE).trim()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(FENCE)) {
                        code += lines[i]
                        i++
                    }
                    blocks += MdBlock.Code(language, code.joinToString("\n"))
                    // i now points at the closing fence, or past the end of a half-written block.
                }

                trimmed.isEmpty() -> begin(0)

                trimmed.startsWith("|") -> {
                    begin(2)
                    table += trimmed
                }

                else -> {
                    val heading = HEADING.matchEntire(trimmed)
                    val bullet = BULLET.matchEntire(line)
                    val numbered = NUMBERED.matchEntire(line)
                    when {
                        heading != null -> {
                            begin(0)
                            val title = heading.groupValues[2].trim().trimEnd('#').trim()
                            blocks += MdBlock.Heading(heading.groupValues[1].length, parseInline(title))
                        }

                        RULE.matches(trimmed) -> {
                            begin(0)
                            blocks += MdBlock.Rule
                        }

                        trimmed.startsWith(">") -> {
                            begin(3)
                            quote += trimmed.removePrefix(">").trim()
                        }

                        bullet != null -> {
                            begin(0)
                            blocks += MdBlock.ListItem(
                                indent = indentLevel(bullet.groupValues[1]),
                                marker = "•",
                                spans = parseInline(bullet.groupValues[2].trim()),
                            )
                        }

                        numbered != null -> {
                            begin(0)
                            blocks += MdBlock.ListItem(
                                indent = indentLevel(numbered.groupValues[1]),
                                marker = numbered.groupValues[2] + ".",
                                spans = parseInline(numbered.groupValues[3].trim()),
                            )
                        }

                        else -> {
                            begin(1)
                            paragraph += line.trimEnd()
                        }
                    }
                }
            }
            i++
        }
        begin(0) // flush whatever is still buffered
        return blocks
    }

    private fun indentLevel(whitespace: String): Int =
        (whitespace.replace("\t", "    ").length / 2).coerceAtMost(4)

    // ------------------------------------------------------------------ inline

    fun parseInline(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        readInline(text, Style(), out)
        return merge(out)
    }

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    )

    private fun readInline(s: String, style: Style, out: MutableList<MdSpan>) {
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out += MdSpan(buf.toString(), style.bold, style.italic, false, style.strike)
                buf.setLength(0)
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                // Backslash escape: \* shows a literal star.
                c == '\\' && i + 1 < s.length && ESCAPABLE.indexOf(s[i + 1]) >= 0 -> {
                    buf.append(s[i + 1])
                    i += 2
                }

                // Inline code: `code` or ``code with ` inside``.
                c == '`' -> {
                    val run = runLength(s, i, '`')
                    val ticks = "`".repeat(run)
                    val end = s.indexOf(ticks, i + run)
                    if (end > i + run) {
                        flush()
                        out += MdSpan(s.substring(i + run, end).trim(), style.bold, style.italic, true, style.strike)
                        i = end + run
                    } else {
                        buf.append(ticks)
                        i += run
                    }
                }

                // Emphasis: *italic*, **bold**, ***both***, _italic_, __bold__, ~~strike~~.
                c == '*' || c == '_' || c == '~' -> {
                    val run = runLength(s, i, c)
                    val n = when {
                        c == '~' -> if (run == 2) 2 else 0
                        run <= 3 -> run
                        else -> 0
                    }
                    val close = if (n > 0 && canOpen(s, i, c, n)) findClose(s, c, n, i + n) else -1
                    if (close != -1) {
                        flush()
                        val next = when {
                            c == '~' -> style.copy(strike = true)
                            n == 1 -> style.copy(italic = true)
                            n == 2 -> style.copy(bold = true)
                            else -> style.copy(bold = true, italic = true)
                        }
                        readInline(s.substring(i + n, close), next, out)
                        i = close + n
                    } else {
                        buf.append(s, i, i + run)
                        i += run
                    }
                }

                // Link: [label](url) is shown as "label (url)".
                c == '[' -> {
                    val mid = s.indexOf("](", i + 1)
                    val end = if (mid > i) s.indexOf(')', mid + 2) else -1
                    val lineEnd = s.indexOf('\n', i).let { if (it == -1) s.length else it }
                    if (mid > i && end > mid && end < lineEnd) {
                        flush()
                        readInline(s.substring(i + 1, mid), style, out)
                        val url = s.substring(mid + 2, end).trim()
                        if (url.isNotEmpty()) buf.append(" (").append(url).append(')')
                        i = end + 1
                    } else {
                        buf.append(c)
                        i++
                    }
                }

                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
    }

    private fun runLength(s: String, start: Int, c: Char): Int {
        var n = 0
        while (start + n < s.length && s[start + n] == c) n++
        return n
    }

    /** An opening marker must be followed by text; `_` must not start inside a word (snake_case). */
    private fun canOpen(s: String, i: Int, c: Char, n: Int): Boolean {
        val after = s.getOrNull(i + n) ?: return false
        if (after.isWhitespace()) return false
        if (c == '_') {
            val before = s.getOrNull(i - 1)
            if (before != null && before.isLetterOrDigit()) return false
        }
        return true
    }

    /**
     * Finds the marker that closes an emphasis opened with [n] copies of [c], on the same line.
     * Returns its index or -1. Markers inside inline code and escaped markers are skipped.
     */
    private fun findClose(s: String, c: Char, n: Int, from: Int): Int {
        var j = from
        while (j < s.length) {
            val ch = s[j]
            if (ch == '\n') return -1
            if (ch == '\\') {
                j += 2
                continue
            }
            if (ch == '`') {
                val end = s.indexOf('`', j + 1)
                if (end > j) {
                    j = end + 1
                    continue
                }
            }
            if (ch == c) {
                val run = runLength(s, j, c)
                // A single * inside **bold** (or the reverse) belongs to the nested emphasis: skip it.
                val sizeFits = if (n == 1) run == 1 else run >= n
                if (sizeFits && j > from && !s[j - 1].isWhitespace()) {
                    val wordEnd = j + run >= s.length || !s[j + run].isLetterOrDigit()
                    if (c != '_' || wordEnd) return j
                }
                j += run
                continue
            }
            j++
        }
        return -1
    }

    private fun merge(spans: List<MdSpan>): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null &&
                last.bold == span.bold &&
                last.italic == span.italic &&
                last.code == span.code &&
                last.strike == span.strike
            ) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }
}
