package com.schwegelbin.openbible.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schwegelbin.openbible.R
import com.schwegelbin.openbible.logic.getPublicRelays
import com.schwegelbin.openbible.logic.nostr.Highlight
import com.schwegelbin.openbible.logic.nostr.HighlightRepository
import com.schwegelbin.openbible.logic.nostr.LocalSigner
import com.schwegelbin.openbible.logic.nostr.RelayPool
import com.schwegelbin.openbible.logic.nostr.db.AppDatabase
import com.schwegelbin.openbible.logic.nostr.embedded.EmbeddedRelay
import com.schwegelbin.openbible.logic.nostr.encodeNevent
import com.schwegelbin.openbible.logic.nostr.getPublicKeyHex
import com.schwegelbin.openbible.logic.nostr.hasKeypair
import com.schwegelbin.openbible.logic.nostr.sync.SyncManager
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
    val isPublishing = remember { mutableStateOf(false) }

    // Load all highlights with published status
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (hasKeypair(context)) {
                    val dao = AppDatabase.getInstance(context).nostrEventDao()
                    val signer = LocalSigner(context)
                    val embeddedRelay = EmbeddedRelay(context)
                    val repo = HighlightRepository(embeddedRelay, signer, dao)
                    highlights.value = repo.getAllHighlights()
                        .sortedByDescending { it.createdAt }
                }
            } catch (_: Exception) { }
        }
    }

    // Reload highlights when publishing completes
    fun reloadHighlights() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dao = AppDatabase.getInstance(context).nostrEventDao()
                    val signer = LocalSigner(context)
                    val embeddedRelay = EmbeddedRelay(context)
                    val repo = HighlightRepository(embeddedRelay, signer, dao)
                    highlights.value = repo.getAllHighlights()
                        .sortedByDescending { it.createdAt }
                } catch (_: Exception) { }
            }
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
        val hl = selectedHighlight.value!!
        ViewHighlightSheet(
            highlight = hl,
            onDismiss = {
                showSheet.value = false
                selectedHighlight.value = null
            },
            onPublish = {
                val relayUrls = getPublicRelays(context).toList()
                if (relayUrls.isEmpty()) {
                    Toast.makeText(context, "No public relays configured", Toast.LENGTH_SHORT).show()
                    return@ViewHighlightSheet
                }

                isPublishing.value = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val dao = AppDatabase.getInstance(context).nostrEventDao()
                            val signer = LocalSigner(context)
                            val embeddedRelay = EmbeddedRelay(context)
                            val relayPool = RelayPool()
                            relayUrls.forEach { relayPool.addRelay(it) }
                            relayPool.connectAll()

                            val syncManager = SyncManager(embeddedRelay, relayPool, dao)
                            val repo = HighlightRepository(embeddedRelay, signer, dao, syncManager)

                            val published = repo.publishHighlight(hl.eventId, relayUrls)

                            withContext(Dispatchers.Main) {
                                if (published.isNotEmpty()) {
                                    Toast.makeText(context, "Published to ${published.size} relay(s)", Toast.LENGTH_SHORT).show()
                                    reloadHighlights()
                                } else {
                                    Toast.makeText(context, "Failed to publish", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isPublishing.value = false
                        }
                    }
                }
                showSheet.value = false
                selectedHighlight.value = null
            },
            onShare = {
                // Generate nevent with relay hints
                val relayHints = hl.publishedRelays.take(3)
                val authorPubkey = getPublicKeyHex(context)
                val nevent = encodeNevent(
                    eventIdHex = hl.eventId,
                    relays = relayHints,
                    authorPubkeyHex = authorPubkey,
                    kind = 9802  // NIP-84 highlight kind
                )

                // Share via Android share sheet
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "nostr:$nevent")
                    putExtra(Intent.EXTRA_TITLE, context.getString(R.string.share_highlight_title))
                }
                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_highlight_title)))

                showSheet.value = false
                selectedHighlight.value = null
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val dao = AppDatabase.getInstance(context).nostrEventDao()
                            val signer = LocalSigner(context)
                            val embeddedRelay = EmbeddedRelay(context)
                            val repo = HighlightRepository(embeddedRelay, signer, dao)
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

            // Status badge with indicator symbol
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator symbol (accessible, not color-dependent)
                Text(
                    text = if (highlight.isPublished) "✓" else "○",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = if (highlight.isPublished) {
                        stringResource(R.string.highlight_published)
                    } else {
                        stringResource(R.string.highlight_local_only)
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
