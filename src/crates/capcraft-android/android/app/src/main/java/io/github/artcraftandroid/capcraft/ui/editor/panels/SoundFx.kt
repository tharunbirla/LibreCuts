package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RATE = 44_100

/** One sound effect of our own: made here, sample by sample, from sines, squares and noise. */
private class SoundFx(val name: String, val category: String, val seconds: Double, val make: (Synth) -> Unit)

/** A mono buffer to draw a sound into, with the few tools every recipe uses. */
private class Synth(seconds: Double) {
    val n = (seconds * RATE).toInt()
    val out = FloatArray(n)
    private val random = java.util.Random(11)

    fun noise(): Float = random.nextFloat() * 2f - 1f

    /** A tone whose pitch moves from [from] to [to] Hz along [curve], shaped by [env], into [start]..[end] seconds. */
    fun tone(
        from: Double,
        to: Double,
        start: Double = 0.0,
        end: Double = n.toDouble() / RATE,
        level: Double = 0.6,
        square: Boolean = false,
        env: (Double) -> Double = { 1.0 },
    ) {
        var phase = 0.0
        val a = (start * RATE).toInt().coerceIn(0, n)
        val b = (end * RATE).toInt().coerceIn(a, n)
        for (i in a until b) {
            val t = (i - a).toDouble() / (b - a).coerceAtLeast(1)
            val f = from * (to / from).pow(t)
            phase += 2 * PI * f / RATE
            val s = if (square) (if (sin(phase) >= 0) 0.6 else -0.6) else sin(phase)
            out[i] += (s * level * env(t)).toFloat()
        }
    }

    /** Noise through a sweeping resonant band, centred from [from] to [to] Hz, shaped by [env]. */
    fun sweep(from: Double, to: Double, level: Double = 0.8, env: (Double) -> Double) {
        var low = 0.0
        var band = 0.0
        val q = 0.35
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = from * (to / from).pow(t)
            val k = 2 * sin(PI * f.coerceAtMost(RATE / 6.0) / RATE)
            val high = noise() - low - q * band
            band += k * high
            low += k * band
            out[i] += (band * level * env(t)).toFloat()
        }
    }

    /** A bell: partials of [pitch] dying away at their own rates, struck at [at] seconds. */
    fun bell(pitch: Double, at: Double = 0.0, level: Double = 0.4, decay: Double = 3.0) {
        val start = (at * RATE).toInt()
        for ((ratio, weight) in listOf(1.0 to 1.0, 2.76 to 0.45, 5.4 to 0.25, 8.93 to 0.12)) {
            for (i in start until n) {
                val t = (i - start).toDouble() / RATE
                out[i] += (sin(2 * PI * pitch * ratio * t) * weight * level * exp(-t * decay * ratio.pow(0.5))).toFloat()
            }
        }
    }
}

private fun decay(rate: Double): (Double) -> Double = { t -> exp(-t * rate) }
private fun swell(t: Double): Double = sin(PI * t).pow(1.5)

