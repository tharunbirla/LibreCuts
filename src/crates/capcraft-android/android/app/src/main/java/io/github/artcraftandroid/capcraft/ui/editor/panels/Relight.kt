package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

private const val RELIGHT = "capcraft.relight"

/**
 * A look of the relight package: its key and fill lights as 0xRRGGBB, where the key comes from
 * (degrees round from the left, clockwise), and how strong, soft and spotted it is.
 */
private class Light(
    val name: String,
    val key: Long,
    val fill: Long,
    val angle: Int,
    val strength: Int,
    val softness: Int,
    val spot: Int,
)

/** The looks under each tab, our own. */
private val LIGHTS = listOf(
    "Facial" to listOf(
        Light("Warm glow", 0xFFD9A8, 0x6E7F99, -40, 55, 60, 40),
        Light("Soft light", 0xFFFFFF, 0x9AA0A8, -90, 40, 90, 60),
        Light("Window", 0xF2F6FF, 0x4A5568, 0, 65, 35, 10),
        Light("Morning sun", 0xFFE2A0, 0x7A8CA8, -135, 60, 50, 30),
        Light("Studio", 0xFFFFFF, 0x5A5F66, -60, 50, 45, 50),
    ),
    "Ambient" to listOf(
        Light("Golden hour", 0xFFB86B, 0x8A6E5A, -150, 60, 70, 0),
        Light("Blue hour", 0x9DB8FF, 0x3C4A78, -60, 55, 70, 0),
        Light("Candle", 0xFFB070, 0x3A2A20, 30, 65, 40, 20),
        Light("Overcast", 0xE8ECF2, 0xA8B0BC, -90, 35, 100, 0),
        Light("Moonlight", 0xB8C8FF, 0x1E2640, -120, 60, 55, 0),
    ),
    "Creative" to listOf(
        Light("Neon duo", 0xFF3DCB, 0x3D6BFF, 0, 70, 30, 0),
        Light("Sunset duo", 0xFF8A3D, 0x8A3DFF, -180, 65, 40, 0),
        Light("Teal & orange", 0xFFA040, 0x2FB8B0, -30, 60, 35, 0),
        Light("Club", 0xFF2D55, 0x2DFFD0, 90, 70, 25, 20),
        Light("Lime & violet", 0xB6FF3D, 0x7A3DFF, -150, 65, 30, 0),
    ),
)

/** A light's colour as the package keeps a colour knob: 0xRRGGBBAA, opaque. */
private fun knob(rgb: Long): Long = (rgb shl 8) or 0xFF

/**
 * Relight: None, then Facial, Ambient and Creative looks of our own, each lighting the picture again
 * with a key light from one side and a fill from the other; the chosen one's strength under them.
 */
@Composable
fun RelightPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val link: JSONObject? = remember(clip.json) {
        vm.clipJson(clip.id)?.optJSONArray("videoEffects")?.let { chain ->
            (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == RELIGHT }
        }
    }
    val params = link?.optJSONObject("params")
    val current = params?.let { p ->
        LIGHTS.flatMap { it.second }.firstOrNull { l ->
            p.optLong("key") == knob(l.key) && p.optLong("fill") == knob(l.fill) && p.optInt("angle") == l.angle
        }
    }
    var tab by remember { mutableIntStateOf(LIGHTS.indexOfFirst { (_, looks) -> current in looks }.coerceAtLeast(0)) }
    var strength by remember(clip.id, current?.name) { mutableFloatStateOf(params?.optDouble("strength", 60.0)?.toFloat() ?: 60f) }

    /** The clip's chain with the relight set to [light] at [power] per cent, or taken off (null). */
    fun set(light: Light?, power: Float?, gesture: String?) {
        val chain = vm.clipJson(clip.id)?.optJSONArray("videoEffects") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until chain.length()) {
            val l = chain.getJSONObject(i)
            if (l.optString("id") != RELIGHT) next.put(l)
        }
        if (light != null) {
            next.put(
                JSONObject().put("id", RELIGHT).put(
                    "params",
                    JSONObject().put("key", knob(light.key)).put("fill", knob(light.fill)).put("angle", light.angle)
                        .put("strength", (power ?: light.strength.toFloat()).roundToInt()).put("softness", light.softness).put("spot", light.spot),
                ),
            )
        }
        vm.patch(clip.id, JSONObject().put("videoEffects", next), gesture)
    }

    PanelFrame("Relight", vm, height = 220.dp, titleOnTop = true, header = {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { set(null, null, null) }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Ban, if (current == null) Palette.Text else Palette.TextSecondary, size = 20.dp, description = "None")
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                LIGHTS.forEachIndexed { i, (name, _) ->
                    Text(
                        name,
                        style = Type.Body.copy(
                            fontSize = 15.sp,
                            color = if (i == tab) Palette.Text else Palette.TextSecondary,
                            fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        modifier = Modifier.clickable { tab = i }.padding(horizontal = 11.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }) {
        Column(Modifier.fillMaxWidth()) {
            if (current != null) {
                ValueSlider(
                    value = strength,
                    range = 0f..100f,
                    label = { "${it.roundToInt()}" },
                    onChange = {
                        strength = it
                        set(current, it, "relight-strength")
                    },
                    onDone = { vm.endGesture() },
                    filled = true,
                )
            } else {
                Spacer(Modifier.height(52.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                LIGHTS[tab].second.forEach { light ->
                    val on = light === current
                    Column(Modifier.width(72.dp).clickable { set(light, null, null) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(60.dp).clip(RoundedCornerShape(6.dp))
                                .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
                        ) { LightSample(light) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            light.name,
                            style = Type.Tiny.copy(color = if (on) Palette.Text else Palette.TextSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A light's sample: a head and shoulders in grey, lit from the key's side in its colour and from the far side in the fill's. */
@Composable
private fun LightSample(light: Light) {
    val key = Color(0xFF000000 or light.key)
    val fill = Color(0xFF000000 or light.fill)
    Canvas(Modifier.fillMaxSize().background(Color(0xFF555555))) {
        val w = size.width
        val h = size.height
        // the figure
        val skin = Color(0xFFE2E2E2)
        drawCircle(skin, w * 0.2f, Offset(w * 0.5f, h * 0.4f))
        drawRoundRect(skin, Offset(w * 0.18f, h * 0.66f), Size(w * 0.64f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.25f))
        // the light across it, from the key's side to the fill's, multiplied in
        val a = Math.toRadians(light.angle.toDouble())
        val from = Offset(w * 0.5f - (cos(a) * w * 0.5f).toFloat(), h * 0.5f - (sin(a) * h * 0.5f).toFloat())
        val to = Offset(w - from.x, h - from.y)
        drawRect(Brush.linearGradient(listOf(key, fill), from, to), blendMode = BlendMode.Multiply)
        if (light.spot > 0) {
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = light.spot / 160f), Color.Transparent), Offset(w * 0.5f, h * 0.4f), w * 0.4f),
                w * 0.4f,
                Offset(w * 0.5f, h * 0.4f),
                blendMode = BlendMode.Screen,
            )
        }
    }
}
