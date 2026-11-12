package com.example.localchat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Copies [text] to the clipboard. Android 13+ shows its own confirmation; older versions get a toast. */
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Local Chat AI", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show()
    }
}

/** Renders a (possibly half-streamed) Markdown answer using the current content colour. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val textColor = LocalContentColor.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index) { MarkdownBlock(block, textColor) }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, textColor: Color) {
    val typography = MaterialTheme.typography
    val codeBackground = textColor.copy(alpha = 0.12f)

    when (block) {
        is MdBlock.Paragraph ->
            Text(styled(block.spans, codeBackground), style = typography.bodyLarge)

        is MdBlock.Heading -> Text(
            styled(block.spans, codeBackground),
            style = when (block.level) {
                1 -> typography.titleLarge
                2 -> typography.titleMedium
                else -> typography.titleSmall
            },
            fontWeight = FontWeight.Bold,
        )

        is MdBlock.ListItem -> Row(Modifier.padding(start = (12 * block.indent).dp)) {
            Text(
                block.marker,
                style = typography.bodyLarge,
                modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
            )
            Text(
                styled(block.spans, codeBackground),
                style = typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }

        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(textColor.copy(alpha = 0.35f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                styled(block.spans, codeBackground),
                style = typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            )
        }

        is MdBlock.Code -> CodeBlock(block.language, block.text, textColor)

        is MdBlock.Table -> CodeBlock("", block.lines.joinToString("\n"), textColor)

        MdBlock.Rule -> HorizontalDivider(color = textColor.copy(alpha = 0.3f))
    }
}

@Composable
private fun CodeBlock(language: String, code: String, textColor: Color) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.08f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            Text(
                "Sao chép",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { copyToClipboard(context, code) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Text(
            code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
    }
}

private fun styled(spans: List<MdSpan>, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else Color.Unspecified,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
        )
        withStyle(style) { append(span.text) }
    }
}
