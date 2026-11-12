package com.example.localchat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun spans(text: String) = MarkdownParser.parseInline(text)

    @Test
    fun boldAndItalic() {
        val s = spans("a **b** *c*")
        assertEquals(listOf("a ", "b", " ", "c"), s.map { it.text })
        assertTrue(s[1].bold)
        assertTrue(s[3].italic)
    }

    @Test
    fun snakeCaseIsNotItalic() {
        val s = spans("snake_case_word")
        assertEquals(1, s.size)
        assertEquals("snake_case_word", s[0].text)
        assertEquals(false, s[0].italic)
    }

    @Test
    fun unclosedMarkersStayPlainText() {
        assertEquals("**dở dang", spans("**dở dang").joinToString("") { it.text })
        assertEquals("5*3=15 và 2 * 4", spans("5*3=15 và 2 * 4").single().text)
    }

    @Test
    fun inlineCodeAndEscape() {
        val s = spans("dùng `x*y` và \\*sao\\*")
        assertTrue(s.any { it.code && it.text == "x*y" })
        assertEquals("dùng x*y và *sao*", s.joinToString("") { it.text })
    }

    @Test
    fun headingListQuoteRule() {
        val b = MarkdownParser.parse("# Tiêu đề\n- một\n1. hai\n> trích\n---")
        assertTrue(b[0] is MdBlock.Heading)
        assertEquals("•", (b[1] as MdBlock.ListItem).marker)
        assertEquals("1.", (b[2] as MdBlock.ListItem).marker)
        assertTrue(b[3] is MdBlock.Quote)
        assertEquals(MdBlock.Rule, b[4])
    }

    @Test
    fun halfWrittenCodeFenceBecomesOpenCodeBlock() {
        val b = MarkdownParser.parse("```kotlin\nval x = 1")
        val code = b.single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
    }

    @Test
    fun everyStreamingPrefixParses() {
        val text = "# A\n**b** `c`\n```\ncode\n```\n| x |\n- d"
        for (n in 0..text.length) MarkdownParser.parse(text.substring(0, n))
    }
}
