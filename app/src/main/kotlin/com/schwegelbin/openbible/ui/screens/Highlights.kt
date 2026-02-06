package com.schwegelbin.openbible.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schwegelbin.openbible.R
import com.schwegelbin.openbible.logic.nostr.Highlight
import com.schwegelbin.openbible.logic.nostr.HighlightRepository
import com.schwegelbin.openbible.logic.nostr.LocalSigner
import com.schwegelbin.openbible.logic.nostr.db.AppDatabase
import com.schwegelbin.openbible.logic.nostr.embedded.EmbeddedRelay
import com.schwegelbin.openbible.logic.nostr.embedded.EventStore
import com.schwegelbin.openbible.logic.nostr.hasKeypair
import com.schwegelbin.openbible.logic.nostr.toHighlight
import com.schwegelbin.openbible.ui.components.ViewHighlightSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(onNavigateToRead: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val highlights = remember { mutableStateOf<List<Highlight>>(emptyList()) }
    val selectedHighlight = remember { mutableStateOf<Highlight?>(null) }
    val showSheet = remember { mutableStateOf(false) }

    // Load all highlights
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (hasKeypair(context)) {
                    val dao = AppDatabase.getInstance(context).nostrEventDao()
                    val eventStore = EventStore(dao)
                    val filter = com.schwegelbin.openbible.logic.nostr.NostrFilter(
                        kinds = listOf(9802)
                    )
                    highlights.value = eventStore.queryEvents(filter)
                        .mapNotNull { it.toHighlight() }
                        .sortedByDescending { it.createdAt }
                }
            } catch (_: Exception) { }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.highlights)) },
            navigationIcon = {
                IconButton(onClick = { onNavigateToRead() }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.close))
                }
            }
        )
    }) { innerPadding ->
        if (highlights.value.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.highlight_no_highlights),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(highlights.value) { highlight ->
                    HighlightCard(
                        highlight = highlight,
                        onClick = {
                            selectedHighlight.value = highlight
                            showSheet.value = true
                        }
                    )
                }
            }
        }
    }

    // View highlight bottom sheet
    if (showSheet.value && selectedHighlight.value != null) {
        ViewHighlightSheet(
            highlight = selectedHighlight.value!!,
            onDismiss = {
                showSheet.value = false
                selectedHighlight.value = null
            },
            onPublish = {
                // TODO: Wire up publish to public relays
                showSheet.value = false
            },
            onDelete = {
                val hl = selectedHighlight.value!!
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val signer = LocalSigner(context)
                            val embeddedRelay = EmbeddedRelay(context)
                            val repo = HighlightRepository(embeddedRelay, signer)
                            repo.deleteHighlight(hl.eventId)
                            highlights.value = highlights.value.filter { it.eventId != hl.eventId }
                        } catch (_: Exception) { }
                    }
                }
                showSheet.value = false
                selectedHighlight.value = null
            }
        )
    }
}

@Composable
fun HighlightCard(
    highlight: Highlight,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Reference
            highlight.reference?.let { ref ->
                Text(
                    text = "${ref.book} ${ref.chapter}:${ref.verse}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Highlighted text snippet
            Text(
                text = highlight.highlightedText,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Note preview
            if (!highlight.comment.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = highlight.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status badge
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.highlight_local_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
