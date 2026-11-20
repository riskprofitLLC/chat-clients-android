package main

import (
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

// In-memory хранилище: тесты и локальная разработка.
// Рестарт процесса теряет всё — прод использует CHAT_RELAY_DSN.
type deviceRow struct {
	pub   string
	token string
}

type linkRow struct {
	deviceID  string
	pub       string
	pollHash  string // 2.5: хэш одноразового секрета опроса
	login     string // заполняется при подтверждении
	confirmed bool   // старое устройство подтвердило; токен ещё не выдан
	createdAt time.Time
}

// одноразовый код привязки живёт сутки (как в pgstore); просроченный
// не принимается ни на чтении, ни на подтверждении
const linkTTL = 24 * time.Hour

func linkExpired(row *linkRow, now time.Time) bool {
	return !row.createdAt.IsZero() && now.Sub(row.createdAt) > linkTTL
}

type emailCode struct {
	email     string
	code      string
	expiresAt time.Time
	used      bool
	attempts  int // S3: неудачные попытки ввода; ≥ maxCodeAttempts — код сгорает
}

type memstore struct {
	mu          sync.Mutex
	accounts    map[string]*account
	devices     map[string]map[string]*deviceRow // login -> device -> row
	byToken     map[string]*deviceAuth
	links       map[string]*linkRow // nonce -> ожидание подтверждения
	queue       map[string][]envelope
	nextQueueID int64             // порядковые id записей очереди (S4: ack)
	pushTokens  map[string]string // login/device -> platform:token
	emails      map[string]string // emailHash -> login
	emailCodes  []emailCode       // верификационные коды
	mailOutbox  []outMail         // очередь исходящей почты (свой mini-MTA)
	nextMailID  int64
}

func newMemStore() *memstore {
	return &memstore{
		accounts:   make(map[string]*account),
		devices:    make(map[string]map[string]*deviceRow),
		byToken:    make(map[string]*deviceAuth),
		links:      make(map[string]*linkRow),
		queue:      make(map[string][]envelope),
		pushTokens: make(map[string]string),
		emails:     make(map[string]string),
		emailCodes: make([]emailCode, 0),
	}
}

func queueKey(login, deviceID string) string { return login + "/" + deviceID }

func (m *memstore) CreateAccount(acc *account, deviceID, pubSpki, token, emailHash string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	// почта занимается атомарно с аккаунтом: иначе две параллельные
	// регистрации с одной почтой давали сироту-аккаунт и сожжённый код
	if emailHash != "" {
		if owner, ok := m.emails[emailHash]; ok && owner != acc.login {
			return false, errEmailTaken
		}
	}
	if _, exists := m.accounts[acc.login]; exists {
		return true, nil
	}
	if acc.createdAt.IsZero() {
		acc.createdAt = time.Now()
	}
	m.accounts[acc.login] = acc
	m.putDeviceLocked(acc.login, deviceID, pubSpki, token)
	if emailHash != "" {
		if m.emails == nil {
			m.emails = make(map[string]string)
		}
		m.emails[emailHash] = acc.login
	}
	return false, nil
}

func (m *memstore) putDeviceLocked(login, deviceID, pubSpki, token string) {
	devices := m.devices[login]
	if devices == nil {
		devices = make(map[string]*deviceRow)
		m.devices[login] = devices
	}
	if row, exists := devices[deviceID]; exists {
		row.token = hashToken(token) // S6: в БД/памяти — только хэш
		if row.pub != pubSpki {
			row.pub = pubSpki // TOFU-конфликт отсеивается выше
		}
	} else {
		devices[deviceID] = &deviceRow{pub: pubSpki, token: hashToken(token)}
	}
	m.byToken[hashToken(token)] = &deviceAuth{login: login, deviceID: deviceID}
}

// возвращает КОПИЮ: указатель на внутреннюю запись читался бы вне лока
// (гонка с SetAccountCloudKey/UpdateAccountHash) и позволял менять стор мимо API
func (m *memstore) AccountByLogin(login string) (*account, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	acc, ok := m.accounts[login]
	if !ok {
		return nil, nil
	}
	cp := *acc
	return &cp, nil
}

func (m *memstore) DeviceExists(login, deviceID string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, exists := m.devices[login][deviceID]
	return exists, nil
}

func (m *memstore) RotateToken(login, deviceID, token string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.devices[login][deviceID]
	if !exists {
		return errAccountNotFound
	}
	delete(m.byToken, row.token)
	row.token = hashToken(token) // S6: только хэш
	m.byToken[row.token] = &deviceAuth{login: login, deviceID: deviceID}
	return nil
}

func (m *memstore) AuthByToken(token string) (*deviceAuth, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.byToken[hashToken(token)], nil // S6: сырой токен в стор не попадает
}

// перехэш аккаунта при входе со старой стоимостью KDF (S6)
func (m *memstore) UpdateAccountHash(login string, salt, key []byte, iterations int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	acc, exists := m.accounts[login]
	if !exists {
		return errAccountNotFound
	}
	acc.salt = salt
	acc.pkdfKey = key
	acc.iters = iterations
	return nil
}

// GH2: облачный пароль — отдельный хэш поверх основного
func (m *memstore) SetAccountCloudKey(login string, salt, key []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	acc, exists := m.accounts[login]
	if !exists {
		return errAccountNotFound
	}
	acc.cloudSalt = salt
	acc.cloudKey = key
	return nil
}

// TOFU: повторная публикация того же ключа ок, смена — конфликт.
// Пустой токен недопустим: иначе hashToken("") стал бы валидным Bearer.
func (m *memstore) PutDeviceKey(login, deviceID, pubSpki, token string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.devices[login][deviceID]
	if exists && row.pub != pubSpki {
		return true, nil
	}
	if !exists {
		// пустой токен при регистрации нового устройства недопустим:
		// иначе hashToken("") стал бы валидным Bearer
		if token == "" {
			return false, fmt.Errorf("empty token")
		}
		m.putDeviceLocked(login, deviceID, pubSpki, token)
	}
	return false, nil
}

func (m *memstore) ListDevices(login string) ([]deviceKey, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	devices := m.devices[login]
	out := make([]deviceKey, 0, len(devices))
	for id, row := range devices {
		out = append(out, deviceKey{DeviceID: id, PubSPKI: row.pub})
	}
	return out, nil
}

// AH1: ревокация устройства — ключ, токен и пуш-токен умирают разом
func (m *memstore) RemoveDevice(login, deviceID string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	devices := m.devices[login]
	row, exists := devices[deviceID]
	if !exists {
		return false, nil
	}
	delete(m.byToken, row.token)
	delete(devices, deviceID)
	delete(m.pushTokens, queueKey(login, deviceID))
	return true, nil
}

// новое устройство публикует свой ключ под одноразовым кодом
func (m *memstore) CreateLink(deviceID, pubSpki, nonce, pollSecretHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	// просроченные коды вычищаем — иначе map растёт вечно
	now := time.Now()
	for n, row := range m.links {
		if linkExpired(row, now) {
			delete(m.links, n)
		}
	}
	m.links[nonce] = &linkRow{deviceID: deviceID, pub: pubSpki, pollHash: pollSecretHash, createdAt: now}
	return nil
}

func (m *memstore) LinkByNonce(nonce string) (string, string, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.links[nonce]
	if !exists || linkExpired(row, time.Now()) {
		return "", "", false, nil
	}
	return row.deviceID, row.pub, true, nil
}

// сверка секрета опроса по хэшу
func (m *memstore) VerifyLinkSecret(nonce, secret string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.links[nonce]
	return exists && !linkExpired(row, time.Now()) && row.pollHash != "" && row.pollHash == hashToken(secret)
}

// подтверждение ранее подключённым устройством: устройство закреплено за
// логином, токен НЕ создаётся — его отmint-ит ConsumeLink при опросе новым
// устройством; в сторе сырого токена до выдачи нет вовсе (1.2)
func (m *memstore) ConfirmLink(nonce, login string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.links[nonce]
	if !exists || linkExpired(row, time.Now()) {
		return errLinkNotFound
	}
	if existing, busy := m.devices[login][row.deviceID]; busy && existing.pub != row.pub {
		return errDeviceConflict
	}
	row.login = login
	row.confirmed = true
	return nil
}

func (m *memstore) LinkStatus(nonce string) (string, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.links[nonce]
	if !exists || linkExpired(row, time.Now()) {
		return "", false, errLinkNotFound
	}
	return row.login, row.confirmed, nil
}

// одноразовая выдача: свежий токен, хэш — в devices, строка стирается
func (m *memstore) ConsumeLink(nonce string) (string, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	row, exists := m.links[nonce]
	if !exists || !row.confirmed || linkExpired(row, time.Now()) {
		return "", "", errLinkNotFound
	}
	token, err := randomToken()
	if err != nil {
		return "", "", err
	}
	if existing, busy := m.devices[row.login][row.deviceID]; busy && existing.pub != row.pub {
		return "", "", errDeviceConflict
	}
	m.putDeviceLocked(row.login, row.deviceID, row.pub, token)
	delete(m.links, nonce)
	return row.login, token, nil
}

func (m *memstore) Enqueue(env *envelope, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := queueKey(env.To, env.ToDevice)
	q := m.queue[key]
	// A5: квоты против флуда — до постановки в очередь считаем, сколько
	// там уже записей этого отправителя и сколько отправителей всего;
	// чужие сообщения в очереди жертвы вытеснять нельзя
	perSender := 0
	senders := make(map[string]struct{})
	for _, e := range q {
		if e.From == env.From {
			perSender++
		}
		senders[e.From] = struct{}{}
	}
	if perSender >= maxQueuePerSender {
		return errSenderQuota
	}
	if _, seen := senders[env.From]; !seen && len(senders) >= maxQueueSenders {
		return errSenderQuota
	}
	env.QueuedAt = now
	m.nextQueueID++
	env.Id = m.nextQueueID
	q = append(q, *env)
	// лимиты per-device: и по счётчику, и по суммарному объёму (S4);
	// сверх — вытесняем старейшие, очередь не растёт до гигабайтов
	total := 0
	for _, e := range q {
		total += len(e.Blob)
	}
	i := 0
	for i < len(q) && (len(q)-i > maxQueue || int64(total) > maxQueueBytes) {
		total -= len(q[i].Blob)
		i++
	}
	m.queue[key] = q[i:]
	return nil
}

// живая часть очереди с TTL-фильтром; страница по счётчику/байтам (S4).
// 1.1: after — курсор ack-дрейна, в страницу попадают только id > after;
// очередь при этом НЕ трогаем: неподтверждённые записи остаются на месте
func (m *memstore) pageLocked(
	login, deviceID string, now time.Time, limit int, byteCeil int64, after int64,
) []envelope {
	key := queueKey(login, deviceID)
	q := m.queue[key]
	cutoff := now.Add(-maxQueueAge)
	fresh := make([]envelope, 0, len(q))
	for _, message := range q {
		if message.QueuedAt.After(cutoff) {
			fresh = append(fresh, message)
		}
	}
	m.queue[key] = fresh
	// 1.1: after — курсор; кандидаты собираются в ОТДЕЛЬНЫЙ срез:
	// писать поверх fresh значило бы портить саму очередь дублями
	var candidates []envelope
	if after > 0 {
		candidates = make([]envelope, 0, len(fresh))
		for _, message := range fresh {
			if message.Id > after {
				candidates = append(candidates, message)
			}
		}
	} else {
		candidates = fresh
	}
	page := candidates
	var total int64
	for i, message := range candidates {
		size := int64(len(message.Blob))
		// страница по счётчику/байтам; первый элемент входит ВСЕГДА —
		// иначе клиент завис бы на блобе больше байтового потолка
		if i >= limit || (byteCeil > 0 && total+size > byteCeil && i > 0) {
			page = candidates[:i]
			break
		}
		total += size
	}
	return page
}

func (m *memstore) compactQueueLocked(key string) {
	if len(m.queue[key]) == 0 {
		delete(m.queue, key)
	}
}

// дрейн: отдаёт страницу и удаляет её из очереди; more=true — за страницей
// ещё есть живые сообщения (S4: клиент докручивает страницы)
func (m *memstore) Drain(
	login, deviceID string, now time.Time, limit int, byteCeil int64, after int64,
) ([]envelope, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	page := m.pageLocked(login, deviceID, now, limit, byteCeil, after)
	key := queueKey(login, deviceID)
	if after > 0 && len(page) > 0 {
		// страница за курсором — не префикс очереди: удаляем по id
		gone := make(map[int64]bool, len(page))
		for _, message := range page {
			gone[message.Id] = true
		}
		kept := m.queue[key][:0]
		for _, message := range m.queue[key] {
			if !gone[message.Id] {
				kept = append(kept, message)
			}
		}
		m.queue[key] = kept
	} else {
		m.queue[key] = m.queue[key][len(page):]
	}
	m.compactQueueLocked(key)
	return page, len(m.queue[key]) > 0, nil
}

// просмотр без удаления (S4: ack-режим) — id заполнены для подтверждения;
// 1.1: after двигает окно выдачи, неподтверждённое остаётся в очереди
func (m *memstore) PeekQueue(
	login, deviceID string, now time.Time, limit int, byteCeil int64, after int64,
) ([]envelope, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	page := m.pageLocked(login, deviceID, now, limit, byteCeil, after)
	// more: за страницей есть ещё ЖИВЫЕ записи ЗА курсором
	key := queueKey(login, deviceID)
	rest := false
	if len(page) > 0 {
		last := page[len(page)-1].Id
		for _, message := range m.queue[key] {
			if message.Id > last {
				rest = true
				break
			}
		}
	} else if after > 0 {
		for _, message := range m.queue[key] {
			if message.Id > after {
				rest = true
				break
			}
		}
	} else {
		rest = len(m.queue[key]) > 0
	}
	return page, rest, nil
}

// подтверждение: удаляем СВОИ записи по id (S4); 2.6: возвращаем возрасты
// удалённых — метрика delivered в ack-режиме считается здесь, один раз
func (m *memstore) AckQueue(login, deviceID string, ids []int64, now time.Time) (int, []float64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	wanted := make(map[int64]bool, len(ids))
	for _, id := range ids {
		wanted[id] = true
	}
	key := queueKey(login, deviceID)
	kept := m.queue[key][:0]
	removed := 0
	var ages []float64
	for _, message := range m.queue[key] {
		if wanted[message.Id] {
			removed++
			ages = append(ages, now.Sub(message.QueuedAt).Seconds()*1000)
			continue
		}
		kept = append(kept, message)
	}
	m.queue[key] = kept
	m.compactQueueLocked(key)
	return removed, ages, nil
}

// периодическая чистка просроченного транзита (S4)
func (m *memstore) SweepExpired(now time.Time) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	cutoff := now.Add(-maxQueueAge)
	removed := 0
	for key, q := range m.queue {
		fresh := q[:0]
		for _, message := range q {
			if message.QueuedAt.After(cutoff) {
				fresh = append(fresh, message)
			} else {
				removed++
			}
		}
		m.queue[key] = fresh
		m.compactQueueLocked(key)
	}
	return removed, nil
}

