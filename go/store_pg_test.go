package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"sort"
	"strings"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

// Персистентность pgstore: проверяется только при заданном TEST_DATABASE_URL.
// Прогон в песочнице: TEST_DATABASE_URL=postgres://chat:chat@127.0.0.1:5433/chat?sslmode=disable
func TestPgstoreSurvivesRestart(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE accounts, devices, link_pending, queue`); err != nil {
		t.Fatal(err)
	}

	acc := &account{login: "restart-user", pkdfKey: []byte("key"), salt: []byte("salt")}
	if conflict, err := store.CreateAccount(acc, "phone", "pub-1", "token-before-restart"); conflict || err != nil {
		t.Fatalf("создание аккаунта: conflict=%v err=%v", conflict, err)
	}
	if err := store.Enqueue(
		&envelope{From: "alice", To: "restart-user", ToDevice: "phone", Kind: "chat", Blob: "YmxvYg=="},
		time.Now(),
	); err != nil {
		t.Fatal(err)
	}
	// код линковки планшета, подтверждение придёт после рестарта
	if err := store.CreateLink("tablet", "pub-tablet", "nonce-1", hashToken("poll-secret-1")); err != nil {
		t.Fatal(err)
	}

	// «рестарт процесса»: хранилище закрывается и открывается заново
	store.Close()
	reopened, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()

	auth, err := reopened.AuthByToken("token-before-restart")
	if err != nil || auth == nil || auth.login != "restart-user" || auth.deviceID != "phone" {
		t.Fatalf("аккаунт/устройство не пережили рестарт: %+v err=%v", auth, err)
	}
	msgs, _, err := reopened.Drain("restart-user", "phone", time.Now(), drainDefaultLimit, drainByteCeiling, 0)
	if err != nil || len(msgs) != 1 || msgs[0].From != "alice" || msgs[0].ToDevice != "phone" {
		t.Fatalf("транзит не пережил рестарт: %v err=%v", msgs, err)
	}
	if again, more2, _ := reopened.Drain("restart-user", "phone", time.Now(), drainDefaultLimit, drainByteCeiling, 0); len(again) != 0 || more2 {
		t.Fatalf("дрейн должен очищать очередь, осталось %d", len(again))
	}
	// TOFU через рестарт
	if conflict, _ := reopened.PutDeviceKey("restart-user", "phone", "pub-attacker", ""); !conflict {
		t.Fatal("подмена ключа должна ловиться после рестарта")
	}
	if conflict, _ := reopened.PutDeviceKey("restart-user", "phone", "pub-1", ""); conflict {
		t.Fatal("повтор того же ключа не должен быть конфликтом")
	}
	// линковка через рестарт: pending выжил, подтверждение выдаёт токен
	if _, _, found, _ := reopened.LinkByNonce("nonce-1"); !found {
		t.Fatal("код линковки не пережил рестарт")
	}
	// 1.2: confirm токен не создаёт; свежий mint-ится при опросе (ConsumeLink)
	if err := reopened.ConfirmLink("nonce-1", "restart-user"); err != nil {
		t.Fatalf("confirm после рестарта: %v", err)
	}
	linkLogin, tabletToken, err := reopened.ConsumeLink("nonce-1")
	if err != nil || linkLogin != "restart-user" || tabletToken == "" {
		t.Fatalf("одноразовая выдача токена: %v %v %v", linkLogin, tabletToken, err)
	}
	// в link_pending сырого токена не было вовсе — выдача mint-ит свежий
	tabletAuth, err := reopened.AuthByToken(tabletToken)
	if err != nil || tabletAuth == nil || tabletAuth.deviceID != "tablet" {
		t.Fatalf("планшет не авторизуется после линковки: %+v err=%v", tabletAuth, err)
	}
	if _, _, err := reopened.ConsumeLink("nonce-1"); err == nil {
		t.Fatal("повторное потребление кода должно быть отклонено")
	}
}

// S3 в Postgres: новый код гасит прежний, перебор сжигает код,
// байтовая квота очереди вытесняет старейшие
func TestPgEmailCodesAndQueueQuota(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE email_codes, queue`); err != nil {
		t.Fatal(err)
	}
	now := time.Now()

	// новый код гасит прежний: валиден только последний
	if err := store.SaveEmailCode("pg@test.example", "1111", now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := store.SaveEmailCode("pg@test.example", "2222", now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if store.ConsumeEmailCode("pg@test.example", "1111", now) {
		t.Fatal("старый код должен быть погашен")
	}
	if !store.ConsumeEmailCode("pg@test.example", "2222", now) {
		t.Fatal("новый код обязан работать")
	}

	// перебор: 5 неудач — код сгорает даже при верной попытке
	if err := store.SaveEmailCode("brute@test.example", "3333", now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < maxCodeAttempts; i++ {
		if store.ConsumeEmailCode("brute@test.example", "9999", now) {
			t.Fatal("неверный код не должен проходить")
		}
	}
	if store.ConsumeEmailCode("brute@test.example", "3333", now) {
		t.Fatal("код обязан сгореть после перебора")
	}

	// байтовая квота: старейшие блобы вытесняются за пределами потолка.
	// Квота считает символы строки блоба — в проде это base64
	env := &envelope{From: "alice", To: "quota-user", ToDevice: "phone", Kind: "chat"}
	blob := func(i int) string {
		raw := []byte(fmt.Sprintf("%02d-%s", i, strings.Repeat("x", 480)))
		return base64.StdEncoding.EncodeToString(raw) // ~644 символа
	}
	old := maxQueueBytes
	maxQueueBytes = 3000 // уживаются 4 блоба по ~644 символа
	defer func() { maxQueueBytes = old }()
	for i := 0; i < 9; i++ {
		env.Blob = blob(i)
		if err := store.Enqueue(env, now); err != nil {
			t.Fatal(err)
		}
	}
	msgs, _, err := store.Drain("quota-user", "phone", time.Now(), drainDefaultLimit, drainByteCeiling, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 4 {
		t.Fatalf("квота должна оставить 4 блоба, получили %d", len(msgs))
	}
	// порядок дрейна = порядок вставки (S4): клиент присваивает ts
	// по очерёдности обработки, перемешанные строки ломали историю
	prefixes := make([]string, len(msgs))
	for i, m := range msgs {
		raw, err := base64.StdEncoding.DecodeString(m.Blob)
		if err != nil {
			t.Fatal(err)
		}
		prefixes[i] = string(raw[:3])
	}
	if got := strings.Join(prefixes, ","); got != "05-,06-,07-,08-" {
		t.Fatalf("очередь/порядок дрейна: %s", got)
	}
}

// S4 в Postgres: peek не удаляет, ack удаляет по id, пагинация с more
func TestPgAckQueue(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE queue`); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	env := &envelope{From: "alice", To: "ack-user", ToDevice: "phone", Kind: "chat"}
	for i := 0; i < 5; i++ {
		env.Blob = base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("m%d", i)))
		if err := store.Enqueue(env, now); err != nil {
			t.Fatal(err)
		}
	}
	// страница peek из 2: не удаляет, id на месте, more=true
	page, more, err := store.PeekQueue("ack-user", "phone", now, 2, 0, 0)
	if err != nil || len(page) != 2 || !more {
		t.Fatalf("peek: %d more=%v err=%v", len(page), more, err)
	}
	ids := []int64{page[0].Id, page[1].Id}
	again, _, err := store.PeekQueue("ack-user", "phone", now, 10, 0, 0)
	if err != nil || len(again) != 5 {
		t.Fatalf("peek не обязан удалять: %d err=%v", len(again), err)
	}
	// ack первых двух: осталось 3, id не повторяются
	removed, _, err := store.AckQueue("ack-user", "phone", ids, now)
	if err != nil || removed != 2 {
		t.Fatalf("ack: removed=%d err=%v", removed, err)
	}
	rest, _, err := store.Drain("ack-user", "phone", now, drainDefaultLimit, 0, 0)
	if err != nil || len(rest) != 3 {
		t.Fatalf("после ack обязано остаться 3: %d err=%v", len(rest), err)
	}
	for _, m := range rest {
		for _, id := range ids {
			if m.Id == id {
				t.Fatalf("подтверждённый id %d выдался снова", id)
			}
		}
	}
	// чужие id не удаляются
	if removed, _, _ := store.AckQueue("ack-user", "phone", []int64{999999}, now); removed != 0 {
		t.Fatalf("чужие id: removed=%d", removed)
	}
	// TTL-чистка: свежий транзит под возрастом не трогает, просроченный — исчезает
	env.Blob = "b2I="
	if err := store.Enqueue(env, now); err != nil {
		t.Fatal(err)
	}
	if removed, _ := store.SweepExpired(now); removed != 0 {
		t.Fatalf("свежий транзит не должен чиститься: %d", removed)
	}
	if removed, err := store.SweepExpired(now.Add(maxQueueAge + time.Hour)); err != nil || removed != 1 {
		t.Fatalf("sweep: removed=%d err=%v", removed, err)
	}
}

// ─── Раунд 7 ───

// 1.2: хэш токена из БД не логинит; легаси-хэш без префикса после
// разовой миграции при старте продолжает работать, сам легаси-хэш
// в роли Bearer — нет
func TestPgHashIsNotAToken(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE accounts, devices, link_pending`); err != nil {
		t.Fatal(err)
	}
	acc := &account{login: "hash-user", pkdfKey: []byte("k"), salt: []byte("s")}
	if conflict, err := store.CreateAccount(acc, "phone", "pub-1", "raw-token-1"); conflict || err != nil {
		t.Fatalf("создание: conflict=%v err=%v", conflict, err)
	}
	// в БД — хэш с префиксом версии
	var stored string
	if err := store.db.QueryRow(
		`SELECT token FROM devices WHERE login = 'hash-user' AND device_id = 'phone'`,
	).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(stored, "h1:") {
		t.Fatalf("хэш обязан храниться с префиксом версии: %q", stored)
	}
	// хэш из дампа в роли Bearer — не авторизует (прежде легаси-ветка принимала)
	if auth, _ := store.AuthByToken(stored); auth != nil {
		t.Fatalf("хэш из БД не должен логинить: %+v", auth)
	}
	// сырой токен — работает
	if auth, _ := store.AuthByToken("raw-token-1"); auth == nil || auth.deviceID != "phone" {
		t.Fatalf("сырой токен обязан работать: %+v", auth)
	}

	// легаси-строка без префикса (старое развёртывание, sha256-hex):
	// переоткрытие мигрирует её префиксом, легаси-токен продолжает работать
	legacySum := sha256.Sum256([]byte("legacy-token"))
	legacyHex := hex.EncodeToString(legacySum[:])
	if _, err := store.db.Exec(
		`UPDATE devices SET token = $1 WHERE login = 'hash-user'`, legacyHex,
	); err != nil {
		t.Fatal(err)
	}
	store.Close()
	reopened, err := openPg(dsn) // стартовая миграция добавляет префикс
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	var migrated string
	if err := reopened.db.QueryRow(
		`SELECT token FROM devices WHERE login = 'hash-user' AND device_id = 'phone'`,
	).Scan(&migrated); err != nil {
		t.Fatal(err)
	}
	if migrated != "h1:"+legacyHex {
		t.Fatalf("миграция обязана добавить префикс: %q", migrated)
	}
	if auth, _ := reopened.AuthByToken("legacy-token"); auth == nil || auth.deviceID != "phone" {
		t.Fatalf("легаси-токен после миграции обязан работать: %+v", auth)
	}
	if auth, _ := reopened.AuthByToken(legacyHex); auth != nil {
		t.Fatalf("легаси-хэш в роли Bearer — нет: %+v", auth)
	}
}

// 1.1: курсор ack-дрейна в Postgres — 450 сообщений по страницам без
// подтверждения, каждое ровно один раз
func TestPgAckCursor(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE queue`); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	env := &envelope{From: "alice", To: "cursor-user", ToDevice: "phone", Kind: "chat"}
	for i := 0; i < 450; i++ {
		env.Blob = base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("c%d", i)))
		if err := store.Enqueue(env, now); err != nil {
			t.Fatal(err)
		}
	}
	seen := map[int64]bool{}
	after := int64(0)
	for page := 0; page < 10; page++ {
		msgs, more, err := store.PeekQueue("cursor-user", "phone", now, 200, 0, after)
		if err != nil {
			t.Fatal(err)
		}
		if len(msgs) == 0 {
			if more {
				t.Fatal("more=true при пустой странице")
			}
			break
		}
		for _, m := range msgs {
			if m.Id <= after {
				t.Fatalf("id %d не новее курсора %d", m.Id, after)
			}
			if seen[m.Id] {
				t.Fatalf("сообщение %d выдано повторно", m.Id)
			}
			seen[m.Id] = true
		}
		after = msgs[len(msgs)-1].Id
		if !more {
			break
		}
	}
	if len(seen) != 450 {
		t.Fatalf("выдано %d из 450", len(seen))
	}
	// удаление по id: ack последних 50 — в очереди остаётся 400.
	// id — BIGSERIAL и после TRUNCATE продолжает счёт, порог абсолютным
	// быть не может — сортируем и берём хвост
	var all []int64
	for id := range seen {
		all = append(all, id)
	}
	sort.Slice(all, func(i, j int) bool { return all[i] < all[j] })
	ids := all[len(all)-50:]
	removed, ages, err := store.AckQueue("cursor-user", "phone", ids, now)
	if err != nil || removed != 50 || len(ages) != 50 {
		t.Fatalf("ack: removed=%d ages=%d err=%v", removed, len(ages), err)
	}
	var restLen int
	for {
		rest, more, err := store.Drain("cursor-user", "phone", now, drainDefaultLimit, drainByteCeiling, 0)
		if err != nil {
			t.Fatal(err)
		}
		restLen += len(rest)
		if !more {
			break
		}
	}
	if restLen != 400 {
		t.Fatalf("после ack обязано остаться 400: %d", restLen)
	}
}

// AH1/AH9: ревокация устройства и замена email в Postgres
func TestPgRevokeAndReplaceEmail(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE accounts, devices, user_emails, push_tokens`); err != nil {
		t.Fatal(err)
	}
	acc := &account{login: "revoke-user", pkdfKey: []byte("key"), salt: []byte("salt")}
	if conflict, err := store.CreateAccount(acc, "phone", "pub-1", "token-1"); conflict || err != nil {
		t.Fatalf("аккаунт: conflict=%v err=%v", conflict, err)
	}
	if err := store.SaveEmail("revoke-user", hashEmail("old@test.example")); err != nil {
		t.Fatal(err)
	}
	if conflict, err := store.PutDeviceKey("revoke-user", "tablet", "pub-2", "token-2"); conflict || err != nil {
		t.Fatalf("планшет: %v %v", conflict, err)
	}
	if err := store.SavePushToken("revoke-user", "tablet", "fcm", "push-2"); err != nil {
		t.Fatal(err)
	}

	// ревокация: строка devices, токен и пуш умирают разом
	removed, err := store.RemoveDevice("revoke-user", "tablet")
	if err != nil || !removed {
		t.Fatalf("ревокация: removed=%v err=%v", removed, err)
	}
	if auth, _ := store.AuthByToken("token-2"); auth != nil {
		t.Fatal("токен планшета жив после ревокации")
	}
	var pushCount int
	if err := store.db.QueryRow(
		`SELECT count(*) FROM push_tokens WHERE login='revoke-user' AND device_id='tablet'`,
	).Scan(&pushCount); err != nil || pushCount != 0 {
		t.Fatalf("пуш-токен жив: count=%d err=%v", pushCount, err)
	}
	if removed, _ := store.RemoveDevice("revoke-user", "tablet"); removed {
		t.Fatal("повторная ревокация не должна находить строку")
	}

	// замена email: старый хэш исчезает, новый резолвит логин
	if err := store.ReplaceEmail("revoke-user", hashEmail("new@test.example")); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.LoginByEmailHash(hashEmail("old@test.example")); got != "" {
		t.Fatalf("старый хэш жив: %q", got)
	}
	if got, _ := store.LoginByEmailHash(hashEmail("new@test.example")); got != "revoke-user" {
		t.Fatalf("новый хэш не резолвится: %q", got)
	}
}

// AI1/AI2 в Postgres: удаление аккаунта сносит все строки, приватность
// присутствия и время последнего визита переживают рестарт
func TestPgDeleteAccountAndPresence(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE accounts, devices, user_emails, push_tokens, queue, link_pending`); err != nil {
		t.Fatal(err)
	}
	acc := &account{login: "vanisher", pkdfKey: []byte("key"), salt: []byte("salt")}
	if conflict, err := store.CreateAccount(acc, "phone", "pub-1", "token-1"); conflict || err != nil {
		t.Fatalf("аккаунт: conflict=%v err=%v", conflict, err)
	}
	if conflict, _ := store.PutDeviceKey("vanisher", "tablet", "pub-2", "token-2"); conflict {
		t.Fatal("планшет не закрепился")
	}
	if err := store.SaveEmail("vanisher", hashEmail("vanish@test.example")); err != nil {
		t.Fatal(err)
	}
	if err := store.SavePushToken("vanisher", "phone", "fcm", "push-1"); err != nil {
		t.Fatal(err)
	}
	if err := store.Enqueue(
		&envelope{From: "alice", To: "vanisher", ToDevice: "phone", Kind: "chat", Blob: "YmxvYg=="},
		time.Now(),
	); err != nil {
		t.Fatal(err)
	}

	// приватность присутствия + время визита
	if err := store.SetPresenceHidden("vanisher", true); err != nil {
		t.Fatal(err)
	}
	moment := time.Now().Add(-time.Hour).UTC().Truncate(time.Second)
	if err := store.TouchLastSeen("vanisher", moment); err != nil {
		t.Fatal(err)
	}
	reopened, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	got, err := reopened.AccountByLogin("vanisher")
	if err != nil || got == nil {
		t.Fatalf("аккаунт пропал: %v", err)
	}
	if !got.hidePresence {
		t.Fatal("hide_presence не пережил рестарт")
	}
	if got.lastSeen.IsZero() || got.lastSeen.Unix() != moment.Unix() {
		t.Fatalf("last_seen не пережил рестарт: %v (ожидали %v)", got.lastSeen, moment)
	}

	// удаление: все строки умирают, логин освобождается
	if err := reopened.DeleteAccount("vanisher"); err != nil {
		t.Fatal(err)
	}
	if got, _ := reopened.AccountByLogin("vanisher"); got != nil {
		t.Fatal("аккаунт жив после удаления")
	}
	if auth, _ := reopened.AuthByToken("token-1"); auth != nil {
		t.Fatal("токен телефона жив после удаления")
	}
	if auth, _ := reopened.AuthByToken("token-2"); auth != nil {
		t.Fatal("токен планшета жив после удаления")
	}
	if got, _ := reopened.LoginByEmailHash(hashEmail("vanish@test.example")); got != "" {
		t.Fatal("email-хэш жив после удаления")
	}
	var queueCount, pushCount int
	_ = reopened.db.QueryRow(`SELECT count(*) FROM queue WHERE to_login='vanisher'`).Scan(&queueCount)
	_ = reopened.db.QueryRow(`SELECT count(*) FROM push_tokens WHERE login='vanisher'`).Scan(&pushCount)
	if queueCount != 0 || pushCount != 0 {
		t.Fatalf("очередь/пуши живы: queue=%d push=%d", queueCount, pushCount)
	}
	if err := reopened.DeleteAccount("vanisher"); err == nil {
		t.Fatal("повторное удаление должно быть errAccountNotFound")
	}
	// логин свободен
	if conflict, err := reopened.CreateAccount(
		&account{login: "vanisher", pkdfKey: []byte("k"), salt: []byte("s"), iters: 40000},
		"phone", "pub-3", "token-3"); conflict || err != nil {
		t.Fatalf("логин не освободился: conflict=%v err=%v", conflict, err)
	}
}

// AJ: порог простоя в Postgres — кандидат по COALESCE(last_seen, created_at),
// снос по пути DeleteAccount; порог 0 не сносит никогда.
func TestPgInactivitySweep(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL не задан — интеграция с Postgres пропущена")
	}
	store, err := openPg(dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.db.Exec(`TRUNCATE accounts, devices, user_emails, push_tokens, queue, link_pending`); err != nil {
		t.Fatal(err)
	}
	acc := &account{login: "sleeper", pkdfKey: []byte("key"), salt: []byte("salt")}
	if conflict, err := store.CreateAccount(acc, "phone", "pub-1", "token-1"); conflict || err != nil {
		t.Fatalf("аккаунт: conflict=%v err=%v", conflict, err)
	}
	// порог не задан — не кандидат даже через годы
	if err := store.TouchLastSeen("sleeper", time.Now().AddDate(-2, 0, 0)); err != nil {
		t.Fatal(err)
	}
	logins, err := store.InactiveAccountLogins(time.Now())
	if err != nil || len(logins) != 0 {
		t.Fatalf("без порога кандидат: %v err=%v", logins, err)
	}
	// порог 3 месяца + 4 месяца простоя — кандидат
	if err := store.SetAccountInactivity("sleeper", 3); err != nil {
		t.Fatal(err)
	}
	logins, err = store.InactiveAccountLogins(time.Now())
	if err != nil || len(logins) != 1 || logins[0] != "sleeper" {
		t.Fatalf("кандидаты: %v err=%v", logins, err)
	}
	if err := store.DeleteAccount("sleeper"); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.AccountByLogin("sleeper"); got != nil {
		t.Fatal("аккаунт жив после сноса")
	}
	logins, err = store.InactiveAccountLogins(time.Now())
	if err != nil || len(logins) != 0 {
		t.Fatalf("после сноса кандидат: %v err=%v", logins, err)
	}
	// никогда не заходивший: база — created_at, свежая — не кандидат
	acc2 := &account{login: "newbie", pkdfKey: []byte("key"), salt: []byte("salt")}
	if conflict, err := store.CreateAccount(acc2, "phone", "pub-2", "token-2"); conflict || err != nil {
		t.Fatalf("аккаунт-2: conflict=%v err=%v", conflict, err)
	}
	if err := store.SetAccountInactivity("newbie", 3); err != nil {
		t.Fatal(err)
	}
	logins, err = store.InactiveAccountLogins(time.Now())
	if err != nil || len(logins) != 0 {
		t.Fatalf("свежий без визитов уже кандидат: %v err=%v", logins, err)
	}
}
