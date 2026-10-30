// Хранилище состояния сервера: аккаунты, устройства, транзитная очередь.
// Одно устройство = свой ключ и свой токен (Этап 3); очередь — per-device.
// memstore — in-memory (тесты, дев); pgstore — прод: рестарты без потерь.
package main

import (
	"errors"
	"time"
)

var (
	errNotFound        = errors.New("не найдено")
	errLinkNotFound    = errors.New("код привязки не найден")
	errDeviceConflict  = errors.New("устройство уже закреплено с другим ключом")
	errAccountNotFound = errors.New("аккаунт не найден")
	// A5: квота отправителя в очереди получателя исчерпана — 429 отправителю
	errSenderQuota = errors.New("ваша квота в очереди получателя исчерпана")
)

type account struct {
	login   string
	pkdfKey []byte
	salt    []byte
	// S6: стоимость KDF записи; старые записи без числа — pbkdf2LegacyIterations,
	// при входе перехэшиваются до текущего потолка
	iters int
	// AI2: приватность присутствия — не отвечать «в сети» спрашивающим
	hidePresence bool
	// AI2: момент разрыва последнего WS-соединения (zero — не был)
	lastSeen time.Time
	// AJ: порог автоудаления при простое, месяцев; 0 — не удалять
	inactivityMonths int
	// AJ: время создания — база простоя у никогда не заходивших
	// (pgstore читает из created_at, memstore ставит при создании)
	createdAt time.Time
	// GH2 (аналог Cloud Password у Telegram): облачный пароль 2FA; nil — выключен
	cloudSalt []byte
	cloudKey  []byte
}

// совпадение из email-контакт-листа
type EmailMatch struct {
	Login     string `json:"login"`
	EmailHash string `json:"email_hash"`
}

// кто стоит за токеном
type deviceAuth struct {
	login    string
	deviceID string
}

type Store interface {
	// аккаунты: true = логин занят
	CreateAccount(acc *account, deviceID, pubSpki, token string) (bool, error)
	AccountByLogin(login string) (*account, error) // nil = не найден
	// S6: перехэш при входе со старой стоимостью KDF
	UpdateAccountHash(login string, salt, key []byte, iterations int) error
	// GH2: установка/снятие облачного пароля (nil key — снять)
	SetAccountCloudKey(login string, salt, key []byte) error
	DeviceExists(login, deviceID string) (bool, error)
	RotateToken(login, deviceID, token string) error
	AuthByToken(token string) (*deviceAuth, error)

	// ключи устройств (TOFU): true = ключ уже закреплён другой
	PutDeviceKey(login, deviceID, pubSpki, token string) (bool, error)
	ListDevices(login string) ([]deviceKey, error)
	// AH1: ревокация устройства — ключ, токен и пуш-токен умирают разом;
	// false = устройства не было
	RemoveDevice(login, deviceID string) (bool, error)

	// линковка нового устройства одноразовым кодом.
	// 2.5: pollSecret — одноразовый секрет опроса (QR несёт; храним хэш)
	CreateLink(deviceID, pubSpki, nonce, pollSecretHash string) error
	LinkByNonce(nonce string) (deviceID, pubSpki string, found bool, err error)
	// 2.5: сверка секрета опроса (false — нет кода или секрет не тот)
	VerifyLinkSecret(nonce, secret string) bool
	// 1.2: подтверждение не создаёт токен — его отmint-ит ConsumeLink;
	// в сторе сырого токена до выдачи нет
	ConfirmLink(nonce, login string) error
	// статус: linked=false → ждём подтверждения; errLinkNotFound → код исчез/истёк
	LinkStatus(nonce string) (login string, linked bool, err error)
	// потребление: poll mint-ит свежий токен и стирает строку —
	// одноразовая выдача без хранения токена в сторе
	ConsumeLink(nonce string) (login, token string, err error)

	// транзитная очередь per-device: TTL и лимиты применяются внутри.
	// S4: Drain удаляет и возвращает страницу (limit/байтовый потолок,
	// more=true — за страницей ещё есть живые сообщения); PeekQueue — то же
	// без удаления (id заполнены, для ack-режима); AckQueue удаляет по id
	// ПОСЛЕ записи клиентом в WAL — обрыв соединения не теряет сообщения;
	// SweepExpired чистит просроченный TTL.
	// 1.1: after — курсор ack-дрейна (id > after); AckQueue возвращает
	// возрасты удалённых записей — delivered в ack-режиме считается при
	// подтверждении, а не при повторных peek (2.6)
	Enqueue(env *envelope, now time.Time) error
	Drain(login, deviceID string, now time.Time, limit int, byteCeil int64, after int64) ([]envelope, bool, error)
	PeekQueue(login, deviceID string, now time.Time, limit int, byteCeil int64, after int64) ([]envelope, bool, error)
	AckQueue(login, deviceID string, ids []int64, now time.Time) (int, []float64, error)
	SweepExpired(now time.Time) (int, error)

	// пуш-токены устройств (wake-канал; контент в пушах не хранится)
	SavePushToken(login, deviceID, platform, token string) error

	// email-контакт-лист
	SaveEmail(login, emailHash string) error
	// AH9: смена email — все прежние хэши логина заменяются новым
	ReplaceEmail(login, emailHash string) error
	FindEmailMatches(hashes []string) ([]EmailMatch, error)
	LoginByEmailHash(emailHash string) (string, error)

	// AI1 (аналог Delete My Account): полный снос аккаунта — записи
	// ключей/токенов/пушей/очереди/email-хэшей. Медиа-блобы остаются:
	// владелец блоба серверу не известен принципиально
	DeleteAccount(login string) error

	// AI2 (аналог «в сети» у Telegram): приватность присутствия и
	// время последнего WS-разрыва
	SetPresenceHidden(login string, hidden bool) error
	TouchLastSeen(login string, at time.Time) error

	// AJ (аналог Delete account if inactive у Telegram): порог простоя
	// в месяцах (0 — не удалять) и кандидаты на снос по нему
	SetAccountInactivity(login string, months int) error
	InactiveAccountLogins(now time.Time) ([]string, error)

	// email-коды для регистрации
	SaveEmailCode(email, code string, expiresAt time.Time) error
	// 2.3: проверка кода БЕЗ списания (с подсчётом попыток) — факт
	// «email уже зарегистрирован» раскрывается только после верного кода
	CheckEmailCode(email, code string, now time.Time) bool
	ConsumeEmailCode(email, code string, now time.Time) bool

	// метрики
	QueueDepths() (map[string]int, error)
	AccountCount() (int, error)
}