func (m *memstore) QueueDepths() (map[string]int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make(map[string]int)
	for key, q := range m.queue {
		login, _, _ := splitQueueKey(key)
		out[login] += len(q)
	}
	return out, nil
}

func splitQueueKey(key string) (string, string, bool) {
	for i := len(key) - 1; i >= 0; i-- {
		if key[i] == '/' {
			return key[:i], key[i+1:], true
		}
	}
	return key, "", false
}

func (m *memstore) AccountCount() (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.accounts), nil
}

// Админка (только чтение): те же агрегаты, что и у pgstore
func (m *memstore) AdminOverview(now time.Time) (adminOverview, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	weekAgo := now.Add(-7 * 24 * time.Hour)
	monthAgo := now.Add(-30 * 24 * time.Hour)
	dayAgo := now.Add(-24 * time.Hour)
	ov := adminOverview{Accounts: len(m.accounts)}
	for _, acc := range m.accounts {
		if acc.createdAt.After(weekAgo) {
			ov.AccountsNew7d++
		}
		if acc.createdAt.After(monthAgo) {
			ov.AccountsNew30d++
		}
		if !acc.lastSeen.IsZero() && acc.lastSeen.After(dayAgo) {
			ov.AccountsActive24h++
		}
	}
	for _, devs := range m.devices {
		ov.Devices += len(devs)
	}
	for _, q := range m.queue {
		ov.QueueTotal += len(q)
	}
	ov.PushTokens = len(m.pushTokens)
	ov.Emails = len(m.emails)
	return ov, nil
}

