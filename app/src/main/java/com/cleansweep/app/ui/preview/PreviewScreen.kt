package com.cleansweep.app.ui.preview

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.EmptyState
import com.cleansweep.app.ui.common.OnResumeEffect
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatDate
import java.io.File

/**
 * Screen 6, the preview of one file (PRD screen 6): what is in it, as large as the screen allows, and the
 * choice between keeping this copy and marking it for deletion. Nothing here changes the file itself.
 */
@Composable
fun PreviewScreen(
    onClose: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val text by viewModel.textPreview.collectAsStateWithLifecycle()
    val pages by viewModel.pdfPagesShown.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    OnResumeEffect { viewModel.onResume() }
    Messages(viewModel, messages)

    PreviewContent(
        state = state,
        text = text,
        pages = pages,
        messages = messages,
        onClose = onClose,
        onKeep = viewModel::keep,
        onRelease = viewModel::release,
        onMark = viewModel::mark,
    )
}

/** Shows what came of a decision, with a way to take it back where there is one. */
@Composable
private fun Messages(
    viewModel: PreviewViewModel,
    messages: SnackbarHostState,
) {
    // Through LocalResources, so that a message follows a change of language.
    val resources = LocalResources.current
    LaunchedEffect(viewModel, messages, resources) {
        viewModel.eventFlow.collect { event ->
            val undo = resources.getString(R.string.hub_msg_undo)
            when (event) {
                PreviewEvent.Kept -> messages.showSnackbar(resources.getString(R.string.preview_msg_kept), duration = SnackbarDuration.Long)
                PreviewEvent.Released -> messages.showSnackbar(resources.getString(R.string.preview_msg_released), duration = SnackbarDuration.Short)
                PreviewEvent.KeptByRule -> messages.showSnackbar(resources.getString(R.string.preview_msg_kept_by_rule), duration = SnackbarDuration.Long)
                PreviewEvent.CouldNotChange -> messages.showSnackbar(resources.getString(R.string.preview_msg_could_not_change), duration = SnackbarDuration.Short)

                is PreviewEvent.Ignored -> {
                    val result = messages.showSnackbar(
                        message = resources.getString(R.string.preview_msg_ignored),
                        actionLabel = undo,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.release()
                }

                is PreviewEvent.Unignored -> messages.showSnackbar(
                    message = resources.getString(R.string.preview_msg_unignored),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}

/**
 * The screen without its ViewModel. What draws a picture, and what plays a film or a piece of music, is passed
 * in, so that a test can show the screen without a decoder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewContent(
    state: PreviewUiState,
    text: String?,
    pages: List<Bitmap>,
    messages: SnackbarHostState,
    onClose: () -> Unit,
    onKeep: () -> Unit,
    onRelease: () -> Unit,
    onMark: (Boolean) -> Unit,
    picture: @Composable (path: String) -> Unit = { ZoomableImage(path = it, modifier = Modifier.fillMaxSize()) },
    player: @Composable (path: String, modifier: Modifier) -> Unit = { path, modifier -> VideoPlayer(path = path, modifier = modifier) },
) {
    val media = state.kind == PreviewKind.IMAGE || state.kind == PreviewKind.VIDEO
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.file?.name ?: stringResource(R.string.preview_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.preview_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (media) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = if (media) Color.White else Color.Unspecified,
                    navigationIconContentColor = if (media) Color.White else Color.Unspecified,
                ),
            )
        },
        snackbarHost = { SnackbarHost(messages) },
        bottomBar = { DecisionBar(state = state, onKeep = onKeep, onRelease = onRelease, onMark = onMark) },
        containerColor = if (media) Color.Black else MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.isGone -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(icon = AppIcons.File, title = stringResource(R.string.preview_gone_title), text = stringResource(R.string.preview_gone_text))
                }
                else -> Content(state = state, text = text, pages = pages, picture = picture, player = player)
            }
        }
    }
}

@Composable
private fun Content(
    state: PreviewUiState,
    text: String?,
    pages: List<Bitmap>,
    picture: @Composable (path: String) -> Unit,
    player: @Composable (path: String, modifier: Modifier) -> Unit,
) {
    val path = state.file?.path ?: return
    val noteColor = MaterialTheme.colorScheme.onSurfaceVariant
    when (state.kind) {
        PreviewKind.IMAGE -> when (state.content) {
            // The picture library draws while it decodes; only a failure is shown here.
            ContentState.FAILED -> CenteredNote(stringResource(R.string.preview_failed_title), stringResource(R.string.preview_failed_text))
            else -> picture(path)
        }

        PreviewKind.VIDEO -> player(path, Modifier.fillMaxSize())

        PreviewKind.AUDIO -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(AppIcons.Audio, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.preview_audio), style = MaterialTheme.typography.titleMedium)
            player(path, Modifier.fillMaxWidth().padding(top = 16.dp))
        }

        PreviewKind.PDF -> when (state.content) {
            ContentState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ContentState.FAILED -> CenteredNote(stringResource(R.string.preview_failed_title), stringResource(R.string.preview_failed_text))
            ContentState.READY -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                for (page in pages) {
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.preview_pdf_note, formatBytes(state.file.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = noteColor,
                )
            }
        }

        PreviewKind.TEXT -> when (state.content) {
            ContentState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ContentState.FAILED -> CenteredNote(stringResource(R.string.preview_failed_title), stringResource(R.string.preview_failed_text))
            ContentState.READY -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                SelectionContainer {
                    Text(text = text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = stringResource(R.string.preview_text_note, formatBytes(PreviewKinds.TEXT_READ_LIMIT_BYTES)),
                    style = MaterialTheme.typography.bodySmall,
                    color = noteColor,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        PreviewKind.NONE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            EmptyState(
                icon = AppIcons.File,
                title = stringResource(R.string.preview_none_title),
                text = stringResource(R.string.preview_none_text),
            )
            FileDetails(state.file)
        }
    }
}

/** "Keep this copy" or "Mark for deletion" (PRD screen 6). Whichever is on can be turned off by tapping it. */
@Composable
private fun DecisionBar(
    state: PreviewUiState,
    onKeep: () -> Unit,
    onRelease: () -> Unit,
    onMark: (Boolean) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (state.isOriginal && !state.isKeptByUser) {
                Text(
                    text = stringResource(R.string.preview_original_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.isKeptEitherWay,
                    enabled = !state.busy,
                    onClick = { if (state.isKeptEitherWay) onRelease() else onKeep() },
                    label = {
                        Text(stringResource(if (state.isDuplicate) R.string.preview_keep_copy else R.string.preview_keep_file))
                    },
                    leadingIcon = { Icon(AppIcons.Bookmark, contentDescription = null) },
                )
                FilterChip(
                    selected = state.isSelected,
                    enabled = state.canMark,
                    onClick = { onMark(!state.isSelected) },
                    label = { Text(stringResource(R.string.preview_mark)) },
                    leadingIcon = { Icon(AppIcons.Stop, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun CenteredNote(
    title: String,
    text: String,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = AppIcons.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The file's path, size and dates, as the group screen shows them. */
@Composable
internal fun FileDetails(
    file: IndexedFile,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Detail(stringResource(R.string.file_path), file.path)
        Detail(stringResource(R.string.file_size), formatBytes(file.sizeBytes))
        Detail(stringResource(R.string.file_modified), formatDate(file.lastModified))
        Detail(
            stringResource(R.string.file_created),
            file.createdAt?.let { formatDate(it) } ?: stringResource(R.string.file_created_unknown),
        )
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A picture that can be pinched to zoom and dragged about; a double tap puts it back (PRD 4.5). */
@Composable
internal fun ZoomableImage(
    path: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = File(path),
        contentDescription = stringResource(R.string.preview_image_description),
        contentScale = ContentScale.Fit,
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            },
    )
}

/** A film or a piece of music, played by the platform's player. It stops when the screen goes to the background. */
@Composable
internal fun VideoPlayer(
    path: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = true
        }
    }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    AndroidView(
        factory = { viewContext -> PlayerView(viewContext).apply {
            this.player = player
            useController = true
        } },
        modifier = modifier,
    )
}
