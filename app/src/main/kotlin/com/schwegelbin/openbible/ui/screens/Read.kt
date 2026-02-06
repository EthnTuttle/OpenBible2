package com.schwegelbin.openbible.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schwegelbin.openbible.R
import com.schwegelbin.openbible.logic.ReadTextAlignment
import com.schwegelbin.openbible.logic.SplitScreen
import com.schwegelbin.openbible.logic.VerseDisplay
import com.schwegelbin.openbible.logic.checkTranslation
import com.schwegelbin.openbible.logic.getAppName
import com.schwegelbin.openbible.logic.getFontSize
import com.schwegelbin.openbible.logic.getSelection
import com.schwegelbin.openbible.logic.getShowVerseNumbers
import com.schwegelbin.openbible.logic.getSplitScreen
import com.schwegelbin.openbible.logic.getTextAlignment
import com.schwegelbin.openbible.logic.getVerses
import com.schwegelbin.openbible.logic.nostr.BibleReference
import com.schwegelbin.openbible.logic.nostr.Highlight
import com.schwegelbin.openbible.logic.nostr.HighlightRepository
import com.schwegelbin.openbible.logic.nostr.LocalSigner
import com.schwegelbin.openbible.logic.nostr.toHighlight
import com.schwegelbin.openbible.logic.nostr.db.AppDatabase
import com.schwegelbin.openbible.logic.nostr.embedded.EmbeddedRelay
import com.schwegelbin.openbible.logic.nostr.embedded.EventStore
import com.schwegelbin.openbible.logic.nostr.hasKeypair
import com.schwegelbin.openbible.logic.turnChapter
import com.schwegelbin.openbible.ui.components.CreateHighlightSheet
import com.schwegelbin.openbible.ui.components.VerseText
import com.schwegelbin.openbible.ui.components.ViewHighlightSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(
    onNavigateToBookmarks: () -> Unit,
    onNavigateToRead: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSelection: (Boolean, Int) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStart: () -> Unit,
) {
    val appTitle = getAppName(
        stringResource(R.string.app_name),
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary
    )
    val split = getSplitScreen(LocalContext.current)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(appTitle) },
            actions = {
                HamburgerMenu(
                    onNavigateToBookmarks,
                    onNavigateToSearch,
                    onNavigateToSettings
                )
            }
        )
    }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ReadCard(onNavigateToSelection, onNavigateToStart, onNavigateToRead, split, false)
                if (split == SplitScreen.Vertical)
                    ReadCard(
                        onNavigateToSelection,
                        onNavigateToStart,
                        onNavigateToRead,
                        split,
                        true
                    )
            }
            if (split == SplitScreen.Horizontal)
                ReadCard(onNavigateToSelection, onNavigateToStart, onNavigateToRead, split, true)
        }
    }
}