// Админка: страница пользователей, свежие сначала (порядок как в pgstore)
func (m *memstore) AdminUsers(now time.Time, limit, offset int) ([]adminUser, int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	logins := make([]string, 0, len(m.accounts))
	for login := range m.accounts {
		logins = append(logins, login)
	}
	sort.Slice(logins, func(i, j int) bool {
		a, b := m.accounts[logins[i]], m.accounts[logins[j]]
		if a.createdAt.Equal(b.createdAt) {
			return logins[i] < logins[j]
		}
		return a.createdAt.After(b.createdAt)
	})
	total := len(logins)
	if offset > total {
		offset = total
	}
	end := offset + limit
	if end > total {
		end = total
	}
	out := make([]adminUser, 0, end-offset)
	for _, login := range logins[offset:end] {
		acc := m.accounts[login]
		row := adminUser{Login: login, CreatedAt: acc.createdAt, Devices: len(m.devices[login])}
		if !acc.lastSeen.IsZero() {
			seen := acc.lastSeen
			row.LastSeen = &seen
		}
		for key, q := range m.queue {
			if owner, _, _ := splitQueueKey(key); owner == login {
				row.Queue += len(q)
			}
		}
		for key := range m.pushTokens {
			if owner, _, _ := splitQueueKey(key); owner == login {
				row.PushTokens++
			}
		}
		for _, owner := range m.emails {
			if owner == login {
				row.HasEmail = true
				break
			}
		}
		out = append(out, row)
	}
	return out, total, nil
}

