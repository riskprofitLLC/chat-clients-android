package main

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

// PostgreSQL-хранилище (прод): рестарт процесса не теряет данные.
// Драйвер pgx (MIT) — серверная зависимость; в клиенты не попадает.
// Схема v2 (мультидевайс, Этап 3): устройства — отдельная таблица,
// очередь per-device. Для обновления с v1 — новая база (прод ещё не задеплоен).

const schema = `
CREATE TABLE IF NOT EXISTS accounts (
  login         TEXT PRIMARY KEY,
  password_hash BYTEA NOT NULL,
  salt          BYTEA NOT NULL,
  pbdf_iters    INT NOT NULL DEFAULT 40000,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS devices (
  login     TEXT NOT NULL,
  device_id TEXT NOT NULL,
  pub_spki  TEXT NOT NULL,
  token     TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (login, device_id)
);
CREATE TABLE IF NOT EXISTS link_pending (
  nonce      TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL,
  pub_spki   TEXT NOT NULL,
  login      TEXT NOT NULL DEFAULT '',
  token      TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS queue (
  id         BIGSERIAL PRIMARY KEY,
  to_login   TEXT NOT NULL,
  to_device  TEXT NOT NULL,
  from_login TEXT NOT NULL,
  kind       TEXT NOT NULL DEFAULT 'chat',
  blob       TEXT NOT NULL,
  queued_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS queue_to_idx ON queue (to_login, to_device, id);
CREATE TABLE IF NOT EXISTS email_codes (
  email      TEXT NOT NULL,
  code       TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  used       BOOLEAN NOT NULL DEFAULT false,
  attempts   INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS email_codes_idx ON email_codes (email, code);
CREATE TABLE IF NOT EXISTS user_emails (
  login      TEXT NOT NULL,
  email_hash TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (login, email_hash)
);
CREATE TABLE IF NOT EXISTS push_tokens (
  login      TEXT NOT NULL,
  device_id  TEXT NOT NULL,
  platform   TEXT NOT NULL,
  token      TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (login, device_id)
);
`

type pgstore struct{ db *sql.DB }

func openPg(dsn string) (*pgstore, error) {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	// схема создаётся сама; ponytail: версионные миграции — при появлении версий схемы
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	// до-миграции старых баз: попытки кода (S3), стоимость KDF (S6),
	// префикс версии хэша токена (1.2), хэш секрета опроса (2.5).
	// Легаси-хэши sha256 получают префикс и продолжают работать; сырые
	// токены старых развёрток умирают — устройство перелинковывается
	// (сознательно: поиск по сырому значению делал хэш из дампа
	// работающим Bearer-токеном)
	for _, ddl := range []string{
		`ALTER TABLE email_codes ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0`,
		`ALTER TABLE accounts ADD COLUMN IF NOT EXISTS pbdf_iters INT NOT NULL DEFAULT 40000`,
		`ALTER TABLE link_pending ADD COLUMN IF NOT EXISTS poll_hash TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE accounts ADD COLUMN IF NOT EXISTS hide_presence INT NOT NULL DEFAULT 0`,
		`ALTER TABLE accounts ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ NULL`,
		`ALTER TABLE accounts ADD COLUMN IF NOT EXISTS inactivity_months INT NOT NULL DEFAULT 0`,
		`ALTER TABLE accounts ADD COLUMN IF NOT EXISTS cloud_salt BYTEA NULL`,
		`ALTER TABLE accounts ADD COLUMN IF NOT EXISTS cloud_key BYTEA NULL`,
		`UPDATE devices SET token = 'h1:' || token WHERE token <> '' AND token NOT LIKE 'h1:%'`,
	} {
		if _, err := db.Exec(ddl); err != nil {
			db.Close()
			return nil, err
		}
	}
	return &pgstore{db: db}, nil
}

func (p *pgstore) Close() error { return p.db.Close() }

