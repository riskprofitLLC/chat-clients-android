// Релей E2E-сообщений, справочник ключей, авторизация, линковка устройств и метрики.
// Хранилище: in-memory (по умолчанию) или PostgreSQL (CHAT_RELAY_DSN).
// Ноль криптокода: блобы opaque, сервер их не вскрывает.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

const (
	maxBlob     = 10 << 20            // потолок блоба, 10 МБ
	maxQueue    = 1000                // сообщений на устройство
	maxQueueAge = 30 * 24 * time.Hour // транзит, не хранилище
	// пагинация дрейна (S4): столько сообщений или байт на страницу ответа
	drainDefaultLimit = 200
	drainMaxLimit     = 1000
	// подтверждение доставки (S4): максимум id за один POST /v1/messages/ack
	ackIdsMax = 1000
	// 2.2: batch-отправка — до стольких получателей в одном запросе
	maxBatchRecipients = 128
)

// байтовые потолки страницы дрейна и квоты очереди (S4): var для тестов;
// 1000×10 МБ никто устройству не должен — транзит ограничен и по объёму
var (
	drainByteCeiling = int64(16 << 20) // 16 МБ на страницу дрейна
	maxQueueBytes    = int64(64 << 20) // 64 МБ на очередь устройства
	// 2.1: суточная квота медиа на аккаунт
	mediaDayQuota = int64(200 << 20)
	// выдачи медиа на аккаунт в минуту (var — тесты опускают)
	mediaGetsPerMin = 120
	// A5: квоты против флуда — один отправитель не должен вытеснять чужие
	// сообщения из очереди жертвы: потолок на пару (from→to-device)
	// и на число РАЗНЫХ отправителей в одной очереди (var — тесты поднимают)
	maxQueuePerSender = 100
	maxQueueSenders   = 200
)

// отправка с одного аккаунта в час (S4): защита от ретрай-шторма клиента
const sendPerHour = 5000

// события реального времени (S5): typing/read/delivered на аккаунт в секунду
const actionsPerSecond = 20

type deviceKey struct {
	DeviceID string `json:"device_id"`
	PubSPKI  string `json:"pub_spki"` // base64
}

type envelope struct {
	From     string    `json:"from_user"`
	To       string    `json:"to_user"`
	ToDevice string    `json:"to_device"`
	Kind     string    `json:"kind"` // chat | transfer
	Blob     string    `json:"blob"` // base64, opaque
	QueuedAt time.Time `json:"queued_at"`
	// Id — порядковый номер в очереди устройства; заполняется стором,
	// наружу отдаётся только в режиме ack (S4): по нему клиент подтверждает
	Id int64 `json:"id,omitempty"`
}

type drainResult struct {
	Messages []envelope `json:"messages"`
	More     bool       `json:"more"` // S4: за страницей ещё есть живые
}

type server struct {
	store                Store
	metrics              *metrics
	stats                *statsBuffer // дневные счётчики для админки
	hub                  *wsHub
	calls                *callHub // GC: сессии звонкового пайпа
	pusher               Pusher
	media                ObjectStore
	linkLimit            *ipLimiter
	discoverLimit        *ipLimiter
	emailCodeLimit       *ipLimiter
	loginLimit           *ipLimiter       // S2: попытки входа по паре (IP, логин)
	loginNameLimit       *ipLimiter       // 2.3: высокий порог по логину (без блокировки жертвы)
	loginIPLimit         *ipLimiter       // A5: общий порог по IP отдельно от пары (DoS через PBKDF2)
	onlineTargetLimit    *ipLimiter       // A5: /v1/online — лимит по паре (запрашивающий, цель)
	discoverAccountLimit *ipLimiter       // A6.3: discover — суточный лимит на аккаунт
	sendLimit            *ipLimiter       // S4: отправка на аккаунт
	actionLimit          *ipLimiter       // S5: typing/read/delivered — 20/с на аккаунт
	registerLimit        *ipLimiter       // 2.1: регистрации с IP
	keysLimit            *ipLimiter       // 2.1: справочник ключей с IP
	mediaRate            *ipLimiter       // 2.1: загрузки медиа на аккаунт в минуту
	mediaGetRate         *ipLimiter       // выдачи медиа на аккаунт в минуту
	mediaBytes           *byteLimiter     // 2.1: суточная квота медиа на аккаунт
	codes                *emailGate       // S3: cooldown + суточный лимит на email
	trustedProxies       []*net.IPNet     // S1: от кого читаем X-Forwarded-For
	emailCooldown        time.Duration    // S3: пауза между кодами на email
	reports              *reportStore     // GH9: счётчик жалоб
	mail                 *mailConfig      // свой mini-MTA; nil — выключен (CHAT_OWN_MAIL)
	now                  func() time.Time // внедряется в тестах
}

