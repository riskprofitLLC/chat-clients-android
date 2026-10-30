package chat.client.ui.screens.share

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.client.ChatApplication
import chat.client.MainActivity
import chat.client.R
import chat.client.data.ChatSummary
import chat.client.data.ThemeMode
import chat.client.domain.ShareOutcome
import chat.client.media.Attachments
import chat.client.media.OutboxSender
import chat.client.ui.components.ChatAvatar
import chat.client.ui.screens.onboarding.authFieldColors
import chat.client.ui.theme.ChatShapes
import chat.client.ui.theme.ChatTheme
import chat.client.ui.theme.Size
import chat.client.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Данные «Поделиться»: текст и/или Uri вложений (с именами, A4)
internal data class SharedContent(val text: String?, val uris: List<Uri>, val names: List<String> = emptyList()) {
    val total: Int get() = (if (text != null) 1 else 0) + uris.size
}

// Share-таргет (Фаза K2): «Поделиться» из других приложений — список
// чатов с поиском; текст — сообщением, картинки — сжатием как из
// пикера, остальное — файлами. Отправка через общий outbox: офлайн
// запись ждёт сети. При включённой блокировке приложения (и если оно
// не на переднем плане) — экран-замок без списка чатов.
class ShareActivity : ComponentActivity() {

