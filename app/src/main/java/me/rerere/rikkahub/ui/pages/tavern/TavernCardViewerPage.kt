package me.rerere.rikkahub.ui.pages.tavern

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.export.PngCardWriter
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.CharacterBook
import me.rerere.rikkahub.data.model.CharacterBookEntry
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownWebView
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildCharacterCardViewerHtml
import me.rerere.rikkahub.ui.components.richtext.buildTavernCardPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import androidx.compose.ui.tooling.preview.Preview
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Encode
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavernCardViewerPage(
    cardUri: String? = null,
    cardJson: String? = null,
    assistantId: String? = null,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val colorScheme = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val defaultError = stringResource(R.string.assistant_importer_import_failed)

    var card by remember { mutableStateOf<TavernCharacterCard?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Use AtomicReference to avoid Compose saving this large string to Bundle
    val rawCardJson = remember { AtomicReference<String?>(null) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val settingsStore: SettingsStore = koinInject()

    LaunchedEffect(cardUri, cardJson) {
        isLoading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                when {
                    cardUri != null -> {
                        val uri = cardUri.toUri()
                        val mime = context.contentResolver.getType(uri)
                        if (mime == "image/png") {
                            val json = ImageUtils.getTavernCharacterMeta(context, uri).getOrThrow()
                            json to TavernCharacterCard.fromJson(json)
                        } else {
                            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                                .use { it?.readText() } ?: error("Cannot read file")
                            json to TavernCharacterCard.fromJson(json)
                        }
                    }
                    cardJson != null -> cardJson to TavernCharacterCard.fromJson(cardJson)
                    else -> null to null
                }
            }
            if (result != null) {
                rawCardJson.set(result.first)
                card = result.second
                // fromJson 解析失败返回 null（不再抛异常）——补一个错误提示，保持旧行为：
                // 此前 parseCardFromJson 抛异常被下方 catch 转成 error 展示。
                if (result.second == null) {
                    error = defaultError
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            error = e.message ?: defaultError
        } finally {
            isLoading = false
        }
    }

    // ── Export launchers ──
    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { outUri ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = rawCardJson.get() ?: return@withContext
                        context.contentResolver.openOutputStream(outUri)?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                        }
                    }
                    toaster.show(context.getString(R.string.tavern_card_export_success))
                } catch (e: Exception) {
                    toaster.show(context.getString(R.string.tavern_card_export_failed))
                }
            }
        }
    }

    val pngExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { outUri ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = rawCardJson.get() ?: return@withContext
                        // Load bitmap from the PNG card or create a default one
                        val bitmap = if (cardUri != null) {
                            context.contentResolver.openInputStream(cardUri.toUri())?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            } ?: createDefaultCardBitmap()
                        } else {
                            createDefaultCardBitmap()
                        }
                        val pngBytes = PngCardWriter.write(json, bitmap)
                        context.contentResolver.openOutputStream(outUri)?.use { os ->
                            os.write(pngBytes)
                        }
                        bitmap.recycle()
                    }
                    toaster.show(context.getString(R.string.tavern_card_export_success))
                } catch (e: Exception) {
                    toaster.show(context.getString(R.string.tavern_card_export_failed))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = card?.name?.ifBlank { stringResource(R.string.tavern_card_viewer_title) }
                            ?: stringResource(R.string.tavern_card_viewer_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    card?.let { currentCard ->
                        // Edit button — only when assistantId is available
                        if (assistantId != null) {
                            IconButton(
                                onClick = { navController.navigate(Screen.TavernCardEditor(assistantId = assistantId)) }
                            ) {
                                Icon(HugeIcons.Edit01, contentDescription = "Edit card")
                            }
                        }
                        // JSON export
                        IconButton(
                            onClick = {
                                val name = currentCard.name.ifBlank { "character" }
                                jsonExportLauncher.launch("$name.card.json")
                            }
                        ) {
                            Icon(HugeIcons.File02, contentDescription = "Export JSON")
                        }
                        // PNG export
                        IconButton(
                            onClick = {
                                val name = currentCard.name.ifBlank { "character" }
                                pngExportLauncher.launch("$name.card.png")
                            }
                        ) {
                            Icon(HugeIcons.Download01, contentDescription = "Export PNG")
                        }
                        // Full WebView preview
                        IconButton(
                            onClick = {
                                val html = buildCharacterCardViewerHtml(
                                    context = context,
                                    name = currentCard.name,
                                    description = currentCard.description,
                                    personality = currentCard.personality,
                                    scenario = currentCard.scenario,
                                    firstMes = currentCard.firstMes,
                                    mesExample = currentCard.mesExample,
                                    creatorNotes = currentCard.creatorNotes,
                                    systemPrompt = currentCard.systemPrompt,
                                    postHistoryInstructions = currentCard.postHistoryInstructions,
                                    alternateGreetings = currentCard.alternateGreetings,
                                    tags = currentCard.tags,
                                    creator = currentCard.creator,
                                    characterVersion = currentCard.characterVersion,
                                    colorScheme = colorScheme
                                )
                                val contentId = WebViewContentCache.store(context.cacheDir, html)
                                navController.navigate(
                                    Screen.WebView(contentId = contentId)
                                )
                            }
                        ) {
                            Icon(HugeIcons.Eye, contentDescription = "Preview")
                        }
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.tavern_card_load_error),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                card != null -> {
                    TavernCardContent(
                        card = card!!,
                        modifier = Modifier.fillMaxSize(),
                        // 仅当从 assistant detail 进来（assistantId != null）时给「使用此开场白」按钮，
                        // 因为新建对话需要确定的 assistant id；从 share/file 临时查看的卡没有这个上下文。
                        onUseGreeting = if (assistantId != null) {
                            { greeting ->
                                scope.launch {
                                    // 切到当前查看的 assistant，确保新对话用这张卡
                                    runCatching {
                                        settingsStore.updateAssistant(kotlin.uuid.Uuid.parse(assistantId))
                                    }
                                    val newConvId = kotlin.uuid.Uuid.random().toString()
                                    navController.navigate(
                                        Screen.Chat(
                                            id = newConvId,
                                            greeting = greeting.base64Encode()
                                        )
                                    )
                                }
                                Unit
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TavernCardContent(
    card: TavernCharacterCard,
    modifier: Modifier = Modifier,
    onUseGreeting: ((String) -> Unit)? = null,
) {
    var selectedSection by remember { mutableIntStateOf(0) }

    val sections = listOf(
        stringResource(R.string.tavern_card_section_overview),
        stringResource(R.string.tavern_card_section_details),
        stringResource(R.string.tavern_card_section_examples),
    )

    Column(modifier = modifier) {
        // Section selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sections.forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedSection == index,
                    onClick = { selectedSection = index },
                    label = { Text(title) }
                )
            }
        }

        HorizontalDivider()

        // Content
        when (selectedSection) {
            0 -> OverviewSection(card = card)
            1 -> DetailsSection(card = card)
            2 -> ExamplesSection(card = card, onUseGreeting = onUseGreeting)
        }
    }
}

@Composable
private fun OverviewSection(card: TavernCharacterCard) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Character image (if from PNG)
        card.sourceImageUri?.let { uri ->
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ZoomableAsyncImage(
                        model = uri,
                        contentDescription = card.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
            }
        }

        // Character header
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = card.name.ifBlank { "Unnamed Character" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )

                    if (card.creator.isNotBlank() || card.characterVersion.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (card.creator.isNotBlank()) {
                                Text(
                                    text = "@${card.creator}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            if (card.characterVersion.isNotBlank()) {
                                Text(
                                    text = "v${card.characterVersion}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.primary
                                )
                            }
                        }
                    }

                    if (card.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            card.tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${card.spec} / ${card.specVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Creator notes
        if (card.creatorNotes.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_creator_notes)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = card.creatorNotes,
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // Description preview
        if (card.description.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_description)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = card.description.take(2000) + if (card.description.length > 2000) "\n\n..." else "",
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // Personality preview
        if (card.personality.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_personality)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = card.personality,
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // Scenario preview
        if (card.scenario.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_scenario)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = card.scenario,
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // Character book summary
        card.characterBook?.let { book ->
            item {
                CardSection(
                    title = stringResource(R.string.tavern_card_character_book),
                    subtitle = "${book.entries.size} entries"
                ) {
                    Column {
                        book.name?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                        }
                        book.description?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Entries: ${book.entries.count { it.enabled }} / ${book.entries.size} enabled",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsSection(card: TavernCharacterCard) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Full description
        if (card.description.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_description)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = card.description,
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // System prompt
        if (card.systemPrompt.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_system_prompt)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = "```\n${card.systemPrompt}\n```",
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // Post-history instructions
        if (card.postHistoryInstructions.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_post_history)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = "```\n${card.postHistoryInstructions}\n```",
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }

        // Character book entries
        card.characterBook?.let { book ->
            item {
                CardSection(title = "${stringResource(R.string.tavern_card_character_book)} (${book.entries.size} entries)") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        book.entries.forEachIndexed { index, entry ->
                            CharacterBookEntryItem(entry = entry, index = index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamplesSection(card: TavernCharacterCard, onUseGreeting: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // First message
        if (card.firstMes.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_first_message)) {
                    // 角色卡 first_mes 走 raw HTML 渲染（默认 600dp 限高 + 内部滚动），
                    // 避免超长文档把外层 LazyColumn 撑爆 / WebView 高度测量不稳定。
                    MarkdownWebView(
                        content = card.firstMes,
                        isRawHtml = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (onUseGreeting != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        UseGreetingButton(onClick = { onUseGreeting(card.firstMes) })
                    }
                }
            }
        }

        // Alternate greetings
        card.alternateGreetings.forEachIndexed { index, greeting ->
            item {
                CardSection(title = "${stringResource(R.string.tavern_card_alternate_greeting)} ${index + 1}") {
                    MarkdownWebView(
                        content = greeting,
                        isRawHtml = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (onUseGreeting != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        UseGreetingButton(onClick = { onUseGreeting(greeting) })
                    }
                }
            }
        }

        // Example messages
        if (card.mesExample.isNotBlank()) {
            item {
                CardSection(title = stringResource(R.string.tavern_card_example_messages)) {
                    val html = buildTavernCardPreviewHtml(
                        context = context,
                        content = card.mesExample,
                        colorScheme = colorScheme
                    )
                    CompactWebView(html = html)
                }
            }
        }
    }
}

/**
 * 「使用此开场白」按钮 — 点击后由外层回调创建新对话并把这条开场白塞为
 * first_mes，然后跳转到 ChatPage。
 */
@Composable
private fun UseGreetingButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("使用此开场白开始新对话")
    }
}

@Composable
private fun CardSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CharacterBookEntryItem(entry: CharacterBookEntry, index: Int) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (entry.enabled) colorScheme.surfaceContainerHighest else colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.name ?: "Entry ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = if (entry.enabled) colorScheme.onSurface else colorScheme.onSurfaceVariant
                )
                if (!entry.enabled) {
                    Text(
                        text = "Disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.error
                    )
                }
            }

            if (entry.keys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Keys: ${entry.keys.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.primary
                )
            }

            if (entry.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = entry.content.take(200) + if (entry.content.length > 200) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (entry.constant == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Constant",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun CompactWebView(html: String) {
    val isPreview = LocalInspectionMode.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        if (isPreview) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "WebView Preview\n(${html.take(80)}...)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val state = rememberWebViewState(
                data = html,
                baseUrl = "https://rikkahub.local",
                mimeType = "text/html",
                settings = {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
            )
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// region Parsing

private fun createDefaultCardBitmap(): android.graphics.Bitmap {
    val size = 400
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(44, 44, 46)
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return bitmap
}

// endregion

// region Preview

@Preview(showBackground = true, name = "Overview Section")
@Composable
private fun OverviewSectionPreview() {
    MaterialTheme {
        OverviewSection(
            card = TavernCharacterCard(
                name = "Elena",
                description = "Elena is a skilled alchemist who runs a small shop in the medieval town of Thornwick. She has long silver hair and piercing green eyes.",
                personality = "Curious, cautious, and deeply passionate about her craft.",
                scenario = "You enter Elena's alchemy shop seeking a cure.",
                firstMes = "Welcome to my humble shop.",
                mesExample = "<START>\n{{user}}: Do you have anything for headaches?\n{{char}}: *She reaches for a small blue vial.* \"Willow bark extract.\"",
                creatorNotes = "Optimized for GPT-4. Works best with high temperature.",
                systemPrompt = "Write {{char}}'s next reply in a fictional chat.",
                postHistoryInstructions = "",
                alternateGreetings = listOf("*The shop is closed, but you notice a light in the back window.*"),
                characterBook = null,
                tags = listOf("fantasy", "alchemist", "medieval"),
                creator = "AlchemistCreator",
                characterVersion = "1.2",
                extensions = null,
                spec = "chara_card_v2",
                specVersion = "2.0"
            )
        )
    }
}

@Preview(showBackground = true, name = "Details Section")
@Composable
private fun DetailsSectionPreview() {
    MaterialTheme {
        DetailsSection(
            card = TavernCharacterCard(
                name = "Elena",
                description = "Elena is a skilled alchemist who runs a small shop.",
                personality = "Curious, cautious, passionate.",
                scenario = "You enter the alchemy shop.",
                firstMes = "",
                mesExample = "",
                creatorNotes = "",
                systemPrompt = "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.",
                postHistoryInstructions = "Remember: {{char}} should never break character.",
                alternateGreetings = emptyList(),
                characterBook = CharacterBook(
                    name = "Thornwick Town Lore",
                    description = "Local knowledge about the town",
                    scanDepth = 50,
                    tokenBudget = 300,
                    entries = listOf(
                        CharacterBookEntry(
                            keys = listOf("Thornwick", "town"),
                            content = "Thornwick is a small medieval town known for its alchemical traditions.",
                            enabled = true,
                            insertionOrder = 100,
                            name = "Town Info"
                        ),
                        CharacterBookEntry(
                            keys = listOf("potion", "elixir"),
                            content = "Elena's potions are renowned throughout the region.",
                            enabled = false,
                            insertionOrder = 200,
                            name = "Potion Lore"
                        )
                    )
                ),
                tags = listOf("fantasy"),
                creator = "AlchemistCreator",
                characterVersion = "1.2",
                extensions = null,
                spec = "chara_card_v2",
                specVersion = "2.0"
            )
        )
    }
}

@Preview(showBackground = true, name = "Examples Section")
@Composable
private fun ExamplesSectionPreview() {
    MaterialTheme {
        ExamplesSection(
            card = TavernCharacterCard(
                name = "Elena",
                description = "",
                personality = "",
                scenario = "",
                firstMes = "Welcome to my humble shop. I don't recognize your face — are you here for a potion?",
                mesExample = "<START>\n{{user}}: Do you have anything for headaches?\n{{char}}: *She reaches for a small blue vial.* \"Willow bark extract, infused with lavender.\"\n<START>\n{{user}}: Tell me about the ruins.\n{{char}}: *Her expression darkens.* \"Some doors are better left unopened.\"",
                creatorNotes = "",
                systemPrompt = "",
                postHistoryInstructions = "",
                alternateGreetings = listOf(
                    "*The shop is closed, but you notice a light in the back window. Elena opens the door cautiously.* \"We're closed. Unless you're here about the... job?\"",
                    "*You find Elena in the town square, arguing with a merchant.* \"Perfect timing. Tell this fool that powdered dragon scale is worthless.\""
                ),
                characterBook = null,
                tags = emptyList(),
                creator = "",
                characterVersion = "",
                extensions = null,
                spec = "chara_card_v2",
                specVersion = "2.0"
            )
        )
    }
}

// endregion