func newServer() *server {
	// медиа по умолчанию — во временном каталоге; тесты и прод назначают своё
	media, err := newFsStore(filepath.Join(os.TempDir(), "chat-media"))
	if err != nil {
		// nil-интерфейс медиа падал бы паникой на первой же загрузке
		log.Fatalf("media: %v", err)
	}
	return &server{
		store: newMemStore(), metrics: &metrics{}, stats: newStatsBuffer(),
		hub: newWsHub(), calls: newCallHub(), pusher: noopPusher{},
		media: media, reports: newReportStore(), linkLimit: newIpLimiter(linkInitPerHour, time.Hour),
		discoverLimit:        newIpLimiter(linkInitPerHour, time.Hour),
		emailCodeLimit:       newIpLimiter(5, time.Hour),
		loginLimit:           newIpLimiter(loginAttemptsPerHour, time.Hour),
		loginNameLimit:       newIpLimiter(100, time.Hour),
		loginIPLimit:         newIpLimiter(300, time.Hour),
		onlineTargetLimit:    newIpLimiter(60, time.Hour),
		discoverAccountLimit: newIpLimiter(20, 24*time.Hour),
		sendLimit:            newIpLimiter(sendPerHour, time.Hour),
		actionLimit:          newIpLimiter(actionsPerSecond, time.Second),
		registerLimit:        newIpLimiter(registerPerHour, time.Hour),
		keysLimit:            newIpLimiter(keysPerHour, time.Hour),
		mediaRate:            newIpLimiter(mediaPutsPerMin, time.Minute),
		mediaGetRate:         newIpLimiter(mediaGetsPerMin, time.Minute),
		mediaBytes:           newByteLimiter(mediaDayQuota, 24*time.Hour),
		codes:                newEmailGate(),
		trustedProxies:       parseTrustedProxies(os.Getenv("CHAT_TRUSTED_PROXIES")),
		emailCooldown:        time.Minute,
		now:                  time.Now,
	}
}

// сервер с постоянным хранилищем; рестарты не теряют данные.
// 2.6: прод требует явный CHAT_MEDIA_DIR — молчаливое /tmp недопустимо
func newPersistentServer(dsn, mediaDir string) (*server, error) {
	st, err := openPg(dsn)
	if err != nil {
		return nil, err
	}
	media, err := newFsStore(mediaDir)
	if err != nil {
		return nil, err
	}
	return &server{
		store: st, metrics: &metrics{}, stats: newStatsBuffer(),
		hub: newWsHub(), calls: newCallHub(), pusher: noopPusher{},
		media: media, reports: newReportStore(), linkLimit: newIpLimiter(linkInitPerHour, time.Hour),
		discoverLimit:        newIpLimiter(linkInitPerHour, time.Hour),
		emailCodeLimit:       newIpLimiter(5, time.Hour),
		loginLimit:           newIpLimiter(loginAttemptsPerHour, time.Hour),
		loginNameLimit:       newIpLimiter(100, time.Hour),
		loginIPLimit:         newIpLimiter(300, time.Hour),
		onlineTargetLimit:    newIpLimiter(60, time.Hour),
		discoverAccountLimit: newIpLimiter(20, 24*time.Hour),
		sendLimit:            newIpLimiter(sendPerHour, time.Hour),
		actionLimit:          newIpLimiter(actionsPerSecond, time.Second),
		registerLimit:        newIpLimiter(registerPerHour, time.Hour),
		keysLimit:            newIpLimiter(keysPerHour, time.Hour),
		mediaRate:            newIpLimiter(mediaPutsPerMin, time.Minute),
		mediaGetRate:         newIpLimiter(mediaGetsPerMin, time.Minute),
		mediaBytes:           newByteLimiter(mediaDayQuota, 24*time.Hour),
		codes:                newEmailGate(),
		trustedProxies:       parseTrustedProxies(os.Getenv("CHAT_TRUSTED_PROXIES")),
		emailCooldown:        time.Minute,
		now:                  time.Now,
	}, nil
}