@Composable
fun ReadCard(
    onNavigateToSelection: (Boolean, Int) -> Unit,
    onNavigateToStart: () -> Unit,
    onNavigateToRead: () -> Unit,
    split: SplitScreen,
    isSplitScreen: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection = remember { mutableStateOf(getSelection(context, isSplitScreen)) }
    val (abbrev, book, chapter) = selection.value
    val translation = checkTranslation(context, abbrev, onNavigateToStart, isSplitScreen)
    val showVerseNumbers = remember { mutableStateOf(getShowVerseNumbers(context)) }
    val textAlignment = getTextAlignment(context)
    val errorStr = stringResource(R.string.error)

    // Per-verse data
    val (translationName, chapterName, verses) = getVerses(
        context, translation, book, chapter, errorStr
    )

    // Highlights for this chapter
    val highlights = remember { mutableStateOf<Map<Int, Highlight>>(emptyMap()) }

    // Load highlights from embedded relay
    LaunchedEffect(translation, book, chapter) {
        withContext(Dispatchers.IO) {
            try {
                if (hasKeypair(context)) {
                    val dao = AppDatabase.getInstance(context).nostrEventDao()
                    val eventStore = EventStore(dao)
                    // Query highlights by tag prefix for this chapter
                    val bookName = if (verses.isNotEmpty()) verses[0].bookName else ""
                    val prefix = "bible:$translation/$bookName/$chapter/"
                    val filter = com.schwegelbin.openbible.logic.nostr.NostrFilter(
                        kinds = listOf(9802),
                        tags = mapOf("r" to listOf(prefix))
                    )
                    val events = eventStore.queryEvents(filter)
                    val map = mutableMapOf<Int, Highlight>()
                    events.forEach { event ->
                        val hl = event.toHighlight()
                        if (hl?.reference != null) {
                            map[hl.reference.verse] = hl
                        }
                    }
                    highlights.value = map
                }
            } catch (_: Exception) {
                // Highlights are non-critical; silently continue
            }
        }
    }

    // Bottom sheet state
    val showCreateSheet = remember { mutableStateOf(false) }
    val showViewSheet = remember { mutableStateOf(false) }
    val selectedVerseForCreate = remember { mutableStateOf<VerseDisplay?>(null) }
    val selectedHighlight = remember { mutableStateOf<Highlight?>(null) }

    val mod = Modifier.fillMaxWidth()
    var outer = mod
    val fontSize = getFontSize(context)
    val textScale = remember { mutableFloatStateOf(fontSize.start) }
    val zoomState = rememberTransformableState { zoomChange, _, _ ->
        textScale.floatValue =
            min(max(textScale.floatValue * zoomChange, fontSize.start), fontSize.endInclusive)
    }
    val textStyle = MaterialTheme.typography.bodyLarge
    val scaledStyle = textStyle.copy(
        fontSize = (textScale.floatValue * textStyle.fontSize.value).sp,
        lineHeight = (textScale.floatValue * textStyle.lineHeight.value).sp
    )

    if (!isSplitScreen && split == SplitScreen.Vertical) outer = Modifier.fillMaxWidth(0.5f)
    if (!isSplitScreen && split == SplitScreen.Horizontal) outer = mod.fillMaxHeight(0.5f)

    Column(outer, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Header card with translation name, chapter name, nav buttons
        ElevatedCard(
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = if (isSplitScreen && split == SplitScreen.Horizontal) mod.padding(
                top = 12.dp
            ) else mod,
            onClick = { onNavigateToSelection(isSplitScreen, 1) }
        ) {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TurnButton(false, isSplitScreen, onNavigateToRead)
                Text(
                    text = translationName,
                    maxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = { onNavigateToSelection(isSplitScreen, 0) }),
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = chapterName,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.MiddleEllipsis,
                    textAlign = TextAlign.Center
                )
                TurnButton(true, isSplitScreen, onNavigateToRead)
            }
        }

        if (split != SplitScreen.Horizontal) Spacer(Modifier)

        // Content card with per-verse rendering
        ElevatedCard(
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .transformable(zoomState)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        textScale.floatValue =
                            if (textScale.floatValue != fontSize.start) fontSize.start else fontSize.endInclusive
                    })
                }
        ) {
            when (textAlignment) {
                ReadTextAlignment.Start -> {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            verses.forEach { verse ->
                                val highlight = highlights.value[verse.verseNumber]
                                VerseText(
                                    verse = verse,
                                    showVerseNumber = showVerseNumbers.value,
                                    highlight = highlight,
                                    textStyle = scaledStyle,
                                    onAnnotationClick = {
                                        if (highlight != null) {
                                            selectedHighlight.value = highlight
                                            showViewSheet.value = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                ReadTextAlignment.Justify -> {
                    Column(
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        verses.forEach { verse ->
                            val highlight = highlights.value[verse.verseNumber]
                            VerseText(
                                verse = verse,
                                showVerseNumber = showVerseNumbers.value,
                                highlight = highlight,
                                textStyle = scaledStyle.copy(textAlign = TextAlign.Justify),
                                onAnnotationClick = {
                                    if (highlight != null) {
                                        selectedHighlight.value = highlight
                                        showViewSheet.value = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create highlight bottom sheet
    if (showCreateSheet.value && selectedVerseForCreate.value != null) {
        val verse = selectedVerseForCreate.value!!
        CreateHighlightSheet(
            selectedText = verse.text,
            onDismiss = {
                showCreateSheet.value = false
                selectedVerseForCreate.value = null
            },
            onSave = { note ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val signer = LocalSigner(context)
                            val embeddedRelay = EmbeddedRelay(context)
                            val repo = HighlightRepository(embeddedRelay, signer)
                            val ref = BibleReference(
                                translation = translation,
                                book = verse.bookName,
                                chapter = chapter,
                                verse = verse.verseNumber
                            )
                            val hl = repo.saveHighlight(
                                highlightedText = verse.text,
                                reference = ref,
                                context = verse.text,
                                comment = note.ifBlank { null }
                            )
                            if (hl != null) {
                                highlights.value = highlights.value +
                                    (verse.verseNumber to hl)
                            }
                        } catch (_: Exception) { }
                    }
                }
                showCreateSheet.value = false
                selectedVerseForCreate.value = null
            }
        )
    }

    // View highlight bottom sheet
    if (showViewSheet.value && selectedHighlight.value != null) {
        ViewHighlightSheet(
            highlight = selectedHighlight.value!!,
            onDismiss = {
                showViewSheet.value = false
                selectedHighlight.value = null
            },
            onPublish = {
                // TODO: Implement publish to public relays
                showViewSheet.value = false
                selectedHighlight.value = null
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
                            val ref = hl.reference
                            if (ref != null) {
                                highlights.value = highlights.value - ref.verse
                            }
                        } catch (_: Exception) { }
                    }
                }
                showViewSheet.value = false
                selectedHighlight.value = null
            }
        )
    }
}


@Composable
fun TurnButton(next: Boolean, isSplitScreen: Boolean, onNavigateToRead: () -> Unit) {
    val context = LocalContext.current
    IconButton(onClick = { turnChapter(context, next, isSplitScreen, onNavigateToRead) }) {
        if (next) {
            Icon(Icons.Filled.ChevronRight, stringResource(R.string.next))
        } else {
            Icon(Icons.Filled.ChevronLeft, stringResource(R.string.previous))
        }
    }
}

@Composable
fun HamburgerMenu(
    onNavigateToBookmarks: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val expanded = remember { mutableStateOf(false) }
    IconButton(onClick = { expanded.value = !expanded.value }) {
        Icon(Icons.Filled.Menu, stringResource(R.string.menu))
    }
    DropdownMenu(
        expanded = expanded.value,
        onDismissRequest = { expanded.value = false },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.settings)) },
            trailingIcon = {
                Icon(Icons.Filled.Settings, null)
            },
            onClick = { expanded.value = false; onNavigateToSettings() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.highlights)) },
            trailingIcon = {
                Icon(Icons.Filled.Highlight, null)
            },
            onClick = { expanded.value = false; onNavigateToBookmarks() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.search)) },
            trailingIcon = {
                Icon(Icons.Filled.Search, null)
            },
            onClick = { expanded.value = false; onNavigateToSearch() }
        )
    }
}
