package com.tharunbirla.librecuts.utils

import com.tharunbirla.librecuts.models.SubtitleCue
import java.io.InputStream

object SubtitleParser {

    /**
     * Parses an SRT or WebVTT input stream and returns a list of SubtitleCues.
     */
    fun parse(inputStream: InputStream): List<SubtitleCue> {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parse(content)
    }

    /**
     * Parses SRT or WebVTT content string and returns a list of SubtitleCues.
     */
    fun parse(content: String): List<SubtitleCue> {
        val cleanContent = content.replace("\uFEFF", "").trim()
        val cues = mutableListOf<SubtitleCue>()
        // Split into blocks by double newlines or multiple newlines
        val blocks = cleanContent.split(Regex("(?:\\r?\\n\\s*){2,}"))
        
        for (block in blocks) {
            val lines = block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            
            // Find the line containing the time arrow "-->"
            val arrowIndex = lines.indexOfFirst { it.contains("-->") }
            if (arrowIndex == -1 || arrowIndex + 1 > lines.size) continue
            
            val timeLine = lines[arrowIndex]
            val timeParts = timeLine.split("-->").map { it.trim() }
            if (timeParts.size != 2) continue
            
            val startStr = timeParts[0]
            val endStr = timeParts[1].split(Regex("\\s+"))[0]
            
            val startTimeMs = parseTime(startStr)
            val endTimeMs = parseTime(endStr)
            
            if (startTimeMs != -1L && endTimeMs != -1L) {
                // All lines after the arrow line form the subtitle text
                val text = lines.drop(arrowIndex + 1).joinToString("\n")
                cues.add(SubtitleCue(startTimeMs, endTimeMs, text))
            }
        }
        
        // Sort by start time to ensure correct sequential order
        return cues.sortedBy { it.startTimeMs }
    }

    /**
     * Parses time format: hh:mm:ss,ms or hh:mm:ss.ms or mm:ss.ms
     */
    private fun parseTime(timeStr: String): Long {
        try {
            // Standardize separator to '.'
            val cleaned = timeStr.trim().replace(',', '.')
            val parts = cleaned.split(":")
            
            var hours = 0L
            var minutes = 0L
            var secondsWithMs = ""
            
            when (parts.size) {
                3 -> {
                    hours = parts[0].toLong()
                    minutes = parts[1].toLong()
                    secondsWithMs = parts[2]
                }
                2 -> {
                    minutes = parts[0].toLong()
                    secondsWithMs = parts[1]
                }
                1 -> {
                    secondsWithMs = parts[0]
                }
                else -> return -1L
            }
            
            val secMsParts = secondsWithMs.split(".")
            val seconds = secMsParts[0].toLong()
            val ms = if (secMsParts.size == 2) {
                var msStr = secMsParts[1]
                if (msStr.length > 3) {
                    msStr = msStr.substring(0, 3)
                }
                while (msStr.length < 3) {
                    msStr += "0"
                }
                msStr.toLong()
            } else {
                0L
            }
            
            return hours * 3600000L + minutes * 60000L + seconds * 1000L + ms
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return -1L
    }
}