func (s *server) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/accounts", s.handleRegister)
	mux.HandleFunc("POST /v1/login", s.handleLogin)
	mux.HandleFunc("PUT /v1/keys/{device}", s.putKey) // Bearer
	mux.HandleFunc("GET /v1/keys/{user}", s.getKeys)  // публичный
	mux.HandleFunc("POST /v1/messages", s.postMessage)
	mux.HandleFunc("POST /v1/messages/batch", s.postBatch) // 2.2: группа одним запросом
	mux.HandleFunc("GET /v1/messages", s.drain)            // Bearer: дрейн своей очереди
	mux.HandleFunc("POST /v1/messages/ack", s.handleAck)   // Bearer: S4, подтверждение ack-дрейна
	mux.HandleFunc("GET /v1/ws", s.handleWS)               // Bearer: wake-сигналы о новых сообщениях
	mux.HandleFunc("POST /v1/push", s.handlePushToken)     // Bearer: регистрация пуш-токена
	mux.HandleFunc("POST /v1/typing", s.handleTyping)
	mux.HandleFunc("POST /v1/delivered", s.handleDelivered)
	mux.HandleFunc("POST /v1/read", s.handleRead) // Bearer: прочитано (синие галочки)
	mux.HandleFunc("POST /v1/discover", s.handleDiscover)
	mux.HandleFunc("POST /v1/email-code", s.handleEmailCode)
	mux.HandleFunc("POST /v1/media", s.handleMediaPut) // Bearer: зашифрованный блоб
	mux.HandleFunc("GET /v1/media/{id}", s.handleMediaGet)
	mux.HandleFunc("POST /v1/link/init", s.handleLinkInit)       // новое устройство публикует ключ
	mux.HandleFunc("POST /v1/link/confirm", s.handleLinkConfirm) // Bearer ранее подключённого
	mux.HandleFunc("GET /v1/link/{nonce}", s.handleLinkPoll)     // опрос новым устройством
	mux.HandleFunc("POST /v1/calls", s.handleCallCreate)         // GC: сессия звонкового пайпа (Bearer)
	mux.HandleFunc("GET /v1/calls/{id}/ws", s.handleCallWS)      // GC: бинарный пайп звонка
	mux.HandleFunc("GET /metrics", s.handleMetrics)
	// админ-панель: только чтение, доступ как у /metrics (CHAT_ADMIN_TOKEN)
	mux.HandleFunc("GET /v1/admin/overview", s.handleAdminOverview)
	mux.HandleFunc("GET /v1/admin/users", s.handleAdminUsers)
	mux.HandleFunc("GET /v1/admin/history", s.handleAdminHistory)
	mux.HandleFunc("GET /v1/devices", s.handleListDevices)                   // GH3: список своих устройств
	mux.HandleFunc("POST /v1/devices/revoke", s.handleRevokeDevice)          // AH1: отключить устройство
	mux.HandleFunc("POST /v1/account/email", s.handleChangeEmail)            // AH9: смена email
	mux.HandleFunc("POST /v1/account/password", s.handleChangePassword)      // AH10: смена пароля
	mux.HandleFunc("POST /v1/account/delete", s.handleDeleteAccount)         // AI1: удаление аккаунта
	mux.HandleFunc("POST /v1/account/inactivity", s.handleAccountInactivity) // AJ: порог простоя
	mux.HandleFunc("POST /v1/account/cloudpw", s.handleCloudPassword)        // GH2: облачный пароль 2FA
	mux.HandleFunc("POST /v1/report", s.handleReport)                        // GH9: жалоба на пользователя
	mux.HandleFunc("POST /v1/online", s.handleOnline)                        // AI2: статус «в сети»
	mux.HandleFunc("POST /v1/presence/privacy", s.handlePresencePrivacy)     // AI2: приватность статуса
	return mux
}