func (p *pgstore) CreateAccount(acc *account, deviceID, pubSpki, token string) (bool, error) {
	tx, err := p.db.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	result, err := tx.Exec(
		`INSERT INTO accounts (login, password_hash, salt, pbdf_iters) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (login) DO NOTHING`,
		acc.login, acc.pkdfKey, acc.salt, acc.iters,
	)
	if err != nil {
		return false, err
	}
	inserted, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	if inserted == 0 {
		return true, nil // логин занят
	}
	// S6: токен в devices — только хэш; link_pending ниже хранит сырой
	// токен до одноразовой выдачи новому устройству
	_, err = tx.Exec(
		`INSERT INTO devices (login, device_id, pub_spki, token) VALUES ($1, $2, $3, $4)`,
		acc.login, deviceID, pubSpki, hashToken(token),
	)
	if err != nil {
		return false, err
	}
	return false, tx.Commit()
}

func (p *pgstore) AccountByLogin(login string) (*account, error) {
	var acc account
	var hide int
	err := p.db.QueryRow(
		`SELECT login, password_hash, salt, COALESCE(pbdf_iters, 40000),
		        COALESCE(hide_presence, 0), last_seen,
		        COALESCE(inactivity_months, 0), created_at, cloud_salt, cloud_key
		 FROM accounts WHERE login = $1`, login,
	).Scan(&acc.login, &acc.pkdfKey, &acc.salt, &acc.iters, &hide, &acc.lastSeen,
		&acc.inactivityMonths, &acc.createdAt, &acc.cloudSalt, &acc.cloudKey)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	acc.hidePresence = hide == 1
	return &acc, nil
}

// GH2: облачный пароль — отдельный хэш поверх основного
func (p *pgstore) SetAccountCloudKey(login string, salt, key []byte) error {
	_, err := p.db.Exec(
		`UPDATE accounts SET cloud_salt = $1, cloud_key = $2 WHERE login = $3`,
		salt, key, login,
	)
	return err
}