    private val container by lazy { ChatApplication.containerOf(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var content = SharedContent(null, emptyList())
    private var wasForeground = false

    private var chats by mutableStateOf<List<ChatSummary>>(emptyList())
    private var loading by mutableStateOf(true)
    private var hasSession by mutableStateOf(false)
    private var sending by mutableStateOf<Pair<Int, Int>?>(null)
    // итог отправки на экране, не тостом (B11): сообщение + кнопка «Закрыть»
    private var sendResult by mutableStateOf<String?>(null)
    // Фаза AF4: мультивыбор целей шеринга
    private var selectedIds by mutableStateOf<Set<String>>(emptySet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // фиксируем ДО старта этой активности: она сама поднимает флаг
        wasForeground = container.connection.appInForeground
        if (container.appPrefs.secureFlag()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val dark = when (container.appPrefs.themeMode()) {
            ThemeMode.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            // AH2: авто-тёмная — по окну (по часам устройства)
            ThemeMode.AUTO -> chat.client.domain.AutoDark.isDark(
                java.util.Calendar.getInstance().let {
                    it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
                },
                container.appPrefs.autoDarkFrom(),
                container.appPrefs.autoDarkTo(),
            )
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
                detectDarkMode = { dark },
            ),
        )
        content = parseIntent(intent)
        // процесс могли поднять ради шеринга: сессию и чаты тянем с диска
        scope.launch { load() }
        setContent {
            ChatTheme(
                container.appPrefs.themeMode(),
                dynamicColor = container.appPrefs.dynamicColors(),
                textScalePercent = container.appPrefs.textScale(),
                direction = runCatching {
                    chat.client.ui.theme.DesignDirection.valueOf(container.appPrefs.designDirectionName())
                }.getOrDefault(chat.client.ui.theme.DesignDirection.SOFT_CARDS),
            ) {
                ShareContent(
                    content = content,
                    lockBlocked = container.appPrefs.lockEnabled() &&
                        container.appPrefs.pinHash() != null && !wasForeground,
                    hasSession = hasSession,
                    chats = chats,
                    loading = loading,
                    sending = sending,
                    selected = selectedIds,
                    sendResult = sendResult,
                    onToggle = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onSend = ::send,
                    onOpenApp = ::openApp,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // разбор ACTION_SEND / SEND_MULTIPLE: текст + Uri вложений.
    // Только content:// и НЕ наш FileProvider (A4): file:// и ссылки
    // на приватные каталоги позволяли бы чужому приложению «отправить
    // в чат» файлы самого приложения (prefs с хэшем PIN, сессия)
    private fun parseIntent(intent: Intent?): SharedContent {
        if (intent == null) return SharedContent(null, emptyList())
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.take(chat.client.vm.DialogViewModel.TEXT_LIMIT) // потолок шаринга текста (A4)
        val rawUris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(extractUri(intent))
            Intent.ACTION_SEND_MULTIPLE -> extractUris(intent)
            else -> emptyList()
        }
        val uris = rawUris.filter(::isShareableUri)
        val names = uris.map { uri -> queryDisplayName(uri) ?: "" }
        return SharedContent(text, uris, names)
    }

    private fun isShareableUri(uri: Uri): Boolean =
        ShareUriFilter.isShareable(uri, packageName, filesDir, cacheDir, getExternalFilesDir(null), externalCacheDir)

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun extractUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

    private fun extractUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    // сессия и список чатов; очередь — warmup, свежий процесс её ещё не читал
    private suspend fun load() {
        withContext(Dispatchers.IO) {
            if (container.currentSession() == null) {
                try {
                    container.sessionRepo.restoreSession()
                } catch (_: Exception) {
                }
            }
            hasSession = container.currentSession() != null
            val s = container.currentSession()
            if (s != null) {
                try {
                    container.outbox.warmup()
                } catch (_: Exception) {
                }
                chats = container.chatRepo.summaries(s)
            }
            loading = false
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // отправка в выбранные чаты (Фаза AF4 — несколько целей): всё через
    // outbox, итог — на экране (B11); частичный успех честно показываем
    private fun send(chatIds: List<String>) {
        if (sending != null || chatIds.isEmpty()) return
        val s = container.currentSession() ?: return
        val targets = chatIds.toList()
        val total = content.total * targets.size
        sending = 0 to total
        scope.launch {
            var sent = 0
            var done = 0
            withContext(Dispatchers.IO) {
                // 3.11: вложения читаем/декодируем ОДИН раз до цикла по чатам
                // — прежде каждое фото декодировалось и сжималось N раз
                val attachments = content.uris.map { uri ->
                    try {
                        Attachments.loadAttachment(contentResolver, uri)
                    } catch (_: Exception) {
                        null
                    }
                }
                for (chatId in targets) {
                    content.text?.let { text ->
                        if (OutboxSender.addText(container, s, chatId, text) != null) sent++
                        done++
                        sending = done to total
                    }
                    for (att in attachments) {
                        if (att != null && OutboxSender.sendAttachment(container, s, chatId, att)) sent++
                        done++
                        sending = done to total
                    }
                }
                try {
                    container.outbox.flushAll(s)
                } catch (_: Exception) {
                }
                container.invalidateChats(targets.toSet())
            }
            sendResult = when (chat.client.domain.ShareOutcome.decide(sent, total)) {
                ShareOutcome.Kind.DONE -> resources.getQuantityString(
                    R.plurals.share_done_chats, targets.size, targets.size,
                )
                ShareOutcome.Kind.PARTIAL ->
                    if (sent % content.total.coerceAtLeast(1) == 0) {
                        getString(
                            R.string.share_partial_chats,
                            sent / content.total.coerceAtLeast(1),
                            targets.size,
                        )
                    } else {
                        getString(R.string.share_sent_partial, sent, total)
                    }
                ShareOutcome.Kind.FAILED -> getString(R.string.share_failed)
            }
            sending = null
        }
    }
}

// Экран шеринга: замок → нет аккаунта → итог → выбор чата. Отдельная
// функция — для инструментальных тестов без запуска активности.
@Composable
internal fun ShareContent(
    content: SharedContent,
    lockBlocked: Boolean,
    hasSession: Boolean,
    chats: List<ChatSummary>,
    loading: Boolean,
    sending: Pair<Int, Int>?,
    selected: Set<String> = emptySet(),
    sendResult: String? = null,
    onToggle: (String) -> Unit = {},
    onSend: (List<String>) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    when {
        lockBlocked -> ShareNotice(
            text = stringResource(R.string.share_locked),
            onOpenApp = onOpenApp,
            onClose = onClose,
        )
        !hasSession -> ShareNotice(
            text = stringResource(R.string.share_no_session),
            onOpenApp = onOpenApp,
            onClose = onClose,
        )
        // итог отправки на экране, не тостом (B11)
        sendResult != null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(chat.client.ui.theme.Size.avatarChat),
            )
            Spacer(Modifier.height(Spacing.l))
            Text(sendResult, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(Spacing.l))
            Button(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
        else -> SharePicker(
            content = content,
            chats = chats,
            loading = loading,
            sending = sending,
            selected = selected,
            onToggle = onToggle,
            onSend = onSend,
            onClose = onClose,
        )
    }
}

// уведомление с кнопками: замок или «нет аккаунта»
@Composable
private fun ShareNotice(text: String, onOpenApp: () -> Unit, onClose: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(Spacing.l))
        Button(onClick = onOpenApp) { Text(stringResource(R.string.share_open_app)) }
        Spacer(Modifier.height(Spacing.s))
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.close)) }
    }
}

// выбор чата: превью содержимого, поиск, список; Фаза AF4 — мультивыбор
@Composable
private fun SharePicker(
    content: SharedContent,
    chats: List<ChatSummary>,
    loading: Boolean,
    sending: Pair<Int, Int>?,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSend: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
            }
            Column {
                Text(
                    stringResource(R.string.share_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.share_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // превью содержимого: текст до двух строк, вложения — именами (A4)
        content.text?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        val shownNames = content.names.filter { it.isNotBlank() }
        if (shownNames.isNotEmpty()) {
            Text(
                shownNames.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        } else if (content.text == null && content.uris.isNotEmpty()) {
            Text(
                stringResource(R.string.share_files_count, content.uris.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.share_search)) },
            singleLine = true,
            shape = ChatShapes.Field,
            colors = authFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )
        // 3.11: фильтр — один расчёт на композицию (прежде дважды за кадр)
        val visibleChats = androidx.compose.runtime.remember(query, chats) {
            filteredChats(chats, query)
        }
        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator()
                }
                visibleChats.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(stringResource(R.string.share_no_chats), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleChats, key = { it.id }) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                ChatAvatar(chat.id, chat.isGroup, Size.avatarList, isSaved = chat.isSelf, avatar = chat.avatar, emoji = chat.emoji)
                            },
                            trailingContent = {
                                // Фаза AF4: чекбокс мультивыбора вместо счётчика
                                if (chat.id in selected) {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(Size.iconAction),
                                    )
                                } else if (chat.unread > 0) {
                                    Text(
                                        chat.unread.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            // 3.11: список заблокирован на время отправки
                            modifier = Modifier.clickable(enabled = sending == null) { onToggle(chat.id) },
                        )
                    }
                }
            }
        }
        // Фаза AF4: кнопка отправки в выбранные чаты — как пикер пересылки
        if (selected.isNotEmpty()) {
            Button(
                onClick = { onSend(selected.toList()) },
                enabled = sending == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .testTag("share-send-multi"),
            ) { Text(stringResource(R.string.share_pick_count, selected.size)) }
        }
        // прогресс отправки поверх списка: не даёт второй тап
        sending?.let { (done, total) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Spacing.l, vertical = Spacing.m),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Size.iconAction),
                )
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(R.string.share_sending, done, total),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// фильтр по названию и логину; чистая функция — для тестов
internal fun filteredChats(chats: List<ChatSummary>, query: String): List<ChatSummary> {
    if (query.isBlank()) return chats
    return chats.filter { it.title.contains(query, true) || it.id.contains(query, true) }
}


// Фильтр шаринга (A4): только content://, НЕ наш FileProvider и не пути
// в приватные каталоги приложения — file:// позволял бы чужому приложению
// «отправить в чат» приватные файлы самого чата (prefs с PIN, сессия)
internal object ShareUriFilter {

    fun isShareable(
        uri: Uri,
        packageName: String,
        filesDir: java.io.File,
        cacheDir: java.io.File,
        externalFilesDir: java.io.File?,
        externalCacheDir: java.io.File?,
    ): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        if (uri.authority == "$packageName.files") return false
        val path = uri.path ?: return true
        val privateDirs = listOfNotNull(filesDir, cacheDir, externalFilesDir, externalCacheDir)
        return privateDirs.none { dir -> path.startsWith(dir.absolutePath) }
    }
}
