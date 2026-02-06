package com.schwegelbin.openbible.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * A small colored triangle drawn in the upper-right corner of a verse
 * to indicate that a highlight/note exists.
 * Tapping it opens the highlight detail bottom sheet.
 */
@Composable
fun HighlightAnnotation(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val color = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .size(14.dp)
            .clickable(onClick = onClick)
    ) {
        val path = Path().apply {
            moveTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, 0f)
            close()
        }
        drawPath(path, color)
    }
}