private val SOUNDS = listOf(
    SoundFx("Whoosh", "Swooshes", 0.8) { it.sweep(250.0, 3000.0) { t -> swell(t) } },
    SoundFx("Swoosh down", "Swooshes", 0.6) { it.sweep(4000.0, 300.0) { t -> swell(t) } },
    SoundFx("Whip", "Swooshes", 0.3) { it.sweep(600.0, 6000.0, 1.0) { t -> swell(t).pow(2.0) } },
    SoundFx("Riser", "Swooshes", 2.0) { s ->
        s.sweep(200.0, 5000.0, 0.5) { t -> t * t }
        s.tone(150.0, 1200.0, level = 0.25, env = { t -> t * t })
    },
    SoundFx("Fall", "Swooshes", 1.0) { it.tone(1200.0, 80.0, level = 0.5, env = decay(2.5)) },
    SoundFx("Pop", "Pops", 0.15) { it.tone(900.0, 180.0, level = 0.8, env = decay(14.0)) },
    SoundFx("Bubble", "Pops", 0.2) { it.tone(380.0, 1300.0, level = 0.6, env = decay(10.0)) },
    SoundFx("Click", "Pops", 0.06) { s -> for (i in 0 until s.n) s.out[i] = s.noise() * exp(-i / (RATE * 0.004)).toFloat() * 0.8f },
    SoundFx("Tick tock", "Pops", 1.0) { s ->
        s.tone(2600.0, 2600.0, 0.0, 0.03, 0.6, env = decay(30.0))
        s.tone(1900.0, 1900.0, 0.5, 0.53, 0.6, env = decay(30.0))
    },
    SoundFx("Ding", "Bells", 1.6) { it.bell(1046.5) },
    SoundFx("Chime", "Bells", 1.6) { s ->
        s.bell(1046.5, 0.0, 0.3)
        s.bell(1318.5, 0.12, 0.3)
        s.bell(1568.0, 0.24, 0.3)
    },
    SoundFx("Notify", "Bells", 0.6) { s ->
        s.tone(660.0, 660.0, 0.0, 0.18, 0.5, env = decay(6.0))
        s.tone(990.0, 990.0, 0.15, 0.6, 0.5, env = decay(5.0))
    },
    SoundFx("Beep", "Bells", 0.3) { it.tone(1000.0, 1000.0, level = 0.45, env = { t -> if (t > 0.9) (1 - t) * 10 else 1.0 }) },
    SoundFx("Boom", "Booms", 1.5) { s ->
        s.tone(70.0, 32.0, level = 0.9, env = decay(3.0))
        s.sweep(400.0, 80.0, 0.9) { t -> exp(-t * 6.0) }
    },
    SoundFx("Heartbeat", "Booms", 1.2) { s ->
        s.tone(60.0, 45.0, 0.0, 0.25, 0.9, env = decay(10.0))
        s.tone(60.0, 45.0, 0.28, 0.55, 0.7, env = decay(10.0))
    },
    SoundFx("Shutter", "Booms", 0.25) { s ->
        for (i in 0 until s.n) {
            val t = i.toDouble() / RATE
            val a = exp(-t / 0.01) + if (t > 0.09) exp(-(t - 0.09) / 0.015) else 0.0
            s.out[i] = (s.noise() * a * 0.8).toFloat()
        }
    },
    SoundFx("Laser", "Game", 0.4) { it.tone(1600.0, 180.0, level = 0.4, square = true, env = decay(4.0)) },
    SoundFx("Coin", "Game", 0.5) { s ->
        s.tone(1047.0, 1047.0, 0.0, 0.08, 0.35, square = true)
        s.tone(1568.0, 1568.0, 0.08, 0.5, 0.35, square = true, env = decay(5.0))
    },
    SoundFx("Error", "Game", 0.45) { s ->
        s.tone(150.0, 150.0, 0.0, 0.18, 0.45, square = true)
        s.tone(150.0, 150.0, 0.24, 0.42, 0.45, square = true)
    },
    SoundFx("Glitch", "Game", 0.6) { s ->
        // bursts of crushed tones, a new pitch every forty milliseconds
        val step = (RATE * 0.04).toInt()
        val r = java.util.Random(5)
        var i = 0
        while (i < s.n) {
            val f = 200.0 + r.nextInt(1800)
            val on = r.nextFloat() > 0.3f
            for (j in i until minOf(i + step, s.n)) {
                val v = if (on) floor(sin(2 * PI * f * j / RATE) * 4) / 4 else 0.0
                s.out[j] = (v * 0.4).toFloat()
            }
            i += step
        }
    },
    SoundFx("Swish", "Swooshes", 0.25) { it.sweep(2500.0, 7000.0, 0.9) { t -> swell(t) } },
    SoundFx("Wind", "Swooshes", 3.0) { s ->
        s.sweep(300.0, 700.0, 0.5) { t -> swell(t) }
        s.sweep(900.0, 400.0, 0.3) { t -> swell(t) * (0.6 + 0.4 * sin(t * 9.0)) }
    },
    SoundFx("Snap", "Pops", 0.12) { s ->
        for (i in 0 until s.n) s.out[i] = (s.noise() * exp(-i / (RATE * 0.006))).toFloat() * 0.7f
        s.tone(2200.0, 1800.0, level = 0.3, env = decay(40.0))
    },
    SoundFx("Water drop", "Pops", 0.3) { it.tone(500.0, 1800.0, 0.0, 0.08, 0.6, env = { t -> sin(PI * t) }) },
    SoundFx("Typewriter", "Pops", 1.4) { s ->
        // keys at a typist's uneven pace, then the bell at the end of the line
        val r = java.util.Random(3)
        var t = 0.0
        while (t < 1.0) {
            val a = (t * RATE).toInt()
            for (i in a until minOf(a + (RATE * 0.02).toInt(), s.n)) s.out[i] += (s.noise() * exp(-(i - a) / (RATE * 0.003))).toFloat() * 0.7f
            t += 0.07 + r.nextDouble() * 0.06
        }
        s.bell(2100.0, 1.05, 0.25, 6.0)
    },
    SoundFx("Sparkle", "Bells", 1.4) { s ->
        val r = java.util.Random(9)
        for (k in 0 until 9) s.bell(1800.0 + r.nextInt(2400), k * 0.09, 0.12, 6.0)
    },
    SoundFx("Success", "Bells", 1.0) { s ->
        listOf(523.25, 659.25, 783.99, 1046.5).forEachIndexed { i, f -> s.tone(f, f, i * 0.1, 1.0, 0.25, env = decay(4.0)) }
    },
    SoundFx("Doorbell", "Bells", 1.6) { s ->
        s.bell(659.25, 0.0, 0.35, 2.5)
        s.bell(523.25, 0.45, 0.35, 2.0)
    },
    SoundFx("Thud", "Booms", 0.5) { s ->
        s.tone(110.0, 50.0, level = 0.9, env = decay(9.0))
        s.sweep(300.0, 120.0, 0.6) { t -> exp(-t * 14.0) }
    },
    SoundFx("Impact", "Booms", 1.4) { s ->
        s.tone(60.0, 30.0, level = 0.9, env = decay(3.5))
        for (i in 0 until s.n) s.out[i] += (s.noise() * exp(-i / (RATE * 0.05))).toFloat() * 0.7f
    },
    SoundFx("Drum roll", "Booms", 2.0) { s ->
        // hits ever closer and louder, to a crash at the end
        var t = 0.0
        var gap = 0.09
        while (t < 1.7) {
            val a = (t * RATE).toInt()
            val loud = 0.3 + 0.5 * t / 1.7
            for (i in a until minOf(a + (RATE * 0.05).toInt(), s.n)) s.out[i] += (s.noise() * exp(-(i - a) / (RATE * 0.012)) * loud).toFloat()
            t += gap
            gap = maxOf(0.035, gap * 0.95)
        }
        val crash = (1.75 * RATE).toInt()
        for (i in crash until s.n) s.out[i] += (s.noise() * exp(-(i - crash) / (RATE * 0.12))).toFloat() * 0.8f
    },
    SoundFx("Jump", "Game", 0.3) { it.tone(300.0, 900.0, level = 0.35, square = true, env = decay(3.0)) },
    SoundFx("Power up", "Game", 0.8) { s ->
        listOf(392.0, 523.25, 659.25, 783.99, 1046.5, 1318.5).forEachIndexed { i, f -> s.tone(f, f, i * 0.1, i * 0.1 + 0.12, 0.3, square = true) }
    },
    SoundFx("Boing", "Cartoon", 0.7) { s ->
        var phase = 0.0
        for (i in 0 until s.n) {
            val t = i.toDouble() / s.n
            val f = 180.0 + 120.0 * sin(t * 40.0) * exp(-t * 4.0) + 140.0 * t
            phase += 2 * PI * f / RATE
            s.out[i] += (sin(phase) * 0.6 * exp(-t * 3.0)).toFloat()
        }
    },
    SoundFx("Slide whistle", "Cartoon", 1.0) { s ->
        var phase = 0.0
        for (i in 0 until s.n) {
            val t = i.toDouble() / s.n
            val f = 600.0 + 900.0 * sin(PI * t)
            phase += 2 * PI * f / RATE
            s.out[i] += (sin(phase) * 0.45 * swell(t)).toFloat()
        }
    },
    SoundFx("Zap", "Cartoon", 0.35) { s ->
        s.tone(3000.0, 200.0, level = 0.35, square = true, env = decay(6.0))
        s.sweep(5000.0, 500.0, 0.4) { t -> exp(-t * 5.0) }
    },
    SoundFx("Honk", "Cartoon", 0.5) { s ->
        s.tone(380.0, 350.0, level = 0.35, square = true, env = { t -> if (t < 0.1) t * 10 else exp(-(t - 0.1) * 3.0) })
        s.tone(570.0, 525.0, level = 0.2, square = true, env = { t -> if (t < 0.1) t * 10 else exp(-(t - 0.1) * 3.0) })
    },
    SoundFx("Bonk", "Cartoon", 0.3) { s ->
        s.tone(820.0, 760.0, level = 0.7, env = decay(18.0))
        for (i in 0 until s.n) s.out[i] += (s.noise() * exp(-i / (RATE * 0.004))).toFloat() * 0.4f
    },
    SoundFx("Squeak", "Cartoon", 0.4) { s ->
        s.tone(1400.0, 3200.0, 0.0, 0.15, 0.35, env = { t -> sin(PI * t) })
        s.tone(1600.0, 3600.0, 0.2, 0.36, 0.35, env = { t -> sin(PI * t) })
    },
    SoundFx("Cork pop", "Cartoon", 0.5) { s ->
        for (i in 0 until s.n) s.out[i] += (s.noise() * exp(-i / (RATE * 0.008))).toFloat() * 0.8f
        s.tone(300.0, 1200.0, 0.02, 0.2, 0.4, env = decay(6.0))
    },
    // between shots
    SoundFx("Tape stop", "Transitions", 1.0) { s ->
        // a chord running down as a tape slows to a halt
        for (f in listOf(220.0, 277.0, 330.0)) s.tone(f, f / 12.0, level = 0.25, env = { t -> 1.0 - t * t })
    },
    SoundFx("Rewind", "Transitions", 0.9) { s ->
        var phase = 0.0
        for (i in 0 until s.n) {
            val t = i.toDouble() / s.n
            val f = 900.0 + 700.0 * sin(t * 2 * PI * 7.0)
            phase += 2 * PI * f / RATE
            s.out[i] += (sin(phase) * 0.25 * swell(t)).toFloat()
        }
        s.sweep(3000.0, 1500.0, 0.3) { t -> swell(t) * (0.5 + 0.5 * sin(t * 2 * PI * 7.0)) }
    },
    SoundFx("Camera flash", "Transitions", 0.8) { s ->
        // the charge whining up, then the flash
        s.tone(1800.0, 7000.0, 0.0, 0.6, 0.12, env = { t -> t })
        val at = (0.62 * RATE).toInt()
        for (i in at until s.n) s.out[i] += (s.noise() * exp(-(i - at) / (RATE * 0.01))).toFloat() * 0.8f
    },
    SoundFx("Reverse swoosh", "Transitions", 0.9) { it.sweep(300.0, 4000.0, 0.9) { t -> t.pow(3.0) * (if (t > 0.97) (1 - t) / 0.03 else 1.0) } },
    // outdoors
    SoundFx("Rain", "Nature", 4.0) { s ->
        s.sweep(1500.0, 1800.0, 0.35) { t -> minOf(t * 8, 1.0, (1 - t) * 8) }
        val r = java.util.Random(13)
        repeat(60) {
            val a = (r.nextDouble() * (s.n - RATE * 0.02)).toInt()
            for (i in a until a + (RATE * 0.01).toInt()) s.out[i] += (s.noise() * exp(-(i - a) / (RATE * 0.002))).toFloat() * 0.3f
        }
    },
    SoundFx("Birds", "Nature", 3.0) { s ->
        val r = java.util.Random(17)
        var t = 0.1
        while (t < 2.7) {
            val f = 2600.0 + r.nextInt(1600)
            s.tone(f, f * 1.4, t, t + 0.08, 0.25, env = { u -> sin(PI * u) })
            s.tone(f * 1.2, f, t + 0.1, t + 0.17, 0.2, env = { u -> sin(PI * u) })
            t += 0.3 + r.nextDouble() * 0.5
        }
    },
    SoundFx("Crickets", "Nature", 3.0) { s ->
        // trains of quick chirps, a breath between
        var t = 0.0
        while (t < 2.8) {
            for (k in 0 until 4) s.tone(4600.0, 4600.0, t + k * 0.03, t + k * 0.03 + 0.02, 0.2, env = { u -> sin(PI * u) })
            t += 0.45
        }
    },
    SoundFx("Thunder", "Nature", 3.5) { s ->
        for (i in 0 until s.n) s.out[i] += (s.noise() * exp(-i / (RATE * 0.03))).toFloat() * 0.9f
        s.sweep(200.0, 50.0, 1.0) { t -> exp(-t * 1.5) * (0.7 + 0.3 * sin(t * 23.0)) }
        s.tone(45.0, 30.0, level = 0.6, env = decay(1.2))
    },
    // dread
    SoundFx("Drone", "Scary", 4.0) { s ->
        s.tone(55.0, 55.0, level = 0.35, env = { t -> swell(t) })
        s.tone(57.5, 57.5, level = 0.35, env = { t -> swell(t) })
        s.tone(82.5, 80.0, level = 0.2, env = { t -> swell(t) })
    },
    SoundFx("Stinger", "Scary", 1.8) { s ->
        // a clashing chord struck hard and left to ring
        for (f in listOf(233.1, 329.6, 466.2, 659.3)) s.tone(f, f * 0.98, level = 0.2, square = true, env = decay(2.0))
        for (i in 0 until s.n) s.out[i] += (s.noise() * exp(-i / (RATE * 0.03))).toFloat() * 0.5f
    },
    SoundFx("Creak", "Scary", 1.5) { s ->
        var phase = 0.0
        val r = java.util.Random(21)
        for (i in 0 until s.n) {
            val t = i.toDouble() / s.n
            val f = 300.0 + 180.0 * sin(t * PI) + 30.0 * sin(t * 50.0)
            phase += 2 * PI * f / RATE
            // a grainy rasp, the pitch catching as the hinge turns
            val grain = if (r.nextFloat() > 0.6f) 1.0 else 0.4
            s.out[i] += (sin(phase) * 0.35 * grain * swell(t)).toFloat()
        }
    },
    SoundFx("Ghost", "Scary", 2.0) { s ->
        var phase = 0.0
        for (i in 0 until s.n) {
            val t = i.toDouble() / s.n
            val f = 500.0 + 250.0 * sin(t * PI) + 25.0 * sin(t * 60.0)
            phase += 2 * PI * f / RATE
            s.out[i] += (sin(phase) * 0.3 * swell(t)).toFloat()
        }
        s.sweep(800.0, 300.0, 0.25) { t -> swell(t) }
    },
    // screens and gadgets
    SoundFx("Keyboard", "Tech", 1.5) { s ->
        val r = java.util.Random(23)
        var t = 0.0
        while (t < 1.4) {
            val a = (t * RATE).toInt()
            for (i in a until minOf(a + (RATE * 0.015).toInt(), s.n)) s.out[i] += (s.noise() * exp(-(i - a) / (RATE * 0.002))).toFloat() * 0.45f
            t += 0.08 + r.nextDouble() * 0.08
        }
    },
    SoundFx("Phone buzz", "Tech", 1.2) { s ->
        s.tone(160.0, 160.0, 0.0, 0.4, 0.4, square = true)
        s.tone(160.0, 160.0, 0.6, 1.0, 0.4, square = true)
    },
    SoundFx("Scan", "Tech", 0.8) { s ->
        s.tone(800.0, 2400.0, 0.0, 0.3, 0.3, env = { t -> sin(PI * t) })
        s.tone(800.0, 2400.0, 0.4, 0.7, 0.3, env = { t -> sin(PI * t) })
    },
    SoundFx("Shutdown", "Tech", 1.0) { s ->
        listOf(1046.5, 783.99, 523.25, 392.0).forEachIndexed { i, f -> s.tone(f, f, i * 0.18, i * 0.18 + 0.2, 0.3, square = true, env = decay(3.0)) }
    },
)