func (p *pgstore) DeviceExists(login, deviceID string) (bool, error) {
	var one int
	err := p.db.QueryRow(
		`SELECT 1 FROM devices WHERE login = $1 AND device_id = $2`, login, deviceID,
	).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func (p *pgstore) RotateToken(login, deviceID, token string) error {
	_, err := p.db.Exec(
		`UPDATE devices SET token = $1 WHERE login = $2 AND device_id = $3`,
		hashToken(token), login, deviceID, // S6: только хэш
	)
	return err
}

// перехэш аккаунта при входе со старой стоимостью KDF (S6)
func (p *pgstore) UpdateAccountHash(login string, salt, key []byte, iterations int) error {
	_, err := p.db.Exec(
		`UPDATE accounts SET salt = $1, password_hash = $2, pbdf_iters = $3 WHERE login = $4`,
		salt, key, iterations, login,
	)
	return err
}

func (p *pgstore) AuthByToken(token string) (*deviceAuth, error) {
	var auth deviceAuth
	// 1.2: ищем ТОЛЬКО по хэшу с префиксом версии; легаси-ветка поиска
	// по сырому значению удалена — хэш из дампа больше не логинит
	err := p.db.QueryRow(
		`SELECT login, device_id FROM devices WHERE token = $1`, hashToken(token),
	).Scan(&auth.login, &auth.deviceID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &auth, nil
}

// TOFU: обновление только того же ключа; RowsAffected=0 — подмена
func (p *pgstore) PutDeviceKey(login, deviceID, pubSpki, token string) (bool, error) {
	// пустой токен при регистрации нового устройства недопустим (иначе
	// hashToken("") стал бы валидным Bearer)
	var exists bool
	if err := p.db.QueryRow(
		`SELECT EXISTS(SELECT 1 FROM devices WHERE login=$1 AND device_id=$2)`,
		login, deviceID,
	).Scan(&exists); err != nil {
		return false, err
	}
	if !exists && token == "" {
		return false, fmt.Errorf("empty token")
	}
	result, err := p.db.Exec(
		`INSERT INTO devices (login, device_id, pub_spki, token) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (login, device_id) DO UPDATE
		 SET pub_spki = EXCLUDED.pub_spki WHERE devices.pub_spki = EXCLUDED.pub_spki`,
		login, deviceID, pubSpki, hashToken(token), // S6: только хэш
	)
	if err != nil {
		return false, err
	}
	updated, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	return updated == 0, nil
}

func (p *pgstore) ListDevices(login string) ([]deviceKey, error) {
	rows, err := p.db.Query(
		`SELECT device_id, pub_spki FROM devices WHERE login = $1 ORDER BY device_id`, login)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []deviceKey{}
	for rows.Next() {
		var key deviceKey
		if err := rows.Scan(&key.DeviceID, &key.PubSPKI); err != nil {
			return nil, err
		}
		out = append(out, key)
	}
	return out, rows.Err()
}

// AH1: ревокация устройства — ключ, токен и пуш-токен умирают разом
func (p *pgstore) RemoveDevice(login, deviceID string) (bool, error) {
	tx, err := p.db.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	res, err := tx.Exec(
		`DELETE FROM devices WHERE login = $1 AND device_id = $2`, login, deviceID)
	if err != nil {
		return false, err
	}
	removed, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	if removed == 0 {
		return false, nil
	}
	if _, err := tx.Exec(
		`DELETE FROM push_tokens WHERE login = $1 AND device_id = $2`, login, deviceID,
	); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

func (p *pgstore) CreateLink(deviceID, pubSpki, nonce, pollSecretHash string) error {
	tx, err := p.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// одноразовые коды старше суток вычищаем
	if _, err := tx.Exec(`DELETE FROM link_pending WHERE created_at < now() - interval '1 day'`); err != nil {
		return err
	}
	_, err = tx.Exec(
		`INSERT INTO link_pending (nonce, device_id, pub_spki, poll_hash) VALUES ($1, $2, $3, $4)`,
		nonce, deviceID, pubSpki, pollSecretHash,
	)
	if err != nil {
		return err
	}
	return tx.Commit()
}

// 2.5: сверка секрета опроса по хэшу
func (p *pgstore) VerifyLinkSecret(nonce, secret string) bool {
	var hash string
	err := p.db.QueryRow(
		`SELECT poll_hash FROM link_pending WHERE nonce = $1`, nonce,
	).Scan(&hash)
	if err != nil {
		return false
	}
	return hash != "" && hash == hashToken(secret)
}

func (p *pgstore) LinkByNonce(nonce string) (string, string, bool, error) {
	var deviceID, pub string
	err := p.db.QueryRow(
		`SELECT device_id, pub_spki FROM link_pending WHERE nonce = $1`, nonce,
	).Scan(&deviceID, &pub)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", false, nil
	}
	if err != nil {
		return "", "", false, err
	}
	return deviceID, pub, true, nil
}

// подтверждение старым устройством: токен НЕ создаётся и в БД не
// хранится (1.2) — его отmint-ит ConsumeLink при опросе новым устройством
func (p *pgstore) ConfirmLink(nonce, login string) error {
	tx, err := p.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var deviceID, pub string
	err = tx.QueryRow(
		`SELECT device_id, pub_spki FROM link_pending WHERE nonce = $1`, nonce,
	).Scan(&deviceID, &pub)
	if errors.Is(err, sql.ErrNoRows) {
		return errLinkNotFound
	}
	if err != nil {
		return err
	}
	// пред-проверка TOFU: устройство уже закреплено с другим ключом
	var existing string
	err = tx.QueryRow(
		`SELECT pub_spki FROM devices WHERE login = $1 AND device_id = $2`, login, deviceID,
	).Scan(&existing)
	if err == nil && existing != pub {
		return errDeviceConflict
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if _, err := tx.Exec(
		`UPDATE link_pending SET login = $1 WHERE nonce = $2`, login, nonce,
	); err != nil {
		return err
	}
	return tx.Commit()
}

func (p *pgstore) LinkStatus(nonce string) (string, bool, error) {
	var login string
	var confirmed bool
	err := p.db.QueryRow(
		`SELECT login, login <> '' FROM link_pending WHERE nonce = $1`, nonce,
	).Scan(&login, &confirmed)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, errLinkNotFound
	}
	if err != nil {
		return "", false, err
	}
	return login, confirmed, nil
}

// одноразовая выдача (1.2): свежий токен mint-ится здесь, хэш — в devices;
// в link_pending сырого токена не было вовсе
func (p *pgstore) ConsumeLink(nonce string) (string, string, error) {
	tx, err := p.db.Begin()
	if err != nil {
		return "", "", err
	}
	defer tx.Rollback()
	var login, deviceID, pub string
	err = tx.QueryRow(
		`SELECT login, device_id, pub_spki FROM link_pending
		  WHERE nonce = $1 AND login <> ''`, nonce,
	).Scan(&login, &deviceID, &pub)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", errLinkNotFound
	}
	if err != nil {
		return "", "", err
	}
	token, err := randomToken()
	if err != nil {
		return "", "", err
	}
	result, err := tx.Exec(
		`INSERT INTO devices (login, device_id, pub_spki, token) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (login, device_id) DO UPDATE
		 SET token = EXCLUDED.token WHERE devices.pub_spki = EXCLUDED.pub_spki`,
		login, deviceID, pub, hashToken(token),
	)
	if err != nil {
		return "", "", err
	}
	inserted, err := result.RowsAffected()
	if err != nil {
		return "", "", err
	}
	if inserted == 0 {
		return "", "", errDeviceConflict // устройство уже закреплено с другим ключом
	}
	if _, err := tx.Exec(`DELETE FROM link_pending WHERE nonce = $1`, nonce); err != nil {
		return "", "", err
	}
	return login, token, tx.Commit()
}

func (p *pgstore) Enqueue(env *envelope, now time.Time) error {
	tx, err := p.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// A5: квоты против флуда до вставки — потолок на пару (from→to-device)
	// и на число разных отправителей в очереди получателя
	var perSender int
	if err := tx.QueryRow(
		`SELECT COUNT(*) FROM queue WHERE to_login = $1 AND to_device = $2 AND from_login = $3`,
		env.To, env.ToDevice, env.From,
	).Scan(&perSender); err != nil {
		return err
	}
	if perSender >= maxQueuePerSender {
		return errSenderQuota
	}
	var senders int
	if err := tx.QueryRow(
		`SELECT COUNT(DISTINCT from_login) FROM queue WHERE to_login = $1 AND to_device = $2`,
		env.To, env.ToDevice,
	).Scan(&senders); err != nil {
		return err
	}
	if senders >= maxQueueSenders {
		var mine bool
		if err := tx.QueryRow(
			`SELECT EXISTS(SELECT 1 FROM queue WHERE to_login = $1 AND to_device = $2 AND from_login = $3)`,
			env.To, env.ToDevice, env.From,
		).Scan(&mine); err != nil {
			return err
		}
		if !mine {
			return errSenderQuota
		}
	}
	if _, err := tx.Exec(
		`INSERT INTO queue (to_login, to_device, from_login, kind, blob, queued_at)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		env.To, env.ToDevice, env.From, env.Kind, env.Blob, now,
	); err != nil {
		return err
	}
	// лимиты per-device (S4): по счётчику и по суммарному объёму — сверх
	// вытесняем старейшие, транзит не растёт до гигабайтов на устройство
	if _, err := tx.Exec(
		`DELETE FROM queue WHERE to_login = $1 AND to_device = $2 AND id NOT IN (
		   SELECT id FROM (
		     SELECT id, SUM(LENGTH(blob)) OVER (ORDER BY id DESC) AS running
		     FROM queue WHERE to_login = $1 AND to_device = $2
		   ) ranked WHERE running <= $3
		 )`,
		env.To, env.ToDevice, maxQueueBytes,
	); err != nil {
		return err
	}
	if _, err := tx.Exec(
		`DELETE FROM queue WHERE to_login = $1 AND to_device = $2 AND id NOT IN
		   (SELECT id FROM queue WHERE to_login = $1 AND to_device = $2 ORDER BY id DESC LIMIT $3)`,
		env.To, env.ToDevice, maxQueue,
	); err != nil {
		return err
	}
	return tx.Commit()
}

// дрейн: атомарно удаляет и возвращает живые сообщения устройства (TTL внутри)
// в ПОРЯДКЕ вставки, страницами по счётчику/байтам (S4): сначала выбор по id,
// затем удаление по явному списку, без гонок с параллельными Enqueue
func (p *pgstore) Drain(
	login, deviceID string, now time.Time, limit int, byteCeil int64, after int64,
) ([]envelope, bool, error) {
	tx, err := p.db.Begin()
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback()
	items, more, err := peekTx(tx, login, deviceID, now, limit, byteCeil, after)
	if err != nil {
		return nil, false, err
	}
	if err := ackTx(tx, login, deviceID, items); err != nil {
		return nil, false, err
	}
	out := make([]envelope, len(items))
	for i, it := range items {
		it.env.Id = it.id // S4: id нужен клиенту для подтверждения
		out[i] = it.env
	}
	return out, more, tx.Commit()
}

// просмотр без удаления (S4: ack-режим) — id заполнены для подтверждения
func (p *pgstore) PeekQueue(
	login, deviceID string, now time.Time, limit int, byteCeil int64, after int64,
) ([]envelope, bool, error) {
	tx, err := p.db.Begin()
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback()
	items, more, err := peekTx(tx, login, deviceID, now, limit, byteCeil, after)
	if err != nil {
		return nil, false, err
	}
	out := make([]envelope, len(items))
	for i, it := range items {
		it.env.Id = it.id // S4: id нужен клиенту для подтверждения
		out[i] = it.env
	}
	return out, more, tx.Rollback()
}

// подтверждение: удаляем СВОИ записи по id (S4); 2.6: возрасты удалённых —
// delivered в ack-режиме считается здесь, а не при повторных peek
func (p *pgstore) AckQueue(login, deviceID string, ids []int64, now time.Time) (int, []float64, error) {
	if len(ids) == 0 {
		return 0, nil, nil
	}
	tx, err := p.db.Begin()
	if err != nil {
		return 0, nil, err
	}
	defer tx.Rollback()
	rows, err := tx.Query(
		`DELETE FROM queue WHERE to_login = $1 AND to_device = $2 AND id = ANY($3)
		  RETURNING queued_at`,
		login, deviceID, ids,
	)
	if err != nil {
		return 0, nil, err
	}
	var ages []float64
	for rows.Next() {
		var queuedAt time.Time
		if err := rows.Scan(&queuedAt); err != nil {
			rows.Close()
			return 0, nil, err
		}
		ageMs := now.Sub(queuedAt).Seconds() * 1000
		if ageMs < 0 {
			ageMs = 0
		}
		ages = append(ages, ageMs)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, nil, err
	}
	return len(ages), ages, tx.Commit()
}

// периодическая чистка просроченного транзита (S4)
func (p *pgstore) SweepExpired(now time.Time) (int, error) {
	result, err := p.db.Exec(
		`DELETE FROM queue WHERE queued_at <= $1`, now.Add(-maxQueueAge),
	)
	if err != nil {
		return 0, err
	}
	removed, err := result.RowsAffected()
	return int(removed), err
}

// элемент очереди с id (S4)
type queuedItem struct {
	id  int64
	env envelope
}

// выбор живой страницы в порядке вставки (S4): лимит по счётчику и байтам;
// more=true — за страницей есть ещё живые сообщения. DELETE…RETURNING
// порядок не гарантирует, а клиент присваивает ts по очерёдности.
// 1.1: after — курсор ack-дрейна, записи с id ≤ after не выдаются
func peekTx(
	tx *sql.Tx, login, deviceID string, now time.Time, limit int, byteCeil int64, after int64,
) ([]queuedItem, bool, error) {
	if limit <= 0 {
		limit = drainDefaultLimit
	}
	// +1 строка: индикатор того, что за страницей есть ещё
	rows, err := tx.Query(
		`SELECT id, from_login, to_login, to_device, kind, blob, queued_at
		  FROM queue WHERE to_login = $1 AND to_device = $2 AND queued_at > $3
		    AND id > $5
		  ORDER BY id
		  LIMIT $4`,
		login, deviceID, now.Add(-maxQueueAge), limit+1, after,
	)
	if err != nil {
		return nil, false, err
	}
	items := []queuedItem{}
	var total int64
	more := false
	for rows.Next() {
		var it queuedItem
		if err := rows.Scan(
			&it.id, &it.env.From, &it.env.To, &it.env.ToDevice,
			&it.env.Kind, &it.env.Blob, &it.env.QueuedAt,
		); err != nil {
			rows.Close()
			return nil, false, err
		}
		size := int64(len(it.env.Blob))
		// страница закрыта по счётчику или по байтам; первый элемент входит
		// ВСЕГДА — иначе клиент завис бы на блобе больше потолка
		if len(items) >= limit || (byteCeil > 0 && total+size > byteCeil && len(items) > 0) {
			more = true
			break
		}
		items = append(items, it)
		total += size
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	return items, more, nil
}

// удаление по явному списку id внутри транзакции дрейна (S4)
func ackTx(tx *sql.Tx, login, deviceID string, items []queuedItem) error {
	if len(items) == 0 {
		return nil
	}
	ids := make([]int64, len(items))
	for i, it := range items {
		ids[i] = it.id
	}
	_, err := tx.Exec(
		`DELETE FROM queue WHERE to_login = $1 AND to_device = $2 AND id = ANY($3)`,
		login, deviceID, ids,
	)
	return err
}

func (p *pgstore) QueueDepths() (map[string]int, error) {
	rows, err := p.db.Query(`SELECT to_login, COUNT(*) FROM queue GROUP BY to_login`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var login string
		var depth int
		if err := rows.Scan(&login, &depth); err != nil {
			return nil, err
		}
		out[login] = depth
	}
	return out, rows.Err()
}

func (p *pgstore) AccountCount() (int, error) {
	var count int
	err := p.db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&count)
	return count, err
}

func (p *pgstore) SaveEmailCode(email, code string, expiresAt time.Time) error {
	// чистим истёкшие; новый код гасит прежние для email — валиден
	// только последний (S3)
	if _, err := p.db.Exec(`DELETE FROM email_codes WHERE expires_at < now() OR email = $1`, email); err != nil {
		return err
	}
	_, err := p.db.Exec(
		`INSERT INTO email_codes (email, code, expires_at) VALUES ($1, $2, $3)`,
		email, code, expiresAt,
	)
	return err
}

// проверка кода без списания (2.3): попытки считаются и код сгорает
// от перебора как при потреблении, но верный код остаётся живым
func (p *pgstore) CheckEmailCode(email, code string, now time.Time) bool {
	tx, err := p.db.Begin()
	if err != nil {
		return false
	}
	defer tx.Rollback()
	if _, err := tx.Exec(
		`UPDATE email_codes SET attempts = attempts + 1
		  WHERE email = $1 AND NOT used AND expires_at > $2 AND code <> $3`,
		email, now, code,
	); err != nil {
		return false
	}
	if _, err := tx.Exec(
		`UPDATE email_codes SET used = true
		  WHERE email = $1 AND NOT used AND expires_at > $2 AND attempts >= $3`,
		email, now, maxCodeAttempts,
	); err != nil {
		return false
	}
	// верный код жив (не списан), не сожжён перебором
	var ok bool
	err = tx.QueryRow(
		`SELECT EXISTS (SELECT 1 FROM email_codes
		  WHERE email = $1 AND code = $2 AND NOT used AND expires_at > $3
		    AND NOT EXISTS (SELECT 1 FROM email_codes
		      WHERE email = $1 AND NOT used AND expires_at > $3 AND attempts >= $4))`,
		email, code, now, maxCodeAttempts,
	).Scan(&ok)
	if err != nil {
		return false
	}
	_ = tx.Commit()
	return ok
}

// проверка и потребление кода (S3): неудачные попытки считаются,
// после maxCodeAttempts живые коды email сгорают — перебор бесперспективен
func (p *pgstore) ConsumeEmailCode(email, code string, now time.Time) bool {
	tx, err := p.db.Begin()
	if err != nil {
		return false
	}
	defer tx.Rollback()
	// правильный код: живой и не сожжён перебором (для email валиден
	// только последний код — SaveEmailCode стирает прежние)
	result, err := tx.Exec(
		`UPDATE email_codes SET used = true
		 WHERE email = $1 AND code = $2 AND NOT used AND expires_at > $3
		   AND NOT EXISTS (
		     SELECT 1 FROM email_codes
		     WHERE email = $1 AND NOT used AND expires_at > $3 AND attempts >= $4
		   )`,
		email, code, now, maxCodeAttempts,
	)
	if err != nil {
		return false
	}
	rows, err := result.RowsAffected()
	if err == nil && rows > 0 {
		return tx.Commit() == nil
	}
	// неверный код: попытка считается у всех живых кодов email
	if _, err := tx.Exec(
		`UPDATE email_codes SET attempts = attempts + 1
		 WHERE email = $1 AND NOT used AND expires_at > $2`,
		email, now,
	); err != nil {
		return false
	}
	// сгоревшие от перебора гасим сразу
	if _, err := tx.Exec(
		`UPDATE email_codes SET used = true
		 WHERE email = $1 AND NOT used AND attempts >= $2`,
		email, maxCodeAttempts,
	); err != nil {
		return false
	}
	_ = tx.Commit()
	return false
}

func (p *pgstore) LoginByEmailHash(emailHash string) (string, error) {
	var login string
	err := p.db.QueryRow(
		`SELECT login FROM user_emails WHERE email_hash = $1`, emailHash,
	).Scan(&login)
	// S6: «нет строки» — нормальный пустой результат; ошибка БД — ошибка
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return login, nil
}

func (p *pgstore) SaveEmail(login, emailHash string) error {
	_, err := p.db.Exec(
		`INSERT INTO user_emails (login, email_hash) VALUES ($1, $2)
		 ON CONFLICT (login, email_hash) DO NOTHING`,
		login, emailHash,
	)
	return err
}

// AH9: смена email — все прежние хэши логина заменяются новым
func (p *pgstore) ReplaceEmail(login, emailHash string) error {
	tx, err := p.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM user_emails WHERE login = $1`, login); err != nil {
		return err
	}
	if _, err := tx.Exec(
		`INSERT INTO user_emails (login, email_hash) VALUES ($1, $2)`,
		login, emailHash,
	); err != nil {
		return err
	}
	return tx.Commit()
}

func (p *pgstore) FindEmailMatches(hashes []string) ([]EmailMatch, error) {
	if len(hashes) == 0 {
		return []EmailMatch{}, nil
	}
	rows, err := p.db.Query(
		`SELECT login, email_hash FROM user_emails WHERE email_hash = ANY($1)`,
		hashes,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []EmailMatch{}
	for rows.Next() {
		var m EmailMatch
		if err := rows.Scan(&m.Login, &m.EmailHash); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (p *pgstore) SavePushToken(login, deviceID, platform, token string) error {
	_, err := p.db.Exec(
		`INSERT INTO push_tokens (login, device_id, platform, token) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (login, device_id) DO UPDATE SET platform = EXCLUDED.platform, token = EXCLUDED.token`,
		login, deviceID, platform, token,
	)
	return err
}

// AI1: полный снос аккаунта одной транзакцией — устройства с токенами,
// очередь всех устройств, пуш-токены, email-хэши, подтверждённые
// линк-записи. Медиа-блобы не трогаем: владелец блоба серверу неизвестен
func (p *pgstore) DeleteAccount(login string) error {
	tx, err := p.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`DELETE FROM accounts WHERE login = $1`, login)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return errAccountNotFound
	}
	for _, q := range []string{
		`DELETE FROM devices WHERE login = $1`,
		`DELETE FROM queue WHERE to_login = $1`,
		`DELETE FROM push_tokens WHERE login = $1`,
		`DELETE FROM user_emails WHERE login = $1`,
		`DELETE FROM link_pending WHERE login = $1`,
	} {
		if _, err := tx.Exec(q, login); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// AI2: приватность присутствия
func (p *pgstore) SetPresenceHidden(login string, hidden bool) error {
	flag := 0
	if hidden {
		flag = 1
	}
	_, err := p.db.Exec(`UPDATE accounts SET hide_presence = $1 WHERE login = $2`, flag, login)
	return err
}

// AI2: время последнего разрыва WS
// AJ: порог автоудаления при простое (0 — не удалять)
func (p *pgstore) SetAccountInactivity(login string, months int) error {
	_, err := p.db.Exec(`UPDATE accounts SET inactivity_months = $1 WHERE login = $2`, months, login)
	return err
}

// AJ: кандидаты на снос — простой от последнего визита (у никогда не
// заходивших — от создания) превысил их порог
func (p *pgstore) InactiveAccountLogins(now time.Time) ([]string, error) {
	rows, err := p.db.Query(
		`SELECT login FROM accounts
		 WHERE inactivity_months > 0
		   AND COALESCE(last_seen, created_at) < $1::timestamptz - (inactivity_months * interval '30 days')`,
		now,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var login string
		if err := rows.Scan(&login); err != nil {
			return nil, err
		}
		out = append(out, login)
	}
	return out, rows.Err()
}

func (p *pgstore) TouchLastSeen(login string, at time.Time) error {
	_, err := p.db.Exec(`UPDATE accounts SET last_seen = $1 WHERE login = $2`, at, login)
	return err
}
