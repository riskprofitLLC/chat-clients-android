package chat.client.data

import android.app.Application
import chat.client.net.OkHttpRelayApi
import chat.client.net.RelayApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Ручной DI: единственное место сборки зависимостей. VM и сервисы берут
// хранилища и репозитории отсюда, глобальных объектов над Context больше нет.
class AppContainer(val app: Application) {

    val clock: Clock = Clock.System
    val appPrefs = AppPrefs(app)
    val chatPrefs = ChatPrefsStore(app)
    val sounds = chat.client.media.Sounds(app)
    val groups = GroupStore(app)
    val destruct = SelfDestructStore(app)
    val unread = UnreadStore(app)
    val knownKeys = KnownKeysStore(app)
    val blockedSenders = BlockedSendersStore(app)
    val contactBook = ContactBookStore(app)
    val editHistory = EditHistoryStore(app)
    val groupEvents = GroupEventStore(app)
    val profileGallery = ProfileGalleryStore(app)
    val avatars = AvatarsStore(app)
    // Фаза AB5: локальные обои чатов (фото из галереи)
    val wallpapers = ChatWallpapers(app)
    val bios = BiosStore(app)
    // живая геопозиция (Фаза AA7)
    val liveGeo = LiveLocationStore(app)
    // GE1: снапшот списка чатов для виджета главного экрана
    val widgetStore = WidgetStore(app)
    // GE4: локальный список историй
    val storyStore = StoryStore(app)
    // GE3: on-device перевод сообщений + кэш
    val translateStore = TranslateStore(app)
    val translator = chat.client.media.MlKitTranslator(app)

    init {
        // прокси из настроек — до первого сетевого вызова (Фаза AA5)
        chat.client.net.NetClients.configure(
            chat.client.domain.ProxyConfig.parse(
                appPrefs.proxyEnabled(), appPrefs.proxyHost(),
                appPrefs.proxyPort(), appPrefs.proxyType(),
            ),
        )
    }

    // api — var для подмены фейком в instrumented-тестах (C2)
    @Volatile var api: RelayApi = OkHttpRelayApi(Session.server())
    // Фаза GB: ядро звонка — WebRTC + стейт-машина. Ставится роутером
    // один раз: живые события дрейна и есть ринг/ответ/завершение
    @Volatile var callRouter: CallRouter? = null

    // лениво: фабрика WebRTC не должна строиться при холодном старте
    val callManager: chat.client.service.CallManager by lazy {
        chat.client.service.CallManager(app, this).also { callRouter = it }
    }

    // Фаза AJ: тик обновления папок после синка с другого устройства —
    // AppViewModel перечитывает prefs и гонит новый список в UI
    val foldersTick = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val media = chat.client.media.MediaRepository(this)
    val messageRepo = MessageRepository(this)
    val sessionRepo = SessionRepository(this)
    val chatRepo = ChatRepository(this)
    val outbox = Outbox(this)
    val connection = chat.client.service.ConnectionManager(app, this)

    // ЕДИНСТВЕННЫЙ StoreGate на процесс (A12): все пути — дрейн, outbox,
    // бэкап, UI — сериализуются через одну блокировку
    val storeGate: StoreGate by lazy {
        StoreGate(chat.crypto.AndroidMessageStore.open(java.io.File(app.filesDir, "store")))
    }

    // переоткрыть журнал после wipe (A6): фабрика нового хранилища на том же пути
    fun reopenStore() {
        storeGate.reopen(chat.crypto.AndroidMessageStore.open(java.io.File(app.filesDir, "store")))
    }

    // состояние сессии: null — ещё не загружено (сплэш), затем NoSession/
    // KeysUnavailable/Ready
    private val _sessionState = MutableStateFlow<SessionState?>(null)
    val sessionState: StateFlow<SessionState?> = _sessionState.asStateFlow()

    fun setSessionState(state: SessionState) {
        _sessionState.value = state
    }

    fun currentSession(): SessionInfo? = (sessionState.value as? SessionState.Ready)?.info

    // момент ухода в фон — для автоблокировки приложения
    @Volatile var lastBackgroundAt: Long = 0L

    // один шаг «не блокировать при возврате из ВНЕШНЕЙ активности» (A1):
    // ставится перед пикерами/камерой/сканером/разрешениями, гасится
    // первым же maybeLockOnResume
    @Volatile var suppressLockOnce: Boolean = false

    // инвалидация списка чатов (A17): null — полный пересчёт
    private val _chatsInvalidation = MutableSharedFlow<Set<String>?>(extraBufferCapacity = 16)
    val chatsInvalidation: SharedFlow<Set<String>?> = _chatsInvalidation.asSharedFlow()

    fun invalidateChats(chatIds: Collection<String>? = null) {
        _chatsInvalidation.tryEmit(chatIds?.toSet())
    }

    // тяжёлая инициализация — только с фона (B4): outbox с Keystore,
    // чистка временных файлов, миграция legacy
    @OptIn(DelicateCoroutinesApi::class)
    fun warmupAsync() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                appPrefs.ensureLegacySince(clock)
                chat.client.media.Media.cleanTemp(app)
                outbox.warmup()
                sweepAgedMedia()
            } catch (e: Exception) {
                android.util.Log.w("AppContainer", "warmup", e)
            }
        }
    }

    // Фаза AE1 (аналог «Keep media» у Telegram): зачистка локальных копий
    // старше срока; аватары защищены. Возврат — число удалённых файлов
    fun sweepAgedMedia(): Int =
        try {
            chat.client.media.Media.sweepByAge(app, appPrefs.mediaKeepDays(), clock.now(), avatars.avatarIds())
        } catch (_: Exception) {
            0
        }
}
