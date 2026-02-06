package com.schwegelbin.openbible.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.schwegelbin.openbible.logic.VerseDisplay
import com.schwegelbin.openbible.logic.nostr.Highlight

/**
 * A single verse rendered with optional highlight background and corner annotation.
 *
 * @param verse The verse data to display
 * @param showVerseNumber Whether to prefix the verse number
 * @param highlight The highlight for this verse, if any
 * @param textStyle Text style for the verse content
 * @param onAnnotationClick Callback when the corner annotation is tapped
 */
@Composable
fun VerseText(
    verse: VerseDisplay,
    showVerseNumber: Boolean,
    highlight: Highlight?,
    textStyle: TextStyle,
    onAnnotationClick: () -> Unit = {}
) {
    val hasHighlight = highlight != null
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        val text = if (showVerseNumber) {
            "${verse.verseNumber} ${verse.text}".trim()
        } else {
            verse.text
        }

        Text(
            text = text,
            style = textStyle,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasHighlight) Modifier.background(highlightColor) else Modifier
                )
                .padding(vertical = 1.dp)
        )

        // Corner annotation indicator
        if (hasHighlight) {
            HighlightAnnotation(
                modifier = Modifier.align(Alignment.TopEnd),
                onClick = onAnnotationClick
            )
        }
    }
}
