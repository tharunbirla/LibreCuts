package com.tharunbirla.librecuts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FFmpegTextEscaperTest {
    @Test
    fun escapeApostropheForDrawtextValue() {
        assertEquals("It\\\\'s\\ a\\ test", FFmpegTextEscaper.escape("It's a test"))
    }
}