// Статистика использования в памяти не живёт: in-memory стор — dev и тесты,
// история есть только в Postgres. Буфер при этом копится и флашится в никуда
// нет — флашер стартует лишь для постоянного хранилища.
func (m *memstore) AddStats(day time.Time, service map[string]int64, users map[string]map[string]int64) error {
	return nil
}

func (m *memstore) StatsHistory(now time.Time, days int) ([]statsDay, error) {
	return []statsDay{}, nil
}

func (m *memstore) SavePushToken(login, deviceID, platform, token string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pushTokens[queueKey(login, deviceID)] = platform + ":" + token
	return nil
}

func (m *memstore) SaveEmailCode(email, code string, expiresAt time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	// новый код гасит прежние: валиден только последний (S3)
	fresh := m.emailCodes[:0]
	for _, ec := range m.emailCodes {
		if ec.email != email && ec.expiresAt.After(time.Now()) {
			fresh = append(fresh, ec)
		}
	}
	m.emailCodes = append(fresh, emailCode{email, code, expiresAt, false, 0})
	return nil
}

// проверка кода (2.3): подсчёт попыток и сгорание — как при потреблении,
// но ВЕРНЫЙ код не списывается: раскрытие «email занят» только после него
func (m *memstore) CheckEmailCode(email, code string, now time.Time) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	found := false
	for i := range m.emailCodes {
		ec := &m.emailCodes[i]
		if ec.email != email || ec.used || !ec.expiresAt.After(now) {
			continue
		}
		if ec.attempts >= maxCodeAttempts {
			ec.used = true // сгорел от перебора
			continue
		}
		if ec.code == code {
			found = true // использование — в ConsumeEmailCode (2.3)
		} else {
			ec.attempts++
			if ec.attempts >= maxCodeAttempts {
				ec.used = true // перебор исчерпан — код сгорает (S3)
			}
		}
	}
	return found
}