// Линковка (Этап 3): новое устройство публикует ключ под одноразовым кодом
// и показывает QR; ранее подключённое сканирует код и подтверждает;
// токен новому выдаётся по коду, пароль не нужен.
type linkInitRequest struct {
	DeviceID string `json:"device_id"`
	PubSPKI  string `json:"pub_spki"`
}

func (s *server) handleLinkInit(w http.ResponseWriter, r *http.Request) {
	if !s.linkLimit.allow(s.clientIP(r), s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req linkInitRequest
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if !deviceRe.MatchString(req.DeviceID) {
		http.Error(w, "некорректный device_id", http.StatusBadRequest)
		return
	}
	if _, err := decodePub(req.PubSPKI); err != nil {
		http.Error(w, "ключ некорректен", http.StatusBadRequest)
		return
	}
	nonce, err := randomToken()
	if err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	// одноразовый секрет опроса — QR несёт его, сервер хранит хэш;
	// прежде токен мог забрать кто угодно, кто первым опросил nonce
	pollSecret, err := randomToken()
	if err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	if err := s.store.CreateLink(req.DeviceID, req.PubSPKI, nonce, hashToken(pollSecret)); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"nonce": nonce, "poll_secret": pollSecret})
}

// подтверждение ранее подключённым устройством; ключ сверяется с тем,
// что в коде (анти-MITM)
func (s *server) handleLinkConfirm(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		Nonce   string `json:"nonce"`
		PubSPKI string `json:"pub_spki"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	// S5: пустой nonce прежде отвечал 200 без тела и без записи
	if req.Nonce == "" {
		http.Error(w, "укажите код привязки", http.StatusBadRequest)
		return
	}
	_, pub, found, err := s.store.LinkByNonce(req.Nonce)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if !found {
		writeErr(w, http.StatusBadRequest, "link_expired", "код не найден или устарел")
		return
	}
	if pub != req.PubSPKI {
		// ключ из кода не совпал с опубликованным: код повреждён или сервер лжёт
		writeErr(w, http.StatusBadRequest, "key_mismatch", "ключ в коде не совпадает — код недействителен")
		return
	}
	// 1.2: токен здесь не создаётся — свежий mint-ится при опросе новым
	// устройством (ConsumeLink); между confirm и poll в БД токена нет
	err = s.store.ConfirmLink(req.Nonce, auth.login)
	switch {
	case errors.Is(err, errLinkNotFound):
		writeErr(w, http.StatusBadRequest, "link_expired", "код не найден или устарел")
		return
	case errors.Is(err, errDeviceConflict):
		writeErr(w, http.StatusConflict, "device_conflict", "устройство уже закреплено с другим ключом")
		return
	case err != nil:
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"user_id": auth.login})
}

// новое устройство опрашивает: pending → linked (токен выдаётся один раз,
// свежий — mint при опросе, 1.2). Секрет из QR обязателен: иначе токен
// мог бы забрать перехватчик кода.
func (s *server) handleLinkPoll(w http.ResponseWriter, r *http.Request) {
	nonce := r.PathValue("nonce")
	secret := r.URL.Query().Get("secret")
	if secret == "" || !s.store.VerifyLinkSecret(nonce, secret) {
		writeErr(w, http.StatusBadRequest, "link_expired", "код не найден или устарел")
		return
	}
	_, linked, err := s.store.LinkStatus(nonce)
	if errors.Is(err, errLinkNotFound) {
		writeErr(w, http.StatusBadRequest, "link_expired", "код не найден или устарел")
		return
	}
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if !linked {
		writeJSON(w, map[string]string{"status": "pending"})
		return
	}
	login, token, err := s.store.ConsumeLink(nonce)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "link_expired", "код уже использован")
		return
	}
	writeJSON(w, map[string]string{"status": "linked", "token": token, "user_id": login})
}

func (s *server) putKey(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	device := r.PathValue("device")
	if device == "" || device != auth.deviceID {
		http.Error(w, "чужое устройство", http.StatusForbidden)
		return
	}
	// S5: тело ключа читается с лимитом, как и остальные JSON-эндпоинты
	var body deviceKey
	if err := readJSONLimited(w, r, &body, jsonBodyLimit); err != nil {
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	// пустой ключ — некорректный запрос, а не молчаливый 200
	if body.PubSPKI == "" {
		http.Error(w, "пустой ключ", http.StatusBadRequest)
		return
	}
	if _, err := base64.StdEncoding.DecodeString(body.PubSPKI); err != nil {
		http.Error(w, "ключ не base64", http.StatusBadRequest)
		return
	}
	conflict, err := s.store.PutDeviceKey(auth.login, device, body.PubSPKI, "")
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if conflict {
		http.Error(w, "ключ уже закреплён (TOFU)", http.StatusConflict)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (s *server) getKeys(w http.ResponseWriter, r *http.Request) {
	// 2.1: лимитер по IP обязан стоять ДО работы со стором — иначе
	// перебор логинов через справочник ключей не ограничен
	if !s.keysLimit.allow(s.clientIP(r), s.now()) {
		http.Error(w, "слишком много запросов, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	user := r.PathValue("user")
	devices, err := s.store.ListDevices(user)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]any{"devices": devices})
}

// отправка: sender шифрует для КАЖДОГО устройства получателя (fan-out),
// сервер лишь раскладывает непрозрачные блобы по per-device очередям
func (s *server) postMessage(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		To    string `json:"to_user"`
		Kind  string `json:"kind"`
		Blobs []struct {
			DeviceID string `json:"device_id"`
			Blob     string `json:"blob"`
		} `json:"blobs"`
	}
	// фан-аут: тело ограничено, лимит на блоб проверяется ниже
	if err := readJSONLimited(w, r, &req, messageBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if req.To == "" || !loginRe.MatchString(req.To) || len(req.Blobs) == 0 {
		http.Error(w, "некорректный получатель", http.StatusBadRequest)
		return
	}
	kind := req.Kind
	if kind == "" {
		kind = "chat"
	}
	if kind != "chat" && kind != "transfer" {
		http.Error(w, "неизвестный kind", http.StatusBadRequest)
		return
	}
	// получатель обязан существовать (S4): без проверки можно плодить
	// очереди для любых пар «логин/устройство» и держать их в памяти
	devices, err := s.store.ListDevices(req.To)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	known := make(map[string]bool, len(devices))
	for _, d := range devices {
		known[d.DeviceID] = true
	}
	// self-send разрешён: transfer — перенос истории, chat — «Избранное»
	// и уведомления о новых устройствах (фан-аут на свои же устройства)
	valid := make([]struct {
		DeviceID string `json:"device_id"`
		Blob     string `json:"blob"`
	}, 0, len(req.Blobs))
	for _, blob := range req.Blobs {
		if !deviceRe.MatchString(blob.DeviceID) {
			http.Error(w, "некорректное устройство", http.StatusBadRequest)
			return
		}
		raw, err := base64.StdEncoding.DecodeString(blob.Blob)
		if err != nil || len(raw) == 0 || len(raw) > maxBlob {
			http.Error(w, "блоб некорректен или велик", http.StatusBadRequest)
			return
		}
		// чужие/несуществующие устройства не ставятся в очередь (S4)
		if known[blob.DeviceID] {
			valid = append(valid, blob)
		}
	}
	if len(valid) == 0 {
		http.Error(w, "получатель не найден", http.StatusNotFound)
		return
	}
	// rate-limit на аккаунт-отправитель (S4)
	if !s.sendLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много сообщений, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	for _, blob := range valid {
		env := envelope{
			From:     auth.login,
			To:       req.To,
			ToDevice: blob.DeviceID,
			Kind:     kind,
			Blob:     blob.Blob,
		}
		if err := s.store.Enqueue(&env, s.now()); err != nil {
			if errors.Is(err, errSenderQuota) {
				http.Error(w, "ваша квота в очереди получателя исчерпана", http.StatusTooManyRequests)
				return
			}
			http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
			return
		}
		s.statSent(auth.login)
		// мгновенный сигнал: живым WS-коннектам и пуш-каналу
		s.hub.notify(env.To, env.ToDevice)
		s.pusher.Notify(env.To, env.ToDevice)
	}
	w.WriteHeader(http.StatusOK)
}

// 2.2: batch-отправка — группа одним запросом. Лимит sendPerHour считается
// по СООБЩЕНИЮ (один batch), а не по блобу: группа из 50 человек прежде
// упиралась в ~100 сообщений/час. Частичные ошибки — по получателям:
// {"sent":N,"errors":[{"to_user":...,"status":404}]}
func (s *server) postBatch(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		Kind       string `json:"kind"`
		Recipients []struct {
			To    string `json:"to_user"`
			Blobs []struct {
				DeviceID string `json:"device_id"`
				Blob     string `json:"blob"`
			} `json:"blobs"`
		} `json:"recipients"`
	}
	if err := readJSONLimited(w, r, &req, batchBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if len(req.Recipients) == 0 || len(req.Recipients) > maxBatchRecipients {
		http.Error(w, "некорректное число получателей", http.StatusBadRequest)
		return
	}
	kind := req.Kind
	if kind == "" {
		kind = "chat"
	}
	if kind != "chat" && kind != "transfer" {
		http.Error(w, "неизвестный kind", http.StatusBadRequest)
		return
	}
	// один batch = одно сообщение: лимит на аккаунт-отправитель (S4)
	if !s.sendLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много сообщений, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	type recipientError struct {
		To     string `json:"to_user"`
		Status int    `json:"status"`
	}
	// два прохода: сначала валидируем ВЕСЬ batch, потом ставим в очередь.
	// Иначе невалидный получатель в середине списка возвращал 400 после
	// того, как первым уже разложили сообщения (частичная отправка + ошибка)
	errs := []recipientError{}
	type preparedRecipient struct {
		to    string
		known map[string]bool
		blobs []struct {
			DeviceID string `json:"device_id"`
			Blob     string `json:"blob"`
		}
	}
	prepared := make([]preparedRecipient, 0, len(req.Recipients))
	for _, rcpt := range req.Recipients {
		if rcpt.To == "" || !loginRe.MatchString(rcpt.To) || len(rcpt.Blobs) == 0 {
			http.Error(w, "некорректный получатель", http.StatusBadRequest)
			return
		}
		devices, err := s.store.ListDevices(rcpt.To)
		if err != nil {
			http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
			return
		}
		known := make(map[string]bool, len(devices))
		for _, d := range devices {
			known[d.DeviceID] = true
		}
		valid := 0
		for _, blob := range rcpt.Blobs {
			if !deviceRe.MatchString(blob.DeviceID) {
				http.Error(w, "некорректное устройство", http.StatusBadRequest)
				return
			}
			raw, err := base64.StdEncoding.DecodeString(blob.Blob)
			if err != nil || len(raw) == 0 || len(raw) > maxBlob {
				http.Error(w, "блоб некорректен или велик", http.StatusBadRequest)
				return
			}
			if known[blob.DeviceID] {
				valid++
			}
		}
		if valid == 0 {
			errs = append(errs, recipientError{rcpt.To, http.StatusNotFound})
			continue
		}
		prepared = append(prepared, preparedRecipient{rcpt.To, known, rcpt.Blobs})
	}
	sent := 0
	for _, rcpt := range prepared {
		for _, blob := range rcpt.blobs {
			if !rcpt.known[blob.DeviceID] {
				continue // чужие/несуществующие устройства не ставятся (S4)
			}
			env := envelope{
				From:     auth.login,
				To:       rcpt.to,
				ToDevice: blob.DeviceID,
				Kind:     kind,
				Blob:     blob.Blob,
			}
			if err := s.store.Enqueue(&env, s.now()); err != nil {
				if errors.Is(err, errSenderQuota) {
					// квота отправителя у этого получателя — частичная ошибка batch
					errs = append(errs, recipientError{rcpt.to, http.StatusTooManyRequests})
					continue
				}
				http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
				return
			}
			s.statSent(auth.login)
			s.hub.notify(env.To, env.ToDevice)
			s.pusher.Notify(env.To, env.ToDevice)
		}
		sent++
	}
	writeJSON(w, map[string]any{"sent": sent, "errors": errs})
}

// дрейн: очередь только своего устройства. S4: ?limit= — пагинация
// (по умолчанию 200 сообщений / 16 МБ на страницу), поле more;
// ?ack=1 — двухфазный дрейн: выдаём с id и НЕ удаляем, удаление — по
// POST /v1/messages/ack после записи клиентом в WAL (обрыв соединения
// больше не теряет сообщения). Без ack — прежнее поведение.
// 1.1: ?after=<id> — курсор ack-дрейна: записи с id ≤ after не выдаются,
// страница за страницей без повторов; зацикливание невозможно и при
// неудачном ack (неподтверждённое вернётся на следующем дрейне, dedup
// снимет дубль)
func (s *server) drain(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	limit := drainDefaultLimit
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > drainMaxLimit {
			http.Error(w, "некорректный limit", http.StatusBadRequest)
			return
		}
		limit = n
	}
	after := int64(0)
	if v := r.URL.Query().Get("after"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			http.Error(w, "некорректный after", http.StatusBadRequest)
			return
		}
		after = n
	}
	ackMode := r.URL.Query().Get("ack") == "1"
	var (
		msgs []envelope
		more bool
		err  error
	)
	if ackMode {
		msgs, more, err = s.store.PeekQueue(auth.login, auth.deviceID, s.now(), limit, drainByteCeiling, after)
	} else {
		msgs, more, err = s.store.Drain(auth.login, auth.deviceID, s.now(), limit, drainByteCeiling, after)
	}
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	// 2.6: delivered считается один раз — в ack-режиме при подтверждении
	// (handleAck), здесь — только для удаляющего дрейна
	if !ackMode {
		for _, message := range msgs {
			// часы инъецированные: с фейковым now метрика латентности не врёт
			s.statDelivered(auth.login, s.now().Sub(message.QueuedAt).Seconds()*1000)
		}
	}
	writeJSON(w, drainResult{Messages: msgs, More: more})
}

// S4: подтверждение ack-дрейна — удаление СВОИХ записей по id после
// записи клиентом в WAL; чужие id не трогаем
func (s *server) handleAck(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		Ids []int64 `json:"ids"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if len(req.Ids) == 0 || len(req.Ids) > ackIdsMax {
		http.Error(w, "пусто или слишком много id", http.StatusBadRequest)
		return
	}
	for _, id := range req.Ids {
		if id <= 0 {
			http.Error(w, "некорректный id", http.StatusBadRequest)
			return
		}
	}
	removed, ages, err := s.store.AckQueue(auth.login, auth.deviceID, req.Ids, s.now())
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	// 2.6: delivered в ack-режиме — при подтверждении, по одному разу
	for _, ageMs := range ages {
		s.statDelivered(auth.login, ageMs)
	}
	writeJSON(w, map[string]int{"deleted": removed})
}