/** The sound effects the search sheet lists as trending, by name. */
internal val TRENDING_SOUNDS = listOf("Whoosh", "Pop", "Ding", "Boom")

/** How long the sound effect [name] runs, in seconds, or null when there is none by that name. */
internal fun soundFxSeconds(name: String): Double? = SOUNDS.firstOrNull { it.name == name }?.seconds

/** Makes the sound effect [name] (once per project) and puts it on the timeline at the playhead. */
internal fun addSoundFx(vm: EditorViewModel, name: String, scope: kotlinx.coroutines.CoroutineScope) {
    val fx = SOUNDS.firstOrNull { it.name == name } ?: return
    scope.launch {
        val file = withContext(Threads.thumbnails) {
            runCatching {
                val dir = File(vm.path, "media").apply { mkdirs() }
                File(dir, "${fx.name}.wav").also { if (!it.exists()) writeWav(it, render(fx)) }
            }.getOrNull()
        }
        if (file == null) vm.message = "Could not make that sound" else placeFx(vm, file, fx.name)
    }
}

/** A sound effect's file put at the playhead, its clip named for the sound rather than the file. */
private fun placeFx(vm: EditorViewModel, file: File, name: String) {
    vm.addRecording(file, vm.playhead) { id -> vm.patch(id, org.json.JSONObject().put("name", name)) }
}