// проверка и потребление кода (S3): считает неудачные попытки,
// после maxCodeAttempts все живые коды email сгорают — перебор 4 цифр бесперспективен
func (m *memstore) ConsumeEmailCode(email, code string, now time.Time) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	found := false
	for i := range m.emailCodes {
		ec := &m.emailCodes[i]
		if ec.email != email || ec.used || !ec.expiresAt.After(now) {
			continue
		}
		if ec.attempts >= maxCodeAttempts {
			ec.used = true // сгорел от перебора
			continue
		}
		if ec.code == code {
			ec.used = true
			found = true
		} else {
			ec.attempts++
			if ec.attempts >= maxCodeAttempts {
				ec.used = true // перебор исчерпан — код сгорает (S3)
			}
		}
	}
	return found
}

// очередь исходящей почты (свой mini-MTA)
func (m *memstore) EnqueueMail(mail *outMail, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.nextMailID++
	mail.ID = m.nextMailID
	mail.CreatedAt = now
	if mail.NextAttempt.IsZero() {
		mail.NextAttempt = now
	}
	m.mailOutbox = append(m.mailOutbox, *mail)
	return nil
}

func (m *memstore) TakeMailDue(now time.Time, limit int) ([]outMail, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []outMail{}
	for _, mail := range m.mailOutbox {
		if len(out) >= limit {
			break
		}
		if !mail.NextAttempt.After(now) {
			out = append(out, mail)
		}
	}
	return out, nil
}

