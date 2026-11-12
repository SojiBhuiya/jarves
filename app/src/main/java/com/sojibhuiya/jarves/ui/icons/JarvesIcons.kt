package com.sojibhuiya.jarves.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.sojibhuiya.jarves.ui.theme.JarvesDimens

/**
 * The single icon system for Jarves: Lucide-style monochrome outline vectors.
 *
 * All glyphs are drawn on the same 24×24 grid with the same [JarvesDimens.IconStrokeWidth],
 * round caps and round joins, and they carry no colour of their own — they are tinted by the
 * caller (defaulting to the shared monochrome icon colour in `JarvesIcon`). Emoji and
 * multicoloured assets are intentionally absent.
 */
object JarvesIcons {

    /** menu */
    val Menu: ImageVector = strokeIcon("Jarves.Menu") {
        strokePath {
            // Two lines only, equal length, tight even gap — ChatGPT-style proportions.
            // Two lines only; the top line runs slightly longer, as in ChatGPT's menu glyph.
            moveTo(3.5f, 9f); horizontalLineTo(20.5f)
            moveTo(3.5f, 15f); horizontalLineTo(16f)
        }
    }

    /** search */
    val Search: ImageVector = strokeIcon("Jarves.Search") {
        strokePath {
            moveTo(21f, 21f); lineTo(16.66f, 16.66f)
            moveTo(19f, 11f)
            arcTo(8f, 8f, 0f, true, true, 3f, 11f)
            arcTo(8f, 8f, 0f, true, true, 19f, 11f)
            close()
        }
    }

