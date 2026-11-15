package io.github.artcraftandroid.capcraft.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The stroke weight CapCraft draws Lucide's 24-unit icons with: a little lighter than Lucide's 2. */
const val ICON_STROKE = 1.5f

/** A Lucide icon from its SVG path strings (see `LucideIconsTask` in app/build.gradle.kts). */
internal fun lucide(name: String, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (d in paths) {
        b.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = ICON_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

/** A Material Symbol from its filled path strings, on its 960-unit grid (y from -960 to 0). */
internal fun material(name: String, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f)
    b.addGroup(translationY = 960f)
    for (d in paths) b.addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
    b.clearGroup()
    return b.build()
}

/** An icon at [size], tinted. */
@Composable
fun Glyph(image: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, description: String? = null) {
    Icon(image, contentDescription = description, tint = tint, modifier = modifier.size(size))
}
