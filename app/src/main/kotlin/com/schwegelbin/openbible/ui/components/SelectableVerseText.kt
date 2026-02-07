package com.schwegelbin.openbible.ui.components

import android.graphics.Typeface
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.schwegelbin.openbible.logic.VerseDisplay
import com.schwegelbin.openbible.logic.nostr.Highlight

private const val MENU_HIGHLIGHT = 100

/**
 * A selectable verse that uses native Android TextView for proper text selection.
 * Adds a "Highlight" action to the text selection floating toolbar.
 *
 * @param verse The verse data to display
 * @param showVerseNumber Whether to prefix the verse number
 * @param highlight The existing highlight for this verse, if any
 * @param textStyle Text style (used for sizing and color)
 * @param hasKeypair Whether the user has Nostr keys (enables Highlight action)
 * @param onHighlight Callback when user selects text and taps "Highlight"
 * @param onAnnotationClick Callback when the corner annotation is tapped
 */
@Composable
fun SelectableVerseText(
    verse: VerseDisplay,
    showVerseNumber: Boolean,
    highlight: Highlight?,
    textStyle: TextStyle,
    hasKeypair: Boolean,
    onHighlight: (selectedText: String) -> Unit,
    onAnnotationClick: () -> Unit = {}
) {
    val hasHighlight = highlight != null
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val fontSize = textStyle.fontSize.value
    val lineHeight = textStyle.lineHeight.value

    val displayText = if (showVerseNumber) {
        "${verse.verseNumber} ${verse.text}".trim()
    } else {
        verse.text
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasHighlight) Modifier.background(highlightColor) else Modifier
            )
            .padding(vertical = 1.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                TextView(context).apply {
                    setTextIsSelectable(true)
                    textSize = fontSize
                    setTextColor(textColor.toArgb())
                    setLineSpacing(0f, lineHeight / fontSize)
                    typeface = Typeface.DEFAULT

                    customSelectionActionModeCallback = object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            if (hasKeypair) {
                                menu.add(Menu.NONE, MENU_HIGHLIGHT, 10, "Highlight")
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            }
                            return true
                        }

                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            return when (item.itemId) {
                                MENU_HIGHLIGHT -> {
                                    val start = selectionStart
                                    val end = selectionEnd
                                    if (start >= 0 && end > start) {
                                        val selected = text.toString().substring(start, end)
                                        onHighlight(selected)
                                    }
                                    mode.finish()
                                    true
                                }
                                else -> false
                            }
                        }

                        override fun onDestroyActionMode(mode: ActionMode) {}
                    }
                }
            },
            update = { textView ->
                textView.text = displayText
                textView.textSize = fontSize
                textView.setTextColor(textColor.toArgb())
            }
        )

        // Corner annotation indicator for existing highlights
        if (hasHighlight) {
            HighlightAnnotation(
                modifier = Modifier.align(Alignment.TopEnd),
                onClick = onAnnotationClick
            )
        }
    }
}