// S6: /metrics — только loopback-клиент или носитель CHAT_METRICS_TOKEN;
// 2.1: loopback считается по КЛИЕНТСКОМУ IP — за Caddy RemoteAddr всегда
// loopback прокси, и без clientIP эндпоинт был публичен всем
func (s *server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	token := os.Getenv("CHAT_METRICS_TOKEN")
	if token != "" &&
		r.Header.Get("Authorization") != "Bearer "+token &&
		r.URL.Query().Get("token") != token {
		http.NotFound(w, r)
		return
	}
	if token == "" && !isLoopbackIP(s.clientIP(r)) {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	fmt.Fprint(w, renderMetrics(s))
}

func isLoopbackIP(ip string) bool {
	parsed := net.ParseIP(ip)
	return parsed != nil && parsed.IsLoopback()
}

// 2.4: типизированные ошибки — JSON {"error":"код"} рядом с текстом;
// клиент разбирает код, старые клиенты — текст (обратная совместимость)
func writeErr(w http.ResponseWriter, status int, code, text string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(map[string]string{"error": code, "message": text}); err != nil {
		log.Printf("ошибка записи ответа: %v", err)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("ошибка записи ответа: %v", err)
	}
}

func decodeJSONBody(r *http.Request, v any) error {
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(v); err != nil {
		return err
	}
	// хвост после валидного JSON — отказ: матрешка из мусора не проходит
	// валидацию полей молча
	if dec.More() {
		return errors.New("мусор после JSON")
	}
	return nil
}

func main() {
	addr := ":8080"
	if v := os.Getenv("CHAT_RELAY_ADDR"); v != "" {
		addr = v
	}
	var srv *server
	if dsn := os.Getenv("CHAT_RELAY_DSN"); dsn != "" {
		// 2.6: прод-режим требует явный каталог медиа — молчаливое /tmp
		// теряло блобы при перезагрузке и забивало диск
		mediaDir := os.Getenv("CHAT_MEDIA_DIR")
		if mediaDir == "" {
			log.Fatalf("прод-режим (CHAT_RELAY_DSN) требует CHAT_MEDIA_DIR")
		}
		persistent, err := newPersistentServer(dsn, mediaDir)
		if err != nil {
			log.Fatalf("Postgres недоступен: %v", err)
		}
		srv = persistent
		log.Printf("хранилище: PostgreSQL (рестарты сохраняют данные)")
		// дневная статистика для админ-панели: буфер → БД батчем;
		// финальный флаш — при остановке процесса
		statsCtx, stopStats := context.WithCancel(context.Background())
		defer func() { stopStats(); srv.flushStats() }()
		go srv.statsFlusher(statsCtx)
	} else {
		srv = newServer()
		log.Printf("хранилище: in-memory (рестарт теряет данные; прод — CHAT_RELAY_DSN)")
	}
	if pushURL := os.Getenv("CHAT_PUSH_URL"); pushURL != "" {
		srv.pusher = newWebhookPusher(pushURL)
		log.Printf("пуш-канал: webhook %s", pushURL)
	}
	log.Printf("релей, ключи, авторизация, линковка на %s", addr)
	stopSweeper := make(chan struct{})
	startMediaSweeper(srv, stopSweeper)
	startAccountSweeper(srv, stopSweeper) // AJ: снос простаивавших аккаунтов
	if mc, err := loadMailConfig(); err != nil {
		log.Fatalf("своя почта (CHAT_OWN_MAIL): %v", err)
	} else if mc != nil {
		srv.mail = mc
		startMailWorker(srv, mc, stopSweeper)
		log.Printf("своя почта: from=%s domain=%s dkim=%s", mc.from, mc.domain, mc.selector)
	}
	defer close(stopSweeper)

	// S5: таймауты на чтение заголовков и simple-запросы; WriteTimeout НЕ
	// ставим — он убивает долгоживущие WS-соединения. SIGTERM/SIGINT —
	// graceful shutdown: drain и WS досыпают ответы до 10 с
	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           srv.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	shutdown := make(chan os.Signal, 1)
	signal.Notify(shutdown, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-shutdown
		log.Printf("получен сигнал остановки — закрываем соединения")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := httpSrv.Shutdown(ctx); err != nil {
			log.Printf("shutdown: %v", err)
		}
	}()
	if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("релей остановлен: %v", err)
	}
	log.Printf("релей остановлен")
}

// POST /v1/typing Bearer {"to_user":...} — событие «печатает» через WS получателю
func (s *server) handleTyping(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		To string `json:"to_user"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if req.To == "" || !loginRe.MatchString(req.To) {
		http.Error(w, "некорректный получатель", http.StatusBadRequest)
		return
	}
	// S5: события реального времени лимитируются на аккаунт — кадры
	// чисто метаданные, но поток не должен молотить сервер
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком часто", http.StatusTooManyRequests)
		return
	}
	// будим все устройства получателя кадром "typing:<от_кого>"
	// A6.10: typing только для существующего получателя
	if acc, err := s.store.AccountByLogin(req.To); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	} else if acc == nil {
		w.WriteHeader(http.StatusOK)
		return
	}
	s.hub.notifyAll(req.To, "typing:"+auth.login)
	w.WriteHeader(http.StatusOK)
}
