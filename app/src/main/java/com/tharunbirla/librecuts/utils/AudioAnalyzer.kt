package com.tharunbirla.librecuts.utils

import android.content.Context
import android.net.Uri
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Log
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioAnalyzer {
    
    /**
     * Extracts PCM audio from the given Uri using FFmpeg and runs a simple energy-based
     * onset (beat) detection algorithm.
     * Returns a list of timestamps (in milliseconds) where beats were detected.
     */
    fun detectBeats(context: Context, audioUri: Uri, onProgress: (Int) -> Unit = {}, onComplete: (List<Long>) -> Unit) {
        val cacheDir = context.cacheDir
        val pcmFile = File(cacheDir, "temp_beat_detect_${System.currentTimeMillis()}.raw")
        
        // We use 8000Hz mono 16-bit to keep the file small and processing fast.
        val inputPath = FFmpegKitConfig.getSafParameterForRead(context, audioUri)
        val cmd = "-y -i \"$inputPath\" -f s16le -ac 1 -ar 8000 \"${pcmFile.absolutePath}\""
        
        FFmpegKit.executeAsync(cmd, { session: FFmpegSession ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                val beats = analyzePcmForBeats(pcmFile)
                pcmFile.delete()
                onComplete(beats)
            } else {
                pcmFile.delete()
                onComplete(emptyList())
            }
        }, { log: Log ->
            // Could parse progress if we wanted
        }, { statistics: Statistics ->
            // Could parse time for progress
        })
    }

    private fun analyzePcmForBeats(pcmFile: File): List<Long> {
        if (!pcmFile.exists()) return emptyList()

        val sampleRate = 8000
        val windowDurationMs = 20
        val samplesPerWindow = (sampleRate * windowDurationMs) / 1000 // 160 samples
        
        val bytes = pcmFile.readBytes()
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val numSamples = shortBuffer.capacity()
        val numWindows = numSamples / samplesPerWindow
        
        val energies = FloatArray(numWindows)
        
        for (i in 0 until numWindows) {
            var sum = 0f
            for (j in 0 until samplesPerWindow) {
                val sample = shortBuffer.get(i * samplesPerWindow + j).toFloat()
                sum += sample * sample
            }
            energies[i] = Math.sqrt((sum / samplesPerWindow).toDouble()).toFloat()
        }
        
        // Simple peak picking with moving average
        val beats = mutableListOf<Long>()
        val historySize = 50 // 1 second of history (50 * 20ms)
        
        // Thresholds
        val minEnergyThreshold = 1000f // Ignore quiet parts completely
        val multiplier = 1.4f // Must be 40% louder than local average
        
        for (i in 0 until numWindows) {
            val currentEnergy = energies[i]
            if (currentEnergy < minEnergyThreshold) continue
            
            // Calculate local moving average
            val startIdx = maxOf(0, i - historySize)
            val endIdx = minOf(numWindows - 1, i + historySize)
            var localSum = 0f
            var count = 0
            for (j in startIdx..endIdx) {
                localSum += energies[j]
                count++
            }
            val localAverage = localSum / count
            
            if (currentEnergy > localAverage * multiplier) {
                // Check if it's a local maximum (peak)
                var isPeak = true
                val checkRange = 3 // 60ms around
                for (j in maxOf(0, i - checkRange)..minOf(numWindows - 1, i + checkRange)) {
                    if (energies[j] > currentEnergy) {
                        isPeak = false
                        break
                    }
                }
                
                if (isPeak) {
                    val timeMs = (i * windowDurationMs).toLong()
                    // Debounce: ensure at least 200ms between beats
                    if (beats.isEmpty() || timeMs - beats.last() > 200) {
                        beats.add(timeMs)
                    }
                }
            }
        }
        
        return beats
    }
}