/** The samples of [fx], gently limited so a stack of tones never clips. */
private fun render(fx: SoundFx): ShortArray {
    val s = Synth(fx.seconds)
    fx.make(s)
    // a few milliseconds of fade at each end, so no sound starts or stops with a click
    val edge = (RATE * 0.004).toInt()
    return ShortArray(s.n) { i ->
        val fade = minOf(1.0, i.toDouble() / edge, (s.n - 1 - i).toDouble() / edge)
        (tanh(s.out[i].toDouble()) * fade * 32767 * 0.9).toInt().toShort()
    }
}

/** Writes 16-bit mono PCM as a WAV file. */
private fun writeWav(file: File, pcm: ShortArray) {
    RandomAccessFile(file, "rw").use { f ->
        f.setLength(0)
        val bytes = pcm.size * 2
        fun intLe(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun shortLe(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        f.write("RIFF".toByteArray()); intLe(36 + bytes); f.write("WAVE".toByteArray())
        f.write("fmt ".toByteArray()); intLe(16); shortLe(1); shortLe(1); intLe(RATE); intLe(RATE * 2); shortLe(2); shortLe(16)
        f.write("data".toByteArray()); intLe(bytes)
        val buf = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { buf.putShort(it) }
        f.write(buf.array())
    }
}

/**
 * Sound effects of our own, browsed as the reference's library is: a search field in the bar,
 * categories under it, one row a sound with its length. A tap plays it; the plus puts it on an
 * audio row at the playhead.
 */
@Composable
fun SoundFxPanel(vm: EditorViewModel) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<String?>(null) }
    val track = remember { arrayOfNulls<AudioTrack>(1) }
    DisposableEffect(Unit) { onDispose { track[0]?.release(); track[0] = null } }
    val categories = remember { listOf("All") + SOUNDS.map { it.category }.distinct() }
    val shown = when {
        query.isNotBlank() -> SOUNDS.filter { it.name.contains(query.trim(), ignoreCase = true) }
        category == 0 -> SOUNDS
        else -> SOUNDS.filter { it.category == categories[category] }
    }

    fun preview(fx: SoundFx) {
        scope.launch {
            val pcm = withContext(Threads.thumbnails) { render(fx) }
            track[0]?.release()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track[0] = t
            playing = fx.name
        }
    }

    fun add(fx: SoundFx) {
        scope.launch {
            val file = withContext(Threads.thumbnails) {
                runCatching {
                    val dir = File(vm.path, "media").apply { mkdirs() }
                    File(dir, "${fx.name}.wav").also { if (!it.exists()) writeWav(it, render(fx)) }
                }.getOrNull()
            }
            if (file == null) vm.message = "Could not make that sound" else placeFx(vm, file, fx.name)
        }
    }

    PanelFrame("Sound FX", vm, tall = true, titleOnTop = true, header = { SearchField(query, { query = it }, "Search sound effects") }) {
        Tabs(categories, if (query.isBlank()) category else -1) {
            category = it
            query = ""
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Timeline))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.name }) { fx ->
                val on = playing == fx.name
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { preview(fx) }.padding(start = 18.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(if (on) LucideIcons.AudioLines else LucideIcons.Music, if (on) Palette.Accent else Palette.TextSecondary, size = 18.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        fx.name,
                        style = Type.Body.copy(fontSize = 15.sp, color = if (on) Palette.Accent else Palette.Text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("00:%02d".format(kotlin.math.ceil(fx.seconds).toInt()), style = Type.Small.copy(fontSize = 11.sp, color = Palette.TextSecondary))
                    Spacer(Modifier.width(14.dp))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF3A3A3A)).clickable { add(fx) },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.Plus, Palette.Text, size = 18.dp, description = "Add ${fx.name}") }
                }
            }
        }
    }
}
