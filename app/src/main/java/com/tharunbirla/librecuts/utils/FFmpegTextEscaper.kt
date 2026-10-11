package com.tharunbirla.librecuts.utils

/** Escapes text for use as an unquoted drawtext value in a filter graph command. */
object FFmpegTextEscaper {
    fun escape(text: String): String = buildString {
        for (character in text) {
            when (character) {
                '\\' -> append("\\\\\\\\")
                '\'' -> append("\\\\'")
                ':' -> append("\\\\\\\\:")
                ',', ';', '[', ']' -> {
                    append('\\')
                    append(character)
                }
                ' ' -> append("\\ ")
                else -> append(character)
            }
        }
    }
}