    /** square-pen — new conversation */
    val SquarePen: ImageVector = strokeIcon("Jarves.SquarePen") {
        strokePath {
            moveTo(12f, 3f); horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, false, false, 3f, 5f)
            verticalLineTo(19f)
            arcTo(2f, 2f, 0f, false, false, 5f, 21f)
            horizontalLineTo(19f)
            arcTo(2f, 2f, 0f, false, false, 21f, 19f)
            verticalLineTo(12f)
            moveTo(18.375f, 2.625f)
            arcTo(2.121f, 2.121f, 0f, true, true, 21.375f, 5.625f)
            lineTo(12f, 15f)
            lineTo(8f, 16f)
            lineTo(9f, 12f)
            close()
        }
    }

    /** history */
    val History: ImageVector = strokeIcon("Jarves.History") {
        strokePath {
            moveTo(3f, 12f)
            arcTo(9f, 9f, 0f, true, false, 12f, 3f)
            arcTo(9.75f, 9.75f, 0f, false, false, 5.26f, 5.74f)
            lineTo(3f, 8f)
            moveTo(3f, 3f); verticalLineTo(8f); horizontalLineTo(8f)
            moveTo(12f, 7f); verticalLineTo(12f); lineTo(16f, 14f)
        }
    }

    /** folder — projects */
    val Folder: ImageVector = strokeIcon("Jarves.Folder") {
        strokePath {
            moveTo(20f, 20f)
            arcTo(2f, 2f, 0f, false, false, 22f, 18f)
            verticalLineTo(8f)
            arcTo(2f, 2f, 0f, false, false, 20f, 6f)
            horizontalLineTo(12.1f)
            arcTo(2f, 2f, 0f, false, true, 10.41f, 5.1f)
            lineTo(9.6f, 3.9f)
            arcTo(2f, 2f, 0f, false, false, 7.93f, 3f)
            horizontalLineTo(4f)
            arcTo(2f, 2f, 0f, false, false, 2f, 5f)
            verticalLineTo(18f)
            arcTo(2f, 2f, 0f, false, false, 4f, 20f)
            close()
        }
    }

    /** puzzle — plugins */
    val Puzzle: ImageVector = strokeIcon("Jarves.Puzzle") {
        strokePath {
            moveTo(4f, 7f)
            horizontalLineTo(9f)
            arcTo(3f, 3f, 0f, false, true, 15f, 7f)
            horizontalLineTo(20f)
            verticalLineTo(9f)
            arcTo(3f, 3f, 0f, false, true, 20f, 15f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            close()
        }
    }

    /** blocks — tools */
    val Blocks: ImageVector = strokeIcon("Jarves.Blocks") {
        strokePath {
            moveTo(10f, 22f); verticalLineTo(7f)
            arcTo(1f, 1f, 0f, false, false, 9f, 6f)
            horizontalLineTo(4f)
            arcTo(1f, 1f, 0f, false, false, 3f, 7f)
            verticalLineTo(22f)
            arcTo(1f, 1f, 0f, false, false, 4f, 23f)
            horizontalLineTo(9f)
            arcTo(1f, 1f, 0f, false, false, 10f, 22f)
            close()
            moveTo(14f, 2f); horizontalLineTo(20f)
            arcTo(1f, 1f, 0f, false, true, 21f, 3f)
            verticalLineTo(9f)
            arcTo(1f, 1f, 0f, false, true, 20f, 10f)
            horizontalLineTo(14f)
            arcTo(1f, 1f, 0f, false, true, 13f, 9f)
            verticalLineTo(3f)
            arcTo(1f, 1f, 0f, false, true, 14f, 2f)
            close()
            moveTo(14f, 16f); horizontalLineTo(20f)
            arcTo(1f, 1f, 0f, false, true, 21f, 17f)
            verticalLineTo(21f)
            arcTo(1f, 1f, 0f, false, true, 20f, 22f)
            horizontalLineTo(14f)
            arcTo(1f, 1f, 0f, false, true, 13f, 21f)
            verticalLineTo(17f)
            arcTo(1f, 1f, 0f, false, true, 14f, 16f)
            close()
        }
    }

    /** user — profile */
    val User: ImageVector = strokeIcon("Jarves.User") {
        strokePath {
            moveTo(19f, 21f); verticalLineTo(19f)
            arcTo(4f, 4f, 0f, false, false, 15f, 15f)
            horizontalLineTo(9f)
            arcTo(4f, 4f, 0f, false, false, 5f, 19f)
            verticalLineTo(21f)
            moveTo(16f, 7f)
            arcTo(4f, 4f, 0f, true, true, 8f, 7f)
            arcTo(4f, 4f, 0f, true, true, 16f, 7f)
            close()
        }
    }

    /** settings (gear) */
    val Settings: ImageVector = strokeIcon("Jarves.Settings") {
        strokePath {
            moveTo(12.22f, 2f); horizontalLineTo(11.78f)
            arcTo(2f, 2f, 0f, false, false, 9.78f, 4f)
            verticalLineTo(4.18f)
            arcTo(2f, 2f, 0f, false, true, 8.78f, 5.91f)
            lineTo(8.35f, 6.16f)
            arcTo(2f, 2f, 0f, false, true, 6.35f, 6.16f)
            lineTo(6.2f, 6.08f)
            arcTo(2f, 2f, 0f, false, false, 3.47f, 6.81f)
            lineTo(3.25f, 7.19f)
            arcTo(2f, 2f, 0f, false, false, 3.98f, 9.92f)
            lineTo(4.13f, 10.02f)
            arcTo(2f, 2f, 0f, false, true, 5.13f, 11.74f)
            verticalLineTo(12.25f)
            arcTo(2f, 2f, 0f, false, true, 4.13f, 13.99f)
            lineTo(3.98f, 14.08f)
            arcTo(2f, 2f, 0f, false, false, 3.25f, 16.81f)
            lineTo(3.47f, 17.19f)
            arcTo(2f, 2f, 0f, false, false, 6.2f, 17.92f)
            lineTo(6.35f, 17.84f)
            arcTo(2f, 2f, 0f, false, true, 8.35f, 17.84f)
            lineTo(8.78f, 18.09f)
            arcTo(2f, 2f, 0f, false, true, 9.78f, 19.82f)
            verticalLineTo(20f)
            arcTo(2f, 2f, 0f, false, false, 11.78f, 22f)
            horizontalLineTo(12.22f)
            arcTo(2f, 2f, 0f, false, false, 14.22f, 20f)
            verticalLineTo(19.82f)
            arcTo(2f, 2f, 0f, false, true, 15.22f, 18.09f)
            lineTo(15.65f, 17.84f)
            arcTo(2f, 2f, 0f, false, true, 17.65f, 17.84f)
            lineTo(17.8f, 17.92f)
            arcTo(2f, 2f, 0f, false, false, 20.53f, 17.19f)
            lineTo(20.75f, 16.81f)
            arcTo(2f, 2f, 0f, false, false, 20.02f, 14.08f)
            lineTo(19.87f, 13.99f)
            arcTo(2f, 2f, 0f, false, true, 18.87f, 12.25f)
            verticalLineTo(11.75f)
            arcTo(2f, 2f, 0f, false, true, 19.87f, 10.01f)
            lineTo(20.02f, 9.92f)
            arcTo(2f, 2f, 0f, false, false, 20.75f, 7.19f)
            lineTo(20.53f, 6.81f)
            arcTo(2f, 2f, 0f, false, false, 17.8f, 6.08f)
            lineTo(17.65f, 6.16f)
            arcTo(2f, 2f, 0f, false, true, 15.65f, 6.16f)
            lineTo(15.22f, 5.91f)
            arcTo(2f, 2f, 0f, false, true, 14.22f, 4.18f)
            verticalLineTo(4f)
            arcTo(2f, 2f, 0f, false, false, 12.22f, 2f)
            close()
            moveTo(15f, 12f)
            arcTo(3f, 3f, 0f, true, true, 9f, 12f)
            arcTo(3f, 3f, 0f, true, true, 15f, 12f)
            close()
        }
    }

    /** plus */
    val Add: ImageVector = strokeIcon("Jarves.Add") {
        strokePath {
            moveTo(12f, 5f); verticalLineTo(19f)
            moveTo(5f, 12f); horizontalLineTo(19f)
        }
    }

    /** x */
    val X: ImageVector = strokeIcon("Jarves.X") {
        strokePath {
            moveTo(18f, 6f); lineTo(6f, 18f)
            moveTo(6f, 6f); lineTo(18f, 18f)
        }
    }

    /** square — stop */
    val Square: ImageVector = strokeIcon("Jarves.Square") {
        strokePath {
            moveTo(5f, 3f); horizontalLineTo(19f)
            arcTo(2f, 2f, 0f, false, true, 21f, 5f)
            verticalLineTo(19f)
            arcTo(2f, 2f, 0f, false, true, 19f, 21f)
            horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, false, true, 3f, 19f)
            verticalLineTo(5f)
            arcTo(2f, 2f, 0f, false, true, 5f, 3f)
            close()
        }
    }

    /** arrow-left */
    val ArrowLeft: ImageVector = strokeIcon("Jarves.ArrowLeft") {
        strokePath {
            moveTo(19f, 12f); horizontalLineTo(5f)
            moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
        }
    }

    /** chevron-right */
    val ChevronRight: ImageVector = strokeIcon("Jarves.ChevronRight") {
        strokePath {
            moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f)
        }
    }

    /** pencil */
    val Pencil: ImageVector = strokeIcon("Jarves.Pencil") {
        strokePath {
            moveTo(12f, 20f); horizontalLineTo(21f)
            moveTo(16.5f, 3.5f)
            arcTo(2.12f, 2.12f, 0f, true, true, 19.5f, 6.5f)
            lineTo(7f, 19f)
            lineTo(3f, 20f)
            lineTo(4f, 16f)
            close()
        }
    }

    /** trash-2 */
    val Trash: ImageVector = strokeIcon("Jarves.Trash") {
        strokePath {
            moveTo(3f, 6f); horizontalLineTo(21f)
            moveTo(19f, 6f); verticalLineTo(20f)
            arcTo(2f, 2f, 0f, false, true, 17f, 22f)
            horizontalLineTo(7f)
            arcTo(2f, 2f, 0f, false, true, 5f, 20f)
            verticalLineTo(6f)
            moveTo(8f, 6f); verticalLineTo(4f)
            arcTo(2f, 2f, 0f, false, true, 10f, 2f)
            horizontalLineTo(14f)
            arcTo(2f, 2f, 0f, false, true, 16f, 4f)
            verticalLineTo(6f)
            moveTo(10f, 11f); verticalLineTo(17f)
            moveTo(14f, 11f); verticalLineTo(17f)
        }
    }

    /** key-round */
    val KeyRound: ImageVector = strokeIcon("Jarves.KeyRound") {
        strokePath {
            moveTo(20.2f, 8f)
            arcTo(4.2f, 4.2f, 0f, true, true, 11.8f, 8f)
            arcTo(4.2f, 4.2f, 0f, true, true, 20.2f, 8f)
            close()
            moveTo(13.05f, 10.95f); lineTo(4f, 20f)
            moveTo(6.5f, 17.5f); lineTo(9f, 20f)
        }
    }

    /** mic */
    val Mic: ImageVector = strokeIcon("Jarves.Mic") {
        strokePath {
            moveTo(12f, 2f)
            arcTo(3f, 3f, 0f, false, true, 15f, 5f)
            verticalLineTo(9f)
            arcTo(3f, 3f, 0f, false, true, 12f, 12f)
            arcTo(3f, 3f, 0f, false, true, 9f, 9f)
            verticalLineTo(5f)
            arcTo(3f, 3f, 0f, false, true, 12f, 2f)
            close()
            moveTo(19f, 10f); verticalLineTo(12f)
            arcTo(7f, 7f, 0f, false, true, 5f, 12f)
            verticalLineTo(10f)
            moveTo(12f, 19f); verticalLineTo(22f)
        }
    }

    /** bot — General AI */
    val Bot: ImageVector = strokeIcon("Jarves.Bot") {
        strokePath {
            moveTo(12f, 8f); verticalLineTo(4f); horizontalLineTo(8f)
            moveTo(6f, 8f); horizontalLineTo(18f)
            arcTo(2f, 2f, 0f, false, true, 20f, 10f)
            verticalLineTo(18f)
            arcTo(2f, 2f, 0f, false, true, 18f, 20f)
            horizontalLineTo(6f)
            arcTo(2f, 2f, 0f, false, true, 4f, 18f)
            verticalLineTo(10f)
            arcTo(2f, 2f, 0f, false, true, 6f, 8f)
            close()
            moveTo(2f, 14f); horizontalLineTo(4f)
            moveTo(20f, 14f); horizontalLineTo(22f)
            moveTo(9f, 13f); verticalLineTo(15f)
            moveTo(15f, 13f); verticalLineTo(15f)
        }
    }

    /** Message bubble — empty-conversation placeholder */
    val MessageCircle: ImageVector = strokeIcon("Jarves.MessageCircle") {
        strokePath {
            moveTo(12f, 21f)
            arcTo(9f, 9f, 0f, true, false, 3f, 12f)
            arcTo(9f, 9f, 0f, false, false, 6.29f, 18.7f)
            lineTo(3f, 21f)
            lineTo(5.3f, 17.71f)
        }
    }
}

private fun strokeIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

/** One stroke style for every icon: round caps/joins, uniform width, no fill. */
private fun ImageVector.Builder.strokePath(block: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = JarvesDimens.IconStrokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}