func (m *memstore) MailDelivered(id int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	fresh := m.mailOutbox[:0]
	for _, mail := range m.mailOutbox {
		if mail.ID != id {
			fresh = append(fresh, mail)
		}
	}
	m.mailOutbox = fresh
	return nil
}

func (m *memstore) MailFailed(id int64, attempts int, nextAttempt time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := range m.mailOutbox {
		if m.mailOutbox[i].ID == id {
			if attempts >= maxMailAttempts {
				m.mailOutbox = append(m.mailOutbox[:i], m.mailOutbox[i+1:]...)
			} else {
				m.mailOutbox[i].Attempts = attempts
				m.mailOutbox[i].NextAttempt = nextAttempt
			}
			return nil
		}
	}
	return nil
}

func (m *memstore) SweepMail(now time.Time) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	fresh := m.mailOutbox[:0]
	removed := 0
	for _, mail := range m.mailOutbox {
		// в очереди остаются только живые: просроченных «висячих» нет,
		// но на всякий случай чистим попытки старше суток
		if now.Sub(mail.CreatedAt) > mailOutboxTTL {
			removed++
			continue
		}
		fresh = append(fresh, mail)
	}
	m.mailOutbox = fresh
	return removed, nil
}

func (m *memstore) LoginByEmailHash(emailHash string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if login, ok := m.emails[emailHash]; ok {
		return login, nil
	}
	return "", nil
}

// AH9: смена email — все прежние хэши логина заменяются новым
func (m *memstore) ReplaceEmail(login, emailHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.emails == nil {
		m.emails = make(map[string]string)
	}
	for h, l := range m.emails {
		if l == login {
			delete(m.emails, h)
		}
	}
	m.emails[emailHash] = login
	return nil
}

func (m *memstore) FindEmailMatches(hashes []string) ([]EmailMatch, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []EmailMatch{}
	for _, h := range hashes {
		if login, ok := m.emails[h]; ok {
			out = append(out, EmailMatch{Login: login, EmailHash: h})
		}
	}
	return out, nil
}

// AI1: полный снос аккаунта — устройства с токенами, очередь всех
// устройств, пуш-токены, email-хэши, подтверждённые линк-записи
func (m *memstore) DeleteAccount(login string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.accounts[login]; !ok {
		return errAccountNotFound
	}
	delete(m.accounts, login)
	// токены устройств умирают вместе с устройствами
	for tok, auth := range m.byToken {
		if auth.login == login {
			delete(m.byToken, tok)
		}
	}
	delete(m.devices, login)
	for key := range m.queue {
		if strings.HasPrefix(key, login+"/") {
			delete(m.queue, key)
		}
	}
	for key := range m.pushTokens {
		if strings.HasPrefix(key, login+"/") {
			delete(m.pushTokens, key)
		}
	}
	for h, l := range m.emails {
		if l == login {
			delete(m.emails, h)
		}
	}
	for nonce, row := range m.links {
		if row.login == login {
			delete(m.links, nonce)
		}
	}
	return nil
}

// AI2: приватность присутствия
func (m *memstore) SetPresenceHidden(login string, hidden bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	acc, ok := m.accounts[login]
	if !ok {
		return errAccountNotFound
	}
	acc.hidePresence = hidden
	return nil
}

// AI2: время последнего разрыва WS
func (m *memstore) TouchLastSeen(login string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	acc, ok := m.accounts[login]
	if !ok {
		return errAccountNotFound // аккаунт могли снести между разрывами
	}
	acc.lastSeen = at
	return nil
}

// AJ: порог автоудаления при простое (0 — не удалять)
func (m *memstore) SetAccountInactivity(login string, months int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	acc, ok := m.accounts[login]
	if !ok {
		return errAccountNotFound
	}
	acc.inactivityMonths = months
	return nil
}

// AJ: логины, чей простой перевалил за их порог; база простоя —
// последний разрыв WS, у никогда не заходивших — время создания
func (m *memstore) InactiveAccountLogins(now time.Time) ([]string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []string
	for _, acc := range m.accounts {
		if acc.inactivityMonths <= 0 {
			continue
		}
		last := acc.lastSeen
		if last.IsZero() {
			last = acc.createdAt
		}
		if last.IsZero() || now.Sub(last) > time.Duration(acc.inactivityMonths)*30*24*time.Hour {
			out = append(out, acc.login)
		}
	}
	return out, nil
}
