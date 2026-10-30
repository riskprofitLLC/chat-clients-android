package main

import (
	"reflect"
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// прямой вызов byBearer для проверки стора токенов
func (s *server) byBearerRaw(token string) (*deviceAuth, bool) {
	req := httptest.NewRequest("GET", "/v1/messages", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	return s.byBearer(req)
}

func testServer(t *testing.T) *httptest.Server {
	t.Helper()
	// dev-режим: тестам регистрации нужен код в ответе (S3)
	t.Setenv("CHAT_DEV_MODE", "1")
	return httptest.NewServer(newServer().routes())
}

func mustHex(s string) []byte {
	raw, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return raw
}

// Векторы PBKDF2-HMAC-SHA-256 из RFC 7914, раздел 11
func TestPbkdf2Rfc7914Vectors(t *testing.T) {
	v1 := mustHex("55ac046e56e3089fec1691c22544b605" +
		"f94185216dde0465e68b9d57c20dacbc" +
		"49ca9cccf179b645991664b39d77ef31" +
		"7c71b845b1e30bd509112041d3a19783")
	if got := pbkdf2([]byte("passwd"), []byte("salt"), 1, 64); !bytes.Equal(got, v1) {
		t.Fatalf("вектор 1: %x", got)
	}
	v2 := mustHex("4ddcd8f60b98be21830cee5ef22701f9" +
		"641a4418d04c0414aeff08876b34ab56" +
		"a1d425a1225833549adb841b51c9b317" +
		"6a272bdebba1d078478f62b397f33c8d")
	if got := pbkdf2([]byte("Password"), []byte("NaCl"), 80_000, 64); !bytes.Equal(got, v2) {
		t.Fatalf("вектор 2: %x", got)
	}
}

func registerUser(t *testing.T, ts *httptest.Server, login, device string) (token, pubB64 string) {
	t.Helper()
	email := login + "@test.example"

	// шаг 1: получаем код (в тестовом режиме он в ответе)
	codeBody, _ := json.Marshal(map[string]string{"email": email})
	codeResp, err := ts.Client().Post(ts.URL+"/v1/email-code", "application/json", bytes.NewReader(codeBody))
	if err != nil {
		t.Fatal(err)
	}
	defer codeResp.Body.Close()
	var codeOut struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(codeResp.Body).Decode(&codeOut); err != nil {
		t.Fatal(err)
	}
	if codeOut.Code == "" {
		t.Fatal("email-code: код не получен")
	}

	// шаг 2: регистрируемся с кодом
	pubB64 = base64.StdEncoding.EncodeToString([]byte("pub-" + login))
	body, _ := json.Marshal(accountRequest{
		Password: "secret123", DeviceID: device, PubSPKI: pubB64,
		Email: email, Code: codeOut.Code,
	})
	resp, err := ts.Client().Post(ts.URL+"/v1/accounts", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("регистрация %s: статус %d", login, resp.StatusCode)
	}
	var out struct {
		Token  string `json:"token"`
		UserId string `json:"user_id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	_ = out.UserId // логин авто-генерируется
	return out.Token, pubB64
}

func authedJSON(t *testing.T, ts *httptest.Server, method, path, token string, body any) (*http.Response, string) {
	t.Helper()
	var raw []byte
	if body != nil {
		raw, _ = json.Marshal(body)
	}
	req, _ := http.NewRequest(method, ts.URL+path, bytes.NewReader(raw))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	rawResp, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return resp, string(rawResp)
}

func tokenFor(t *testing.T, ts *httptest.Server, login string) string {
	t.Helper()
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: login, Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("логин %s: %d", login, resp.StatusCode)
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatal(err)
	}
	return out.Token
}

func sendBlob(t *testing.T, ts *httptest.Server, token, to, kind, device, blob string) int {
	t.Helper()
	body := map[string]any{
		"to_user": to,
		"blobs":   []map[string]string{{"device_id": device, "blob": blob}},
	}
	if kind != "" {
		body["kind"] = kind
	}
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/messages", token, body)
	return resp.StatusCode
}

// полный дрейн: страницы крутим до more=false (S4), как реальный клиент
func drainAuth(t *testing.T, ts *httptest.Server, token string) []envelope {
	t.Helper()
	var all []envelope
	for {
		resp, body := authedJSON(t, ts, http.MethodGet, "/v1/messages", token, nil)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("дрейн: статус %d", resp.StatusCode)
		}
		var result drainResult
		if err := json.Unmarshal([]byte(body), &result); err != nil {
			t.Fatal(err)
		}
		all = append(all, result.Messages...)
		if !result.More {
			return all
		}
	}
}

func TestRegisterLoginFlow(t *testing.T) {
	// два кода на один email подряд: пауза в 60 с в тесте не нужна
	srv := newServer()
	srv.emailCooldown = 0
	t.Setenv("CHAT_DEV_MODE", "1")
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	token, _ := registerUser(t, ts, "alice", "phone")

	// повторная регистрация с тем же email → нужен новый код + ожидаем 409
	codeBody, _ := json.Marshal(map[string]string{"email": "alice@test.example"})
	codeResp, err := ts.Client().Post(ts.URL+"/v1/email-code", "application/json", bytes.NewReader(codeBody))
	if err != nil {
		t.Fatal(err)
	}
	var codeOut struct {
		Code string `json:"code"`
	}
	json.NewDecoder(codeResp.Body).Decode(&codeOut)
	codeResp.Body.Close()
	if codeOut.Code == "" {
		t.Fatal("email-code для повторной: код не получен")
	}

	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Password: "secret123", DeviceID: "phone", PubSPKI: "cHViLWFsaWNl",
		Email: "alice@test.example", Code: codeOut.Code,
	})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("повторная регистрация: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "wrong-pass", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("неверный пароль: %d", resp.StatusCode)
	}

	// непривязанное устройство не входит
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "secret123", DeviceID: "tablet",
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("чужое устройство: %d", resp.StatusCode)
	}

	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("логин: %d", resp.StatusCode)
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatal(err)
	}
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/messages", token, nil)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("старый токен должен умереть: %d", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/messages", out.Token, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("новый токен должен работать: %d", resp.StatusCode)
	}
}

func TestKeysRequireAuthAndTofu(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	token, pubB64 := registerUser(t, ts, "alice", "phone")

	resp, _ := authedJSON(t, ts, http.MethodPut, "/v1/keys/phone", "", deviceKey{PubSPKI: "cHVi"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без авторизации: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPut, "/v1/keys/tablet", token, deviceKey{PubSPKI: "cHVi"})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("чужое устройство: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPut, "/v1/keys/phone", token, deviceKey{PubSPKI: pubB64})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("повтор того же ключа: %d", resp.StatusCode)
	}

	attacker := base64.StdEncoding.EncodeToString([]byte("pub-attacker"))
	resp, _ = authedJSON(t, ts, http.MethodPut, "/v1/keys/phone", token, deviceKey{PubSPKI: attacker})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("подмена ключа должна быть 409: %d", resp.StatusCode)
	}

	resp, body := authedJSON(t, ts, http.MethodGet, "/v1/keys/alice", "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, pubB64) {
		t.Fatalf("листинг ключей: %d %s", resp.StatusCode, body)
	}
}

func TestRelayDeliversOnceAndBindsSender(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	aliceToken, _ := registerUser(t, ts, "alice", "phone")
	bobToken, _ := registerUser(t, ts, "bob", "phone")

	blob := base64.StdEncoding.EncodeToString([]byte("opaque-blob"))
	// подделка from_user игнорируется: отправитель = владелец токена
	body := map[string]any{
		"from_user": "bob", "to_user": "bob",
		"blobs": []map[string]string{{"device_id": "phone", "blob": blob}},
	}
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/messages", aliceToken, body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("отправка: %d", resp.StatusCode)
	}

	msgs := drainAuth(t, ts, bobToken)
	if len(msgs) != 1 || msgs[0].Blob != blob || msgs[0].From != "alice" || msgs[0].Kind != "chat" {
		t.Fatalf("ожидали 1 chat-сообщение от alice, получили %+v", msgs)
	}
	if again := drainAuth(t, ts, bobToken); len(again) != 0 {
		t.Fatalf("дрейн должен очищать очередь, осталось %d", len(again))
	}
	if own := drainAuth(t, ts, aliceToken); len(own) != 0 {
		t.Fatalf("алиса не должна видеть чужую очередь: %d", len(own))
	}
}

func TestRelayRequiresAuthAndRejectsGarbage(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")

	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/messages", "", map[string]any{
		"to_user": "bob", "blobs": []map[string]string{{"device_id": "phone", "blob": "YWJj"}},
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("отправка без авторизации: %d", resp.StatusCode)
	}

	aliceToken := tokenFor(t, ts, "alice")
	// пустой список блобов
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages", aliceToken, map[string]any{
		"to_user": "bob", "blobs": []map[string]string{},
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("пустой fan-out: %d", resp.StatusCode)
	}
	// неизвестный kind
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages", aliceToken, map[string]any{
		"to_user": "bob", "kind": "evil", "blobs": []map[string]string{{"device_id": "phone", "blob": "YWJj"}},
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("неизвестный kind: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Login: "carol", Password: "secret123", DeviceID: "phone", PubSPKI: "не-base64!!!",
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("некорректный ключ: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Login: "A!", Password: "secret123", DeviceID: "phone", PubSPKI: "cHVi",
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("некорректный логин: %d", resp.StatusCode)
	}
}

// Полный цикл Этапа 3: линковка второго устройства, fan-out, изоляция,
// перенос истории, перезаход по паролю, TOFU на повторной линковке
func TestLinkDeviceFlow(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	aliceToken, _ := registerUser(t, ts, "alice", "phone")
	bobToken, _ := registerUser(t, ts, "bob", "phone")

	tabletPub := base64.StdEncoding.EncodeToString([]byte("pub-tablet"))

	// 1) новое устройство публикует ключ под одноразовым кодом
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/link/init", "", linkInitRequest{
		DeviceID: "tablet", PubSPKI: tabletPub,
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("link/init: %d", resp.StatusCode)
	}
	var init struct {
		Nonce string `json:"nonce"`
	}
	if err := json.Unmarshal([]byte(body), &init); err != nil {
		t.Fatal(err)
	}

	// 2) до подтверждения — pending
	resp, body = authedJSON(t, ts, http.MethodGet, "/v1/link/"+init.Nonce, "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"pending"`) {
		t.Fatalf("poll до подтверждения: %d %s", resp.StatusCode, body)
	}

	// 3) подтверждение требует авторизации
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/link/confirm", "", map[string]string{
		"nonce": init.Nonce, "pub_spki": tabletPub,
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("confirm без авторизации: %d", resp.StatusCode)
	}

	// 4) ключ в подтверждении обязан совпасть с ключом из кода (анти-MITM)
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/link/confirm", aliceToken, map[string]string{
		"nonce": init.Nonce, "pub_spki": base64.StdEncoding.EncodeToString([]byte("pub-forged")),
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("подмена ключа в confirm должна быть 400: %d", resp.StatusCode)
	}

	// 5) старое устройство подтверждает
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/link/confirm", aliceToken, map[string]string{
		"nonce": init.Nonce, "pub_spki": tabletPub,
	})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"alice"`) {
		t.Fatalf("confirm: %d %s", resp.StatusCode, body)
	}

	// 6) новое устройство забирает токен; выдача одноразовая
	resp, body = authedJSON(t, ts, http.MethodGet, "/v1/link/"+init.Nonce, "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"linked"`) {
		t.Fatalf("poll после подтверждения: %d %s", resp.StatusCode, body)
	}
	var linked struct {
		Token  string `json:"token"`
		UserID string `json:"user_id"`
	}
	if err := json.Unmarshal([]byte(body), &linked); err != nil {
		t.Fatal(err)
	}
	if linked.Token == "" || linked.UserID != "alice" {
		t.Fatalf("токен не выдан: %s", body)
	}
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/link/"+init.Nonce, "", nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("повторный poll должен быть 400: %d", resp.StatusCode)
	}
	tabletToken := linked.Token

	// 7) fan-out: боб шифрует обоим устройствам алисы, очереди изолированы
	phoneBlob := base64.StdEncoding.EncodeToString([]byte("for-phone"))
	tabletBlob := base64.StdEncoding.EncodeToString([]byte("for-tablet"))
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages", bobToken, map[string]any{
		"to_user": "alice",
		"blobs": []map[string]string{
			{"device_id": "phone", "blob": phoneBlob},
			{"device_id": "tablet", "blob": tabletBlob},
		},
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("fan-out: %d", resp.StatusCode)
	}
	msgs := drainAuth(t, ts, aliceToken)
	if len(msgs) != 1 || msgs[0].Blob != phoneBlob || msgs[0].ToDevice != "phone" {
		t.Fatalf("телефон получил не своё: %+v", msgs)
	}
	msgs = drainAuth(t, ts, tabletToken)
	if len(msgs) != 1 || msgs[0].Blob != tabletBlob || msgs[0].ToDevice != "tablet" {
		t.Fatalf("планшет получил не своё: %+v", msgs)
	}
	if again := drainAuth(t, ts, aliceToken); len(again) != 0 {
		t.Fatalf("очереди устройств должны быть изолированы: %d", len(again))
	}

	// 8) self-send: chat — «Избранное»/уведомления устройств, transfer — перенос
	transferBlob := base64.StdEncoding.EncodeToString([]byte("history-chunk"))
	if code := sendBlob(t, ts, aliceToken, "alice", "chat", "tablet", transferBlob); code != http.StatusOK {
		t.Fatalf("chat-самопосылка (Избранное): %d", code)
	}
	if code := sendBlob(t, ts, aliceToken, "alice", "transfer", "tablet", transferBlob); code != http.StatusOK {
		t.Fatalf("transfer-самопосылка: %d", code)
	}
	msgs = drainAuth(t, ts, tabletToken)
	if len(msgs) != 2 {
		t.Fatalf("ожидали 2 самопосылки (chat+transfer), получили %d", len(msgs))
	}
	for _, m := range msgs {
		if m.From != "alice" {
			t.Fatalf("самопосылка не от alice: %+v", m)
		}
	}
	if msgs[0].Kind != "chat" || msgs[1].Kind != "transfer" {
		t.Fatalf("kind самопосылок: %+v", msgs)
	}

	// 9) планшет перезаходит по паролю; старый токен планшета умирает
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "secret123", DeviceID: "tablet",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("логин планшета: %d %s", resp.StatusCode, body)
	}
	var relogin struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal([]byte(body), &relogin); err != nil {
		t.Fatal(err)
	}
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/messages", tabletToken, nil)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("старый токен планшета должен умереть: %d", resp.StatusCode)
	}

	// 10) повторная линковка того же устройства с другим ключом — конфликт
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/link/init", "", linkInitRequest{
		DeviceID: "tablet", PubSPKI: base64.StdEncoding.EncodeToString([]byte("pub-evil")),
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("второй link/init: %d", resp.StatusCode)
	}
	json.Unmarshal([]byte(body), &init)
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/link/confirm", aliceToken, map[string]string{
		"nonce":    init.Nonce,
		"pub_spki": base64.StdEncoding.EncodeToString([]byte("pub-evil")),
	})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("подмена ключа существующего устройства: %d", resp.StatusCode)
	}
}

func TestRelayDropsStaleAndCaps(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1") // registerUser ждёт код в ответе
	srv := newServer()
	base := time.Now()
	srv.now = func() time.Time { return base }
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")

	blob := base64.StdEncoding.EncodeToString([]byte("x"))
	aliceToken := tokenFor(t, ts, "alice")
	// A5: per-sender квота поднята, чтобы тест гнал чистый потолок очереди
	oldPerSender := maxQueuePerSender
	maxQueuePerSender = maxQueue + 10
	defer func() { maxQueuePerSender = oldPerSender }()
	for i := 0; i < maxQueue+5; i++ {
		if code := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); code != http.StatusOK {
			t.Fatalf("отправка %d: %d", i, code)
		}
	}
	if msgs := drainAuth(t, ts, tokenFor(t, ts, "bob")); len(msgs) != maxQueue {
		t.Fatalf("очередь устройства должна быть ограничена %d, получили %d", maxQueue, len(msgs))
	}
	for i := 0; i < 3; i++ {
		_, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages", aliceToken, map[string]any{
			"to_user": "bob", "blobs": []map[string]string{{"device_id": "phone", "blob": blob}},
		})
	}
	srv.now = func() time.Time { return base.Add(31 * 24 * time.Hour) }
	if msgs := drainAuth(t, ts, tokenFor(t, ts, "bob")); len(msgs) != 0 {
		t.Fatalf("просроченное должно стираться, получили %d", len(msgs))
	}
}

func TestMetricsEndpoint(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	bobToken, _ := registerUser(t, ts, "bob", "phone")
	if code := sendBlob(t, ts, tokenFor(t, ts, "alice"), "bob", "chat", "phone", "YWJj"); code != http.StatusOK {
		t.Fatalf("отправка: %d", code)
	}
	_ = drainAuth(t, ts, bobToken)

	resp, body := authedJSON(t, ts, http.MethodGet, "/metrics", "", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("метрики: %d", resp.StatusCode)
	}
	for _, want := range []string{
		"chat_accounts_total 2",
		"chat_messages_sent_total 1",
		"chat_messages_delivered_total 1",
		"chat_delivery_latency_ms_count 1",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("в метриках нет %q:\n%s", want, body)
		}
	}
}

// WS-канал: получатель с открытым сокетом получает wake-кадр мгновенно при Enqueue
func TestWebSocketWakeUp(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	bobToken, _ := registerUser(t, ts, "bob", "phone")

	// без авторизации апгрейд отклоняется
	_, respBad, err := websocket.DefaultDialer.Dial(
		strings.Replace(ts.URL, "http", "ws", 1)+"/v1/ws", nil)
	if err == nil {
		respBad.Body.Close()
		t.Fatal("WS без токена должен быть отклонён")
	}

	// bob открывает сокет
	header := http.Header{"Authorization": []string{"Bearer " + bobToken}}
	wsURL := strings.Replace(ts.URL, "http", "ws", 1) + "/v1/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))

	// alice шлёт bob'у — кадр должен прийти в сокет быстрее поллинга
	go func() {
		if code := sendBlob(t, ts, tokenFor(t, ts, "alice"), "bob", "chat", "phone", "V1NLS0VTVA=="); code != http.StatusOK {
			t.Errorf("отправка: %d", code)
		}
	}()
	_, message, err := conn.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	if string(message) != "newmsg" {
		t.Fatalf("ожидали wake-кадр newmsg, получили %q", message)
	}
	// за сигналом следует фактическое сообщение в очереди bob'а
	msgs := drainAuth(t, ts, bobToken)
	if len(msgs) != 1 {
		t.Fatalf("после wake-сигнала дрейн пуст: %v", msgs)
	}
}

// регистрация пуш-токена: только своё устройство, только android|ios
func TestPushTokenRegistration(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	bobToken, _ := registerUser(t, ts, "bob", "phone")

	body := map[string]string{"token": "fcm-token-123", "platform": "android"}
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/push", bobToken, body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("регистрация пуш-токена: %d", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/push", "", body)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без авторизации: %d", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/push", bobToken,
		map[string]string{"token": "x", "platform": "windows"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("неизвестная платформа: %d", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/push", bobToken,
		map[string]string{"token": "", "platform": "ios"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("пустой токен: %d", resp.StatusCode)
	}
}

// Медиа: зашифрованный блоб загружается и скачивается; границы размера и доступа
func TestMediaUploadDownload(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	token, _ := registerUser(t, ts, "alice", "phone")

	upload := func(tok string, body []byte) (*http.Response, string) {
		req, _ := http.NewRequest(http.MethodPost, ts.URL+"/v1/media", bytes.NewReader(body))
		if tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		resp, err := ts.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		return resp, string(raw)
	}

	payload := []byte("encrypted-bytes-0123456789")
	resp, body := upload(token, payload)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("upload: %d %s", resp.StatusCode, body)
	}
	var out struct {
		MediaID string `json:"media_id"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatal(err)
	}
	if !mediaIDRe(out.MediaID) {
		t.Fatalf("некорректный media_id: %q", out.MediaID)
	}

	// скачивание: байты совпадают
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/v1/media/"+out.MediaID, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	getResp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer getResp.Body.Close()
	raw, _ := io.ReadAll(getResp.Body)
	if getResp.StatusCode != http.StatusOK || !bytes.Equal(raw, payload) {
		t.Fatalf("get: %d, байты не совпали", getResp.StatusCode)
	}

	// без авторизации — 401
	if resp, _ = upload("", payload); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("upload без токена: %d", resp.StatusCode)
	}
	// невалидный идентификатор — 400
	req, _ = http.NewRequest(http.MethodGet, ts.URL+"/v1/media/xyz", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if resp, err := ts.Client().Do(req); err != nil || resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("невалидный id: %v %v", resp, err)
	}
	// валидный, но неизвестный — 404
	req, _ = http.NewRequest(http.MethodGet, ts.URL+"/v1/media/"+strings.Repeat("ab", 32), nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if resp, err := ts.Client().Do(req); err != nil || resp.StatusCode != http.StatusNotFound {
		t.Fatalf("неизвестный id: %v %v", resp, err)
	}
	// превышение размера — 413
	if resp, _ = upload(token, make([]byte, maxMediaSize+1)); resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize: %d", resp.StatusCode)
	}
}

// Лимит тела на маленьких JSON-эндпоинтах
func TestRequestBodyLimit(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	big := strings.Repeat("a", 200<<10) // 200 КБ > лимита 64 КБ
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", map[string]string{
		"login": big, "password": "secret123", "device_id": "phone", "pub_spki": "cHVi",
	})
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize register: %d", resp.StatusCode)
	}
}

// Rate-limit инициаций линковки с одного IP
func TestLinkInitRateLimit(t *testing.T) {
	srv := newServer()
	base := time.Now()
	srv.now = func() time.Time { return base }
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()

	initBody := linkInitRequest{DeviceID: "tablet", PubSPKI: "cHVi"}
	for i := 0; i < linkInitPerHour; i++ {
		resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/link/init", "", initBody)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("init %d: %d (ожидали 200)", i, resp.StatusCode)
		}
	}
	// 11-я попытка в том же окне — 429
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/link/init", "", initBody)
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("11-я попытка: %d (ожидали 429)", resp.StatusCode)
	}
	// окно ушло — снова можно
	srv.now = func() time.Time { return base.Add(61 * time.Minute) }
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/link/init", "", initBody)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("после окна: %d (ожидали 200)", resp.StatusCode)
	}
}

// TTL медиа: устаревший блоб стирается чисткой, свежий — живёт
func TestMediaSweep(t *testing.T) {
	store, err := newFsStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	old := []byte("old")
	fresh := []byte("fresh")
	if err := store.Put("aaaaaaaa", old); err != nil {
		t.Fatal(err)
	}
	if err := store.Put("bbbbbbbb", fresh); err != nil {
		t.Fatal(err)
	}
	// файл old становится старше окна транзита
	past := time.Now().Add(-maxMediaAge - time.Hour)
	if err := os.Chtimes(store.path("aaaaaaaa"), past, past); err != nil {
		t.Fatal(err)
	}
	if removed := store.sweep(time.Now()); removed != 1 {
		t.Fatalf("ожидали 1 удаление, получили %d", removed)
	}
	if _, err := store.Get("aaaaaaaa"); err == nil {
		t.Fatal("устаревший блоб должен быть удалён")
	}
	if data, err := store.Get("bbbbbbbb"); err != nil || string(data) != "fresh" {
		t.Fatalf("свежий блоб повреждён: %v", err)
	}
}

// Email-контакт-лист: регистрация с email → discover находит
func TestEmailDiscovery(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()

	// Alice регистрируется с email
	// Alice и Bob регистрируются с email (нужны коды)
	aliceEmail := "alice@example.com"
	bobEmail := "bob@example.com"

	for _, email := range []string{aliceEmail, bobEmail} {
		cb, _ := json.Marshal(map[string]string{"email": email})
		cr, _ := ts.Client().Post(ts.URL+"/v1/email-code", "application/json", bytes.NewReader(cb))
		var co struct {
			Code string `json:"code"`
		}
		json.NewDecoder(cr.Body).Decode(&co)
		cr.Body.Close()
		if co.Code == "" {
			t.Fatal("email-code: не получен для " + email)
		}
		// регистрируем
		pub := base64.StdEncoding.EncodeToString([]byte("pub-" + email[:strings.Index(email, "@")]))
		_, body := authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
			Password: "secret123", DeviceID: "phone",
			PubSPKI: pub, Email: email, Code: co.Code,
		})
		_ = body
	}

	// Bob ищет: у него в контактах email Alice
	aliceHash := hashEmail(aliceEmail)
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/discover",
		tokenFor(t, ts, "bob"),
		map[string]any{"email_hashes": []string{aliceHash, hashEmail("charlie@nowhere.com")}},
	)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("discover: %d %s", resp.StatusCode, body)
	}

	var result struct {
		Matches []EmailMatch `json:"matches"`
	}
	if err := json.Unmarshal([]byte(body), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Matches) != 1 {
		t.Fatalf("ожидали 1 совпадение (alice), получили %d: %+v", len(result.Matches), result.Matches)
	}
	if result.Matches[0].Login != "alice" || result.Matches[0].EmailHash != aliceHash {
		t.Fatalf("неверное совпадение: %+v", result.Matches[0])
	}
}

// Discover без авторизации и с кривым хэшем
func TestEmailDiscoveryValidation(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	token, _ := registerUser(t, ts, "tester", "phone")

	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/discover", "",
		map[string]any{"email_hashes": []string{"abc"}})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без авторизации: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/discover", token,
		map[string]any{"email_hashes": []string{}})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("пустой список: %d", resp.StatusCode)
	}

	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/discover", token,
		map[string]any{"email_hashes": []string{"short"}})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("кривой хэш: %d", resp.StatusCode)
	}
}

// Прочитано: читатель шлёт from_user, автор получает WS-кадр "read:<читатель>"
func TestReadReceipt(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")

	// без авторизации — 401
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/read", "", map[string]string{"from_user": "alice"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("read без авторизации: %d", resp.StatusCode)
	}

	// пустое тело — 400
	bobToken := tokenFor(t, ts, "bob")
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/read", bobToken, map[string]string{})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("read с пустым телом: %d", resp.StatusCode)
	}

	// alice открывает сокет, bob читает её сообщения
	header := http.Header{"Authorization": []string{"Bearer " + tokenFor(t, ts, "alice")}}
	wsURL := strings.Replace(ts.URL, "http", "ws", 1) + "/v1/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))

	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/read", bobToken, map[string]string{"from_user": "alice"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("read: %d", resp.StatusCode)
	}
	_, message, err := conn.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	if string(message) != "read:bob" {
		t.Fatalf("ожидали кадр read:bob, получили %q", message)
	}
}

// ─── S1: XFF за прокси, лимиты по реальному клиенту ───

// POST email-code с заданным XFF; ответ — статус
func postEmailCode(t *testing.T, ts *httptest.Server, email, xff string) int {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"email": email})
	req, _ := http.NewRequest("POST", ts.URL+"/v1/email-code", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if xff != "" {
		req.Header.Set("X-Forwarded-For", xff)
	}
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	return resp.StatusCode
}

// два «клиента» за прокси с разными XFF не делят лимит кодов
func TestXffSeparatesClients(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	srv.emailCooldown = 0
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()

	// первый клиент выжигает свой лимит 5/ч
	for i := 0; i < 5; i++ {
		if got := postEmailCode(t, ts, "one@test.example", "1.2.3.4"); got != http.StatusOK {
			t.Fatalf("код %d для 1.2.3.4: %d (ожидали 200)", i, got)
		}
	}
	if got := postEmailCode(t, ts, "one@test.example", "1.2.3.4"); got != http.StatusTooManyRequests {
		t.Fatalf("шестой код для 1.2.3.4: %d (ожидали 429)", got)
	}
	// второй клиент за тем же прокси живет своей жизнью
	if got := postEmailCode(t, ts, "two@test.example", "5.6.7.8"); got != http.StatusOK {
		t.Fatalf("первый код для 5.6.7.8 не прошёл: %d (ожидали 200)", got)
	}
}

// подделанный XFF от НЕдоверенного источника игнорируется:
// все попытки «прямого» клиента 203.0.113.7 считаются против него самого
func TestSpoofedXffIgnoredFromUntrusted(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1") // 2.3: без dev сбой отправки кода честно даёт 503
	srv := newServer()
	srv.emailCooldown = 0
	mux := srv.routes()
	do := func(xff, remote string) int {
		req := httptest.NewRequest("POST", "/v1/email-code",
			strings.NewReader(`{"email":"spoof@test.example"}`))
		req.Header.Set("Content-Type", "application/json")
		if xff != "" {
			req.Header.Set("X-Forwarded-For", xff)
		}
		req.RemoteAddr = remote
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		return w.Code
	}
	// пять попыток с разными поддельными XFF — все в один бакет
	for i := 0; i < 5; i++ {
		if got := do("9.9.9.9", "203.0.113.7:10000"); got != http.StatusOK {
			t.Fatalf("попытка %d: %d (ожидали 200)", i, got)
		}
	}
	if got := do("8.8.8.8", "203.0.113.7:10000"); got != http.StatusTooManyRequests {
		t.Fatalf("подделка XFF должна делить лимит с прямым IP: %d (ожидали 429)", got)
	}
	// другой прямой клиент не пострадал
	if got := do("", "203.0.113.8:10000"); got != http.StatusOK {
		t.Fatalf("соседний IP не должен делить лимит: %d (ожидали 200)", got)
	}
}

// чистка лимитера: ключи без свежих попаданий удаляются
func TestIpLimiterCleanup(t *testing.T) {
	l := newIpLimiter(2, time.Hour)
	base := time.Now()
	if !l.allow("a", base) || !l.allow("a", base) {
		t.Fatal("лимит 2 должен пропускать первые два")
	}
	if l.allow("a", base) {
		t.Fatal("третий — за пределом")
	}
	// без чистки ключ живёт вечно
	if removed := l.cleanup(base.Add(30 * time.Minute)); removed != 0 {
		t.Fatalf("свежий ключ удалён преждевременно: %d", removed)
	}
	if removed := l.cleanup(base.Add(61 * time.Minute)); removed != 1 {
		t.Fatalf("ожидали удаление устаревшего ключа, получили %d", removed)
	}
	if !l.allow("a", base.Add(61*time.Minute)) {
		t.Fatal("после окна и чистки лимит снова работает")
	}
}

// ─── S2: вход по email и по логину ───

func TestLoginByEmailAndCaseInsensitiveLogin(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")

	// вход по email — прежний код ронял его в 401 «устройство не привязано»
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice@test.example", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("вход по email: %d %s", resp.StatusCode, body)
	}
	var out struct {
		UserID string `json:"user_id"`
	}
	json.Unmarshal([]byte(body), &out)
	if out.UserID != "alice" {
		t.Fatalf("вход по email: user_id %q", out.UserID)
	}

	// вход по логину в верхнем регистре — приводим к нижнему
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "ALICE", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("вход по ALICE: %d", resp.StatusCode)
	}

	// email без аккаунта — 401
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "nobody@test.example", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("чужой email: %d (ожидали 401)", resp.StatusCode)
	}
}

// перебор пароля останавливается лимитом по логину и IP
func TestLoginRateLimited(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")

	for i := 0; i < loginAttemptsPerHour; i++ {
		resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
			Login: "alice", Password: "wrong", DeviceID: "phone",
		})
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("попытка %d: %d (ожидали 401)", i, resp.StatusCode)
		}
	}
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("перебор должен упереться в 429: %d", resp.StatusCode)
	}
}

// ─── S3: коды подтверждения ───

// регистрация с заданным кодом; ответ — статус
func registerWithCode(t *testing.T, ts *httptest.Server, email, code string) int {
	t.Helper()
	body, _ := json.Marshal(accountRequest{
		Password: "secret123", DeviceID: "phone",
		PubSPKI: base64.StdEncoding.EncodeToString([]byte("pub-" + email)),
		Email:   email, Code: code,
	})
	resp, err := ts.Client().Post(ts.URL+"/v1/accounts", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

// код с почты для email (dev-режим отдаёт его в ответе)
func codeFor(t *testing.T, ts *httptest.Server, email string) string {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"email": email})
	resp, err := ts.Client().Post(ts.URL+"/v1/email-code", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.Code == "" {
		t.Fatalf("email-code для %s: код не получен (%v)", email, err)
	}
	return out.Code
}

// перебор 4-значного кода сгорает после maxCodeAttempts неудач:
// даже ВЕРНЫЙ код больше не принимается
func TestEmailCodeBruteForce(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	srv.emailCooldown = 0
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()

	email := "brute@test.example"
	real := codeFor(t, ts, email)
	wrong := fmt.Sprintf("%04d", (mustAtoi(real)+1)%10000)
	for i := 0; i < maxCodeAttempts; i++ {
		if got := registerWithCode(t, ts, email, wrong); got != http.StatusBadRequest {
			t.Fatalf("неверная попытка %d: %d (ожидали 400)", i, got)
		}
	}
	// перебор исчерпан — верный код сгорел
	if got := registerWithCode(t, ts, email, real); got != http.StatusBadRequest {
		t.Fatalf("код обязан сгореть после перебора: %d (ожидали 400)", got)
	}
}

func mustAtoi(s string) int {
	n := 0
	for _, c := range s {
		n = n*10 + int(c-'0')
	}
	return n
}

// новый код гасит прежний: валиден только последний
func TestNewCodeInvalidatesOld(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	srv.emailCooldown = 0
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()

	email := "fresh@test.example"
	oldCode := codeFor(t, ts, email)
	newCode := codeFor(t, ts, email)
	if oldCode == newCode {
		t.Skip("код совпал — реролл")
	}
	if got := registerWithCode(t, ts, email, oldCode); got != http.StatusBadRequest {
		t.Fatalf("старый код должен быть погашен: %d (ожидали 400)", got)
	}
	if got := registerWithCode(t, ts, email, newCode); got != http.StatusOK {
		t.Fatalf("новый код обязан работать: %d (ожидали 200)", got)
	}
}

// неудачная валидация полей не сжигает код: он потребляется последним
func TestCodeNotConsumedByFailedValidation(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	email := "keep@test.example"
	code := codeFor(t, ts, email)

	// кривой публичный ключ → 400, код НЕ потреблён
	body, _ := json.Marshal(accountRequest{
		Password: "secret123", DeviceID: "phone",
		PubSPKI: "не-base64!!!", Email: email, Code: code,
	})
	resp, err := ts.Client().Post(ts.URL+"/v1/accounts", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("кривой ключ: %d (ожидали 400)", resp.StatusCode)
	}
	// тот же код проходит со здоровым ключом
	if got := registerWithCode(t, ts, email, code); got != http.StatusOK {
		t.Fatalf("код не должен сгореть от кривого ключа: %d (ожидали 200)", got)
	}
}

// без CHAT_DEV_MODE кода нет ни в ответе
func TestNoCodeWithoutDevMode(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "")
	ts := httptest.NewServer(newServer().routes())
	defer ts.Close()

	body, _ := json.Marshal(map[string]string{"email": "prod@test.example"})
	resp, err := ts.Client().Post(ts.URL+"/v1/email-code", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		Code string `json:"code"`
	}
	raw, _ := io.ReadAll(resp.Body)
	json.Unmarshal(raw, &out)
	if out.Code != "" {
		t.Fatalf("без dev-режима код утёк в ответ: %s", string(raw))
	}
}

// ─── S4: очередь и получатель ───

// очереди для несуществующих получателей не плодятся:
// неизвестный логин → 404, неизвестное устройство отбрасывается
func TestPostMessageUnknownRecipient(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")

	// незарегистрированный получатель
	if got := sendBlob(t, ts, aliceToken, "ghost", "chat", "phone", "YWJj"); got != http.StatusNotFound {
		t.Fatalf("отправка ghost: %d (ожидали 404)", got)
	}
	// зарегистрированный, но устройство неизвестно
	if got := sendBlob(t, ts, aliceToken, "bob", "chat", "tablet", "YWJj"); got != http.StatusNotFound {
		t.Fatalf("отправка bob/tablet: %d (ожидали 404)", got)
	}
	// смешанный fan-out: чужое устройство отброшено, своё — в очереди
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/messages", aliceToken, map[string]any{
		"to_user": "bob",
		"blobs": []map[string]string{
			{"device_id": "tablet", "blob": "AAAA"},
			{"device_id": "phone", "blob": "YWJj"},
		},
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("смешанный fan-out: %d (ожидали 200)", resp.StatusCode)
	}
	msgs := drainAuth(t, ts, tokenFor(t, ts, "bob"))
	if len(msgs) != 1 || msgs[0].ToDevice != "phone" || msgs[0].Blob != "YWJj" {
		t.Fatalf("в очереди должно быть только реальное устройство: %+v", msgs)
	}
}

// байтовая квота очереди: сверх потолка вытесняются старейшие
func TestQueueByteQuota(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")

	old := maxQueueBytes
	maxQueueBytes = 3000 // по ~668 символов base64 на блоб — уживутся 4
	defer func() { maxQueueBytes = old }()

	for i := 0; i < 10; i++ {
		raw := fmt.Sprintf("blob-%02d-%s", i, strings.Repeat("x", 480))
		blob := base64.StdEncoding.EncodeToString([]byte(raw))
		if got := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); got != http.StatusOK {
			t.Fatalf("отправка %d: %d", i, got)
		}
	}
	msgs := drainAuth(t, ts, tokenFor(t, ts, "bob"))
	if len(msgs) != 4 {
		t.Fatalf("байтовая квота должна оставить 4 старейших вытесненными, получили %d", len(msgs))
	}
	// остались ПОСЛЕДНИЕ блобы (вытеснялись старейшие)
	raw, _ := base64.StdEncoding.DecodeString(msgs[0].Blob)
	if !strings.HasPrefix(string(raw), "blob-06") {
		t.Fatalf("в очереди не последние блобы: %q", string(raw)[:10])
	}
}

// ─── S6: push-вебхук ───

// вебхук: таймаут в секундах и асинхронная доставка
func TestPushWebhookAsync(t *testing.T) {
	received := make(chan string, 8)
	hook := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		received <- string(raw)
	}))
	defer hook.Close()

	p := newWebhookPusher(hook.URL)
	if p.client.Timeout < 5*time.Second {
		t.Fatalf("таймаут вебхука обязан быть секундами, а не наносекундами: %v", p.client.Timeout)
	}
	p.Notify("bob", "phone")
	select {
	case body := <-received:
		if !strings.Contains(body, `"to_user":"bob"`) || !strings.Contains(body, `"wake":true`) {
			t.Fatalf("неожиданное тело вебхука: %s", body)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("wake-сигнал не дошёл до вебхука за 3 с")
	}
}

// ─── S4: ack-дрейн и пагинация ───

// двухфазный дрейн: ack=1 не удаляет, повторный peek отдаёт то же самое;
// ack по id удаляет подтверждённое, остальное живёт
func TestAckDrainTwoPhase(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")
	bobToken := tokenFor(t, ts, "bob")
	blob := base64.StdEncoding.EncodeToString([]byte("survives-disconnect"))
	if got := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); got != http.StatusOK {
		t.Fatalf("отправка: %d", got)
	}

	// первая страница: peek без удаления
	resp, body := authedJSON(t, ts, http.MethodGet, "/v1/messages?ack=1", bobToken, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("ack-дрейн: %d", resp.StatusCode)
	}
	var page1 drainResult
	if err := json.Unmarshal([]byte(body), &page1); err != nil {
		t.Fatal(err)
	}
	if len(page1.Messages) != 1 || page1.More {
		t.Fatalf("ожидали 1 сообщение без more: %+v", page1)
	}
	id := page1.Messages[0].Id
	if id <= 0 {
		t.Fatalf("id очереди обязаны приходить в ack-режиме: %+v", page1.Messages[0])
	}

	// обрыв ДО ack: страница отдаётся снова — ничего не потеряно
	resp, body = authedJSON(t, ts, http.MethodGet, "/v1/messages?ack=1", bobToken, nil)
	var page2 drainResult
	json.Unmarshal([]byte(body), &page2)
	if len(page2.Messages) != 1 || page2.Messages[0].Id != id {
		t.Fatalf("ack-дрейн не должен удалять: %+v", page2)
	}

	// ack после «записи в WAL»: подтверждение удаляет
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages/ack", bobToken, map[string]any{"ids": []int64{id}})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("ack: %d", resp.StatusCode)
	}
	// теперь очередь пуста — и обычный, и ack-дрейн
	if msgs := drainAuth(t, ts, bobToken); len(msgs) != 0 {
		t.Fatalf("после ack очередь обязана быть пустой: %d", len(msgs))
	}
	// чужие id не удаляются: ничего не должно произойти
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages/ack", bobToken, map[string]any{"ids": []int64{999999}})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("ack с чужими id: %d", resp.StatusCode)
	}
	// пустой список id — 400
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/messages/ack", bobToken, map[string]any{"ids": []int64{}})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("ack без id: %d (ожидали 400)", resp.StatusCode)
	}
}

// пагинация: limit делит выдачу, more=true до последней страницы
func TestDrainPagination(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")
	bobToken := tokenFor(t, ts, "bob")
	for i := 0; i < 5; i++ {
		blob := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("msg-%d", i)))
		if got := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); got != http.StatusOK {
			t.Fatalf("отправка %d: %d", i, got)
		}
	}
	// страница из 2 (peek, без удаления): ровно 2 сообщения, more=true
	resp, body := authedJSON(t, ts, http.MethodGet, "/v1/messages?ack=1&limit=2", bobToken, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("страница: %d", resp.StatusCode)
	}
	var page drainResult
	json.Unmarshal([]byte(body), &page)
	if len(page.Messages) != 2 || !page.More {
		t.Fatalf("ожидали 2 сообщения и more: %d more=%v", len(page.Messages), page.More)
	}
	// порядок вставки сохраняется
	if !strings.HasSuffix(page.Messages[0].Blob, "bXNnLTA=") { // base64("msg-0")
		t.Fatalf("порядок страниц нарушен: %s", page.Messages[0].Blob)
	}
	// полный дрейн крутит страницы и отдаёт все 5
	if msgs := drainAuth(t, ts, bobToken); len(msgs) != 5 {
		t.Fatalf("полный дрейн обязан собрать все страницы: %d", len(msgs))
	}
	// oversize-лимит страницы отдаёт всё равно всё меньше 16 МБ — здесь просто пусто
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/messages?limit=0", bobToken, nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit=0: %d (ожидали 400)", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/messages?limit=9999", bobToken, nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit=9999: %d (ожидали 400)", resp.StatusCode)
	}
}

// TTL-чистка очереди удаляет просроченный транзит (S4)
func TestQueueSweep(t *testing.T) {
	srv := newServer()
	base := time.Now()
	srv.now = func() time.Time { return base }
	t.Setenv("CHAT_DEV_MODE", "1")
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")
	blob := base64.StdEncoding.EncodeToString([]byte("stale"))
	if got := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); got != http.StatusOK {
		t.Fatalf("отправка: %d", got)
	}
	// чистка в будущем окне убирает транзит с возрастом за maxQueueAge
	if removed, err := srv.store.SweepExpired(base.Add(maxQueueAge + time.Hour)); err != nil || removed != 1 {
		t.Fatalf("sweep: removed=%d err=%v", removed, err)
	}
	if msgs := drainAuth(t, ts, tokenFor(t, ts, "bob")); len(msgs) != 0 {
		t.Fatalf("просроченный транзит обязан исчезнуть: %d", len(msgs))
	}
}

// ─── S5: события реального времени и границы тел ───

// typing/read/delivered лимитируются на аккаунт и валидируются
func TestRealtimeEventsRateLimited(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	aliceToken := tokenFor(t, ts, "alice")

	// кривой получатель и устройство — 400
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/typing", aliceToken,
		map[string]string{"to_user": "GRП ПЛОХОЙ!"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("typing с кривым получателем: %d", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/delivered", aliceToken,
		map[string]string{"from_user": "alice", "to_device": "../injection"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("delivered с кривым устройством: %d", resp.StatusCode)
	}
	// поток сверх 20/с упирается в 429
	codes := make(map[int]int)
	for i := 0; i < actionsPerSecond+5; i++ {
		resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/typing", aliceToken,
			map[string]string{"to_user": "alice"})
		codes[resp.StatusCode]++
	}
	if codes[http.StatusOK] != actionsPerSecond || codes[http.StatusTooManyRequests] != 5 {
		t.Fatalf("typing: %d×200, %d×429 (ожидали 20 и 5)", codes[http.StatusOK], codes[http.StatusTooManyRequests])
	}
	// read валидирует автора
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/read", aliceToken,
		map[string]string{"from_user": "НЕ-ЛОГИН"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("read с кривым автором: %d", resp.StatusCode)
	}
}

// 413 на oversize-теле теперь не подменяется до-400 (S5: isMaxBytes)
func TestMaxBytes413OnSmallEndpoints(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	aliceToken := tokenFor(t, ts, "alice")
	big := strings.Repeat("a", 200<<10)
	for _, tc := range []struct {
		path string
		body any
	}{
		{"/v1/discover", map[string]any{"email_hashes": []string{big}}},
		{"/v1/delivered", map[string]any{"from_user": "alice", "to_device": big}},
		{"/v1/read", map[string]any{"from_user": big}},
		{"/v1/email-code", map[string]any{"email": big}},
		{"/v1/typing", map[string]any{"to_user": big}},
	} {
		resp, _ := authedJSON(t, ts, http.MethodPost, tc.path, aliceToken, tc.body)
		if tc.path == "/v1/email-code" {
			resp, _ = authedJSON(t, ts, http.MethodPost, tc.path, "", tc.body)
		}
		if resp.StatusCode != http.StatusRequestEntityTooLarge {
			t.Fatalf("%s oversize: %d (ожидали 413)", tc.path, resp.StatusCode)
		}
	}
}

// putKey читает тело с лимитом; link/confirm с пустым nonce — 400
func TestPutKeyLimitAndEmptyNonce(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	token, _ := registerUser(t, ts, "alice", "phone")
	// oversize ключ — 413, не 500
	resp, _ := authedJSON(t, ts, http.MethodPut, "/v1/keys/phone", token,
		deviceKey{PubSPKI: strings.Repeat("a", 200<<10)})
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("putKey oversize: %d (ожидали 413)", resp.StatusCode)
	}
	// подтверждение линковки без nonce — 400, не пустой 200
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/link/confirm", token,
		map[string]string{"nonce": "", "pub_spki": "cHVi"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("confirm с пустым nonce: %d (ожидали 400)", resp.StatusCode)
	}
}

// ─── S6: хранение токенов, перехэш KDF, пространство grp_, метрики ───

// токены в БД/памяти лежат только хэшем SHA-256; сырой никуда не попадает
func TestTokensStoredHashed(t *testing.T) {
	srv := newServer()
	t.Setenv("CHAT_DEV_MODE", "1")
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	raw, _ := registerUser(t, ts, "alice", "phone")

	// сырой токен авторизует
	if _, ok := srv.byBearerRaw(raw); !ok {
		t.Fatal("сырой токен обязан работать")
	}
	// но в ключах стора сырого токена нет — только хэш
	mem := srv.store.(*memstore)
	mem.mu.Lock()
	for key := range mem.byToken {
		if key == raw {
			mem.mu.Unlock()
			t.Fatal("сырой токен хранится в памяти")
		}
	}
	mem.mu.Unlock()
}

// аккаунт со старой стоимостью KDF входит по старому числу итераций
// и перехэшируется до текущего потолка
func TestPbkdf2RehashOnLogin(t *testing.T) {
	srv := newServer()
	t.Setenv("CHAT_DEV_MODE", "1")
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")

	// «легаси-запись»: 40k итераций, соль/хэш под них
	mem := srv.store.(*memstore)
	legacySalt := []byte("legacy-salt-16")
	legacyKey := pbkdf2([]byte("secret123"), legacySalt, pbkdf2LegacyIterations, 32)
	mem.mu.Lock()
	acc := mem.accounts["alice"]
	acc.salt = legacySalt
	acc.pkdfKey = legacyKey
	acc.iters = pbkdf2LegacyIterations
	mem.mu.Unlock()

	// вход по легаси-записи проходит и перехэширует
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("вход по легаси-хэшу: %d %s", resp.StatusCode, body)
	}
	mem.mu.Lock()
	upgradedIters := mem.accounts["alice"].iters
	upgradedKey := mem.accounts["alice"].pkdfKey
	upgradedSalt := mem.accounts["alice"].salt
	mem.mu.Unlock()
	if upgradedIters != pbkdf2Iterations {
		t.Fatalf("перехэш не состоялся: iters=%d", upgradedIters)
	}
	if string(upgradedKey) == string(legacyKey) || string(upgradedSalt) == string(legacySalt) {
		t.Fatal("перехэш обязан сменить соль и хэш")
	}
	// повторный вход — уже по новой записи
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("вход после перехэша: %d", resp.StatusCode)
	}
	// неверный пароль по-прежнему отвергается
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "alice", Password: "wrong-pass", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("неверный пароль: %d", resp.StatusCode)
	}
}

// логины с префиксом grp_ не регистрируются: пространство групп
func TestGrpPrefixLoginBanned(t *testing.T) {
	if got := loginFromEmail("grp_evil@test.example"); got != "ugrp_evil" {
		t.Fatalf("loginFromEmail обязан ломать префикс grp_: %q", got)
	}
	ts := testServer(t)
	defer ts.Close()
	code := codeFor(t, ts, "grp_x@test.example")
	if got := registerWithCode(t, ts, "grp_x@test.example", code); got != http.StatusOK {
		t.Fatalf("регистрация grp_x: %d", got)
	}
	// зарегистрированный логин не начинается с grp_
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/login", "", accountRequest{
		Login: "ugrp_x", Password: "secret123", DeviceID: "phone",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("вход за ugrp_x: %d %s", resp.StatusCode, body)
	}
	var out struct {
		UserID string `json:"user_id"`
	}
	json.Unmarshal([]byte(body), &out)
	if out.UserID != "ugrp_x" {
		t.Fatalf("user_id обязан избежать префикса: %q", out.UserID)
	}
}

// /metrics: только loopback или носитель CHAT_METRICS_TOKEN
func TestMetricsAccessControl(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	mux := srv.routes()

	do := func(remote, token string) int {
		req := httptest.NewRequest("GET", "/metrics", nil)
		req.RemoteAddr = remote
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		return w.Code
	}
	// без токена: только loopback
	if got := do("127.0.0.1:10000", ""); got != http.StatusOK {
		t.Fatalf("loopback: %d (ожидали 200)", got)
	}
	if got := do("203.0.113.7:10000", ""); got != http.StatusNotFound {
		t.Fatalf("внешний IP без токена: %d (ожидали 404)", got)
	}
	// с токеном — доступен отовсюду, с неверным токеном — нет
	t.Setenv("CHAT_METRICS_TOKEN", "sekrit")
	if got := do("203.0.113.7:10000", "sekrit"); got != http.StatusOK {
		t.Fatalf("верный токен: %d (ожидали 200)", got)
	}
	if got := do("203.0.113.7:10000", "wrong"); got != http.StatusNotFound {
		t.Fatalf("неверный токен: %d (ожидали 404)", got)
	}
	// логинов в выдаче нет
	req := httptest.NewRequest("GET", "/metrics?token=sekrit", nil)
	req.RemoteAddr = "203.0.113.7:10000"
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if strings.Contains(w.Body.String(), `login="`) {
		t.Fatalf("логины не должны светиться в метриках:\n%s", w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "chat_queue_depth_total") {
		t.Fatalf("агрегат очереди обязателен в метриках:\n%s", w.Body.String())
	}
}

// ─── Раунд 7, 1.1: курсор ack-дрейна ───

// after=<id>: страница после курсора без дублей; дрейн из 450 сообщений
// по страницам без ack между ними обязан выдать каждое ровно один раз.
// Без курсора PeekQueue отдавал начало очереди вечно (зацикливание).
func TestDrainAckCursor(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")
	bobToken := tokenFor(t, ts, "bob")
	const total = 450
	// A5: per-sender квота поднята — тест гоняет курсор, а не квоту отправителя
	oldPerSender := maxQueuePerSender
	maxQueuePerSender = total + 10
	defer func() { maxQueuePerSender = oldPerSender }()
	for i := 0; i < total; i++ {
		blob := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("cursor-%d", i)))
		if got := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); got != http.StatusOK {
			t.Fatalf("отправка %d: %d", i, got)
		}
	}
	seen := map[int64]bool{}
	after := int64(0)
	for page := 0; page < 10; page++ {
		url := "/v1/messages?ack=1&limit=200"
		if after > 0 {
			url += fmt.Sprintf("&after=%d", after)
		}
		resp, body := authedJSON(t, ts, http.MethodGet, url, bobToken, nil)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("страница %d: %d", page, resp.StatusCode)
		}
		var result drainResult
		if err := json.Unmarshal([]byte(body), &result); err != nil {
			t.Fatal(err)
		}
		if len(result.Messages) == 0 {
			break
		}
		for _, m := range result.Messages {
			if m.Id <= after {
				t.Fatalf("страница %d: id %d не новее курсора %d", page, m.Id, after)
			}
			if seen[m.Id] {
				t.Fatalf("сообщение %d выдано повторно на странице %d", m.Id, page)
			}
			seen[m.Id] = true
		}
		last := result.Messages[len(result.Messages)-1].Id
		if last <= after {
			t.Fatal("курсор не двигается — дрейн зациклился")
		}
		after = last
		if !result.More {
			break
		}
	}
	if len(seen) != total {
		t.Fatalf("ack-дрейн с курсором обязан выдать все %d сообщений ровно по разу, выдано %d", total, len(seen))
	}
	// after больше всех id — пустая страница, зацикливания нет
	resp, body := authedJSON(t, ts, http.MethodGet,
		fmt.Sprintf("/v1/messages?ack=1&after=%d", after), bobToken, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("хвостовой after: %d", resp.StatusCode)
	}
	var tail drainResult
	json.Unmarshal([]byte(body), &tail)
	if len(tail.Messages) != 0 || tail.More {
		t.Fatalf("за курсором обязана быть пустая страница: %d more=%v", len(tail.Messages), tail.More)
	}
}

// after за пределами очереди и мусорное значение — 400/пусто, не 500
func TestDrainAckCursorValidation(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	bobToken := tokenFor(t, ts, "bob")
	resp, _ := authedJSON(t, ts, http.MethodGet, "/v1/messages?after=-5", bobToken, nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("after=-5: %d (ожидали 400)", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/messages?after=abc", bobToken, nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("after=abc: %d (ожидали 400)", resp.StatusCode)
	}
}

// ─── Раунд 7, 1.2: хэш токена не работает как Bearer ───

// значение хэша из хранилища в роли Bearer даёт 401; сырой токен жив.
// link_pending не хранит токен до выдачи: poll mint-ит свежий
func TestHashIsNotAToken(t *testing.T) {
	srv := newServer()
	t.Setenv("CHAT_DEV_MODE", "1")
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	token := tokenFor(t, ts, "alice")

	// в хранилище лежит хэш с префиксом версии; сам хэш — не токен
	store := srv.store.(*memstore)
	row := store.devices["alice"]["phone"]
	if row == nil || row.token == "" {
		t.Fatal("строка устройства не найдена")
	}
	if !strings.HasPrefix(row.token, "h1:") {
		t.Fatalf("хэш токена обязан храниться с префиксом версии: %q", row.token)
	}
	if auth, ok := srv.byBearerRaw(row.token); ok || auth != nil {
		t.Fatalf("хэш из хранилища не должен авторизовать: %+v", auth)
	}
	if auth, ok := srv.byBearerRaw(token); !ok || auth == nil || auth.login != "alice" {
		t.Fatalf("сырой токен обязан работать: %+v ok=%v", auth, ok)
	}
}

// ─── Раунд 7, Фаза 2 ───

// 2.1: discover и /metrics лимитируются по клиентскому IP за прокси.
// Прежнее remoteIP за Caddy делало лимит общим на весь сервис, а /metrics
// был публичен (loopback RemoteAddr всегда «свой»)
func TestDiscoverXffLimitSeparated(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	// доверяем loopback-прокси, как Caddy в проде
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	token := tokenFor(t, ts, "alice")
	body := map[string]any{"email_hashes": []string{strings.Repeat("a", 64)}}
	send := func(xff string) int {
		req, _ := http.NewRequest(http.MethodPost, ts.URL+"/v1/discover", bytes.NewReader([]byte(`{"email_hashes":["`+strings.Repeat("a", 64)+`"]}`)))
		req.Header.Set("Authorization", "Bearer "+token)
		if xff != "" {
			req.Header.Set("X-Forwarded-For", xff)
		}
		resp, err := ts.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	// выжигаем лимит одного клиента (10/ч на IP)
	for i := 0; i < linkInitPerHour; i++ {
		if got := send("198.51.100.7"); got != http.StatusOK {
			t.Fatalf("запрос %d: %d", i, got)
		}
	}
	if got := send("198.51.100.7"); got != http.StatusTooManyRequests {
		t.Fatalf("лимит одного клиента: %d (ожидали 429)", got)
	}
	// другой клиент за тем же прокси — свой бюджет
	if got := send("198.51.100.8"); got != http.StatusOK {
		t.Fatalf("соседний IP за прокси обязан работать: %d", got)
	}
	_ = body
}

// 2.1: /metrics за прокси публичен без токена — доступен только с
// клиентского loopback; XFF внешнего адреса скрывает эндпоинт
func TestMetricsBehindProxyUsesClientIP(t *testing.T) {
	srv := newServer()
	mux := srv.routes()
	do := func(xff string) int {
		req := httptest.NewRequest("GET", "/metrics", nil)
		req.RemoteAddr = "127.0.0.1:10000" // Caddy в проде — loopback
		if xff != "" {
			req.Header.Set("X-Forwarded-For", xff)
		}
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		return w.Code
	}
	if got := do(""); got != http.StatusOK {
		t.Fatalf("напрямую с loopback: %d (ожидали 200)", got)
	}
	if got := do("203.0.113.7"); got != http.StatusNotFound {
		t.Fatalf("внешний клиент за прокси: %d (ожидали 404)", got)
	}
}

// 2.1: тело /v1/push читается с лимитом — 200 КБ дают 413, а не 400
func TestPushBodyLimit413(t *testing.T) {
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	token := tokenFor(t, ts, "alice")
	big := map[string]any{"token": strings.Repeat("t", 200*1024), "platform": "android"}
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/push", token, big)
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("200 КБ push-токен: %d (ожидали 413)", resp.StatusCode)
	}
}

// 2.1: квота медиа на аккаунт — 200 МБ в сутки; rate на загрузку
func TestMediaQuotaPerAccount(t *testing.T) {
	old := mediaDayQuota
	mediaDayQuota = 1500
	defer func() { mediaDayQuota = old }()
	ts := testServer(t)
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	token := tokenFor(t, ts, "alice")
	put := func(size int) int {
		req, _ := http.NewRequest(http.MethodPost, ts.URL+"/v1/media", bytes.NewReader(make([]byte, size)))
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := ts.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if got := put(500); got != http.StatusOK {
		t.Fatalf("первый блоб: %d", got)
	}
	if got := put(600); got != http.StatusOK {
		t.Fatalf("второй блоб: %d", got)
	}
	// 500+600+700 > 1500 — квота исчерпана, 413
	if got := put(700); got != http.StatusRequestEntityTooLarge {
		t.Fatalf("за квотой: %d (ожидали 413)", got)
	}
	// другой аккаунт — свой бюджет
	_, _ = registerUser(t, ts, "bob", "phone")
	bobToken := tokenFor(t, ts, "bob")
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/v1/media", bytes.NewReader(make([]byte, 700)))
	req.Header.Set("Authorization", "Bearer "+bobToken)
	resp, _ := ts.Client().Do(req)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("другой аккаунт обязан грузить: %d", resp.StatusCode)
	}
}

// 2.2: batch-отправка — группа из N участников одним запросом, лимит
// считается на сообщение (один batch), а не на каждый блоб
func TestMessagesBatch(t *testing.T) {
	// 2.1: сами сервер и ts — прямой доступ к стору для массового создания
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	// 50 участников напрямую в стор: email-коды (5/ч на IP) и лимит
	// регистраций не тянут 50 HTTP-регистраций с одного адреса
	newUser := func(login string) string {
		token := "token-" + login
		acc := &account{login: login, pkdfKey: []byte("k"), salt: []byte("s")}
		if conflict, err := srv.store.CreateAccount(acc, "phone", "pub-"+login, token); conflict || err != nil {
			t.Fatalf("создание %s: %v %v", login, conflict, err)
		}
		return token
	}
	aliceToken := newUser("alice")
	recipients := make([]map[string]any, 0, 50)
	for i := 0; i < 50; i++ {
		login := fmt.Sprintf("batch%d", i)
		newUser(login)
		blob := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("to-%d", i)))
		recipients = append(recipients, map[string]any{
			"to_user": login,
			"blobs":   []map[string]string{{"device_id": "phone", "blob": blob}},
		})
	}
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/messages/batch",
		aliceToken, map[string]any{"kind": "chat", "recipients": recipients})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("batch: %d %s", resp.StatusCode, body)
	}
	var out struct {
		Sent   int `json:"sent"`
		Errors []struct {
			To string `json:"to_user"`
			St int    `json:"status"`
		} `json:"errors"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatal(err)
	}
	if out.Sent != 50 || len(out.Errors) != 0 {
		t.Fatalf("batch обязан доставить всех: sent=%d errors=%v", out.Sent, out.Errors)
	}
	// каждый получил свою запись
	for i := 0; i < 50; i++ {
		msgs := drainAuth(t, ts, "token-batch"+fmt.Sprint(i))
		if len(msgs) != 1 {
			t.Fatalf("batch%d: %d записей", i, len(msgs))
		}
	}
	// частичные ошибки: несуществующий получатель в errors, остальные дошли
	recipients = append(recipients, map[string]any{
		"to_user": "ghost",
		"blobs":   []map[string]string{{"device_id": "phone", "blob": base64.StdEncoding.EncodeToString([]byte("x"))}},
	})
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/messages/batch",
		aliceToken, map[string]any{"kind": "chat", "recipients": recipients})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("batch с ghost: %d %s", resp.StatusCode, body)
	}
	json.Unmarshal([]byte(body), &out)
	if out.Sent != 50 || len(out.Errors) != 1 || out.Errors[0].To != "ghost" || out.Errors[0].St != http.StatusNotFound {
		t.Fatalf("частичные ошибки: sent=%d errors=%v", out.Sent, out.Errors)
	}
	// лимит batch: один запрос = одно сообщение (5000/ч не выгорает от 50)
	if resp.StatusCode != http.StatusOK {
		t.Fatal("batch в лимит не упирается")
	}
}

// 2.3: порядок регистрации — код проверяется БЕЗ списания, факт
// «email занят» раскрывается только после верного кода
func TestRegisterEmailHiddenBehindCode(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	// alice уже зарегистрирована — напрямую в стор: email-кулдаун (минуту)
	// не позволяет получить второй код на её адрес в рамках теста
	acc := &account{login: "alice", pkdfKey: []byte("k"), salt: []byte("s")}
	if conflict, err := srv.store.CreateAccount(acc, "phone", "pub-alice", "token-alice"); conflict || err != nil {
		t.Fatalf("создание alice: %v %v", conflict, err)
	}
	if err := srv.store.SaveEmail("alice", hashEmail("alice@test.example")); err != nil {
		t.Fatal(err)
	}
	// alice@test.example уже занят; перебор email без кода не раскрывает факт
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Email: "alice@test.example", Code: "0000", Password: "secret123",
		DeviceID: "phone", PubSPKI: base64.StdEncoding.EncodeToString([]byte("pub-x")),
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("неверный код: %d (ожидали 400, факт занятости скрыт)", resp.StatusCode)
	}
	// коды берём ПО ОДНОМУ на email (кулдаун минуты между кодами)
	codeOf := func(email string) string {
		codeResp, err := ts.Client().Post(ts.URL+"/v1/email-code", "application/json",
			bytes.NewReader([]byte(`{"email":"`+email+`"}`)))
		if err != nil {
			t.Fatal(err)
		}
		var out struct {
			Code string `json:"code"`
		}
		json.NewDecoder(codeResp.Body).Decode(&out)
		codeResp.Body.Close()
		if out.Code == "" {
			t.Fatalf("код для %s не получен", email)
		}
		return out.Code
	}
	// с ВЕРНЫМ кодом — честный 409 (кулдаун не мешает: код уже взят выше)
	aliceCode := codeOf("alice@test.example")
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Email: "alice@test.example", Code: aliceCode, Password: "secret123",
		DeviceID: "phone", PubSPKI: base64.StdEncoding.EncodeToString([]byte("pub-x")),
	})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("верный код + занятый email: %d (ожидали 409)", resp.StatusCode)
	}
	// неверная попытка НЕ сжигает валидный код того же email
	badResp, _ := authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Email: "bob@test.example", Code: "0000", Password: "secret123",
		DeviceID: "phone", PubSPKI: base64.StdEncoding.EncodeToString([]byte("pub-b")),
	})
	if badResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("неверный код для bob: %d (ожидали 400)", badResp.StatusCode)
	}
	bobCode := codeOf("bob@test.example")
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/accounts", "", accountRequest{
		Email: "bob@test.example", Code: bobCode, Password: "secret123",
		DeviceID: "phone", PubSPKI: base64.StdEncoding.EncodeToString([]byte("pub-b")),
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("код не сожжён неверной попыткой: %d", resp.StatusCode)
	}
}

// 2.5: одноразовый секрет опроса линковки. При включённом
// CHAT_LINK_SECRET_REQUIRED опрос без секрета и с неверным секретом
// отвергается; верный секрет — pending/linked как прежде
func TestLinkPollSecret(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	t.Setenv("CHAT_LINK_SECRET_REQUIRED", "1")
	ts := httptest.NewServer(newServer().routes())
	defer ts.Close()
	_, pub := registerUser(t, ts, "alice", "phone")

	// init возвращает nonce и poll_secret
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/link/init", "",
		map[string]string{"device_id": "tablet", "pub_spki": pub})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("init: %d", resp.StatusCode)
	}
	var init struct {
		Nonce      string `json:"nonce"`
		PollSecret string `json:"poll_secret"`
	}
	if err := json.Unmarshal([]byte(body), &init); err != nil {
		t.Fatal(err)
	}
	if init.Nonce == "" || init.PollSecret == "" {
		t.Fatalf("init обязан вернуть nonce и poll_secret: %s", body)
	}
	// опрос без секрета — отвергнут: токен не может забрать перехватчик кода
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/link/"+init.Nonce, "", nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("без секрета: %d (ожидали 400)", resp.StatusCode)
	}
	// с неверным секретом — тоже
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/link/"+init.Nonce+"?secret=wrong", "", nil)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("с неверным секретом: %d (ожидали 400)", resp.StatusCode)
	}
	// с верным — pending
	resp, body = authedJSON(t, ts, http.MethodGet, "/v1/link/"+init.Nonce+"?secret="+init.PollSecret, "", nil)
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, "pending") {
		t.Fatalf("с верным секретом: %d %s", resp.StatusCode, body)
	}
}

// AH1: ревокация устройства — токен и ключ отозванного устройства
// умирают разом; текущее устройство отозвать нельзя; 404 для чужих
func TestRevokeDevice(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	token, _ := registerUser(t, ts, "diana", "phone")
	// второе устройство — напрямую в стор (линковка не в предмете теста)
	if conflict, err := srv.store.PutDeviceKey("diana", "tablet", "pub-tablet", "token-tablet"); conflict || err != nil {
		t.Fatalf("планшет: %v %v", conflict, err)
	}
	// чужой Bearer не ревокает (кейс не в предмете — свой токен ниже)

	// своё текущее устройство отозвать нельзя
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/devices/revoke", token,
		map[string]string{"device_id": "phone"})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("текущее устройство: %d (ожидали 409), тело=%s", resp.StatusCode, body)
	}
	// ревокация планшета
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/devices/revoke", token,
		map[string]string{"device_id": "tablet"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("ревокация планшета: %d, тело=%s", resp.StatusCode, body)
	}
	if auth, _ := srv.store.AuthByToken("token-tablet"); auth != nil {
		t.Fatal("токен планшета жив после ревокации")
	}
	exists, _ := srv.store.DeviceExists("diana", "tablet")
	if exists {
		t.Fatal("устройство живо после ревокации")
	}
	// повторная ревокация — 404
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/devices/revoke", token,
		map[string]string{"device_id": "tablet"})
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("повторная ревокация: %d (ожидали 404)", resp.StatusCode)
	}
	// без Bearer — 401
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/devices/revoke", "",
		map[string]string{"device_id": "tablet"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без токена: %d (ожидали 401)", resp.StatusCode)
	}
	// текущее продолжает работать
	if auth, _ := srv.store.AuthByToken(token); auth == nil || auth.deviceID != "phone" {
		t.Fatal("токен текущего устройства повреждён ревокацией")
	}
}

// AH9: смена email — код на новую почту, логин по новому адресу,
// старый хэш больше не резолвится; неверный код не сжигает валидный
func TestChangeEmail(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	token, _ := registerUser(t, ts, "eve", "phone")

	codeOf := func(email string) string {
		codeResp, err := ts.Client().Post(ts.URL+"/v1/email-code", "application/json",
			bytes.NewReader([]byte(`{"email":"`+email+`"}`)))
		if err != nil {
			t.Fatal(err)
		}
		var out struct {
			Code string `json:"code"`
		}
		json.NewDecoder(codeResp.Body).Decode(&out)
		codeResp.Body.Close()
		if out.Code == "" {
			t.Fatalf("код для %s не получен", email)
		}
		return out.Code
	}
	// чужой email занят: аккаунт frank создан напрямую, без кодов
	frank := &account{login: "frank", pkdfKey: []byte("k"), salt: []byte("s")}
	if conflict, err := srv.store.CreateAccount(frank, "phone", "pub-frank", "token-frank"); conflict || err != nil {
		t.Fatalf("создание frank: %v %v", conflict, err)
	}
	if err := srv.store.SaveEmail("frank", hashEmail("frank@test.example")); err != nil {
		t.Fatal(err)
	}

	// неверный код не сжигает валидный (код берём заранее)
	newCode := codeOf("eve2@test.example")
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/account/email", token,
		map[string]string{"new_email": "eve2@test.example", "code": "0000"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("неверный код: %d (ожидали 400), тело=%s", resp.StatusCode, body)
	}
	// занятый email — честный 409 c кодом email_taken (код frank@ не нужен:
	// проверка занятости после кода — нужен код на frank@; обходим кулдаун
	// прямым сохранением кода в стор)
	if err := srv.store.SaveEmailCode("frank@test.example", "4321", time.Now().Add(15*time.Minute)); err != nil {
		t.Fatal(err)
	}
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/account/email", token,
		map[string]string{"new_email": "frank@test.example", "code": "4321"})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("занятый email: %d (ожидали 409), тело=%s", resp.StatusCode, body)
	}
	// верный код — смена прошла
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/account/email", token,
		map[string]string{"new_email": "eve2@test.example", "code": newCode})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("смена email: %d, тело=%s", resp.StatusCode, body)
	}
	// новый хэш резолвит логин, старый — нет
	if got, _ := srv.store.LoginByEmailHash(hashEmail("eve2@test.example")); got != "eve" {
		t.Fatalf("новый email не резолвится: %q", got)
	}
	if got, _ := srv.store.LoginByEmailHash(hashEmail("eve@test.example")); got != "" {
		t.Fatalf("старый email всё ещё резолвится: %q", got)
	}
	// вход по новому email
	loginBody, _ := json.Marshal(accountRequest{
		Login: "eve2@test.example", Password: "secret123", DeviceID: "phone",
	})
	loginResp, err := ts.Client().Post(ts.URL+"/v1/login", "application/json", bytes.NewReader(loginBody))
	if err != nil {
		t.Fatal(err)
	}
	defer loginResp.Body.Close()
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("вход по новому email: %d", loginResp.StatusCode)
	}
	// без Bearer — 401
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/account/email", "",
		map[string]string{"new_email": "x@y.example", "code": "1111"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без токена: %d (ожидали 401)", resp.StatusCode)
	}
}

// AH10: смена пароля — старый обязателен, вход работает по новому
func TestChangePassword(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	token, _ := registerUser(t, ts, "gina", "phone")

	// неверный текущий — 401 без смены
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/account/password", token,
		map[string]string{"current_password": "wrong", "new_password": "newsecret123"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("неверный текущий: %d (ожидали 401), тело=%s", resp.StatusCode, body)
	}
	// короткий новый — 400
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/account/password", token,
		map[string]string{"current_password": "secret123", "new_password": "123"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("короткий новый: %d (ожидали 400)", resp.StatusCode)
	}
	// верная смена
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/account/password", token,
		map[string]string{"current_password": "secret123", "new_password": "newsecret123"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("смена пароля: %d, тело=%s", resp.StatusCode, body)
	}
	// вход по новому паролю
	loginBody, _ := json.Marshal(accountRequest{
		Login: "gina", Password: "newsecret123", DeviceID: "phone",
	})
	loginResp, err := ts.Client().Post(ts.URL+"/v1/login", "application/json", bytes.NewReader(loginBody))
	if err != nil {
		t.Fatal(err)
	}
	defer loginResp.Body.Close()
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("вход по новому паролю: %d", loginResp.StatusCode)
	}
	// старый пароль больше не работает
	badLogin, _ := json.Marshal(accountRequest{
		Login: "gina", Password: "secret123", DeviceID: "phone",
	})
	badResp, err := ts.Client().Post(ts.URL+"/v1/login", "application/json", bytes.NewReader(badLogin))
	if err != nil {
		t.Fatal(err)
	}
	badResp.Body.Close()
	if badResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("старый пароль жив: %d", badResp.StatusCode)
	}
	// без Bearer — 401
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/account/password", "",
		map[string]string{"current_password": "x", "new_password": "yyyyyyy"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без токена: %d (ожидали 401)", resp.StatusCode)
	}
}

// AI1: удаление аккаунта — пароль обязателен, всё умирает: ключи, токены,
// очередь, пуш-токены, email-хэши; WS рвётся; логин освобождается
func TestDeleteAccount(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "goner", "phone")
	// свежий токен: логин ротирует токены, регистрируемый уже не действует
	token := tokenFor(t, ts, "goner")
	if conflict, err := srv.store.PutDeviceKey("goner", "tablet", "pub-tablet", "token-tablet"); conflict || err != nil {
		t.Fatalf("планшет: %v %v", conflict, err)
	}
	if err := srv.store.SaveEmail("goner", hashEmail("goner@test.example")); err != nil {
		t.Fatal(err)
	}
	if err := srv.store.SavePushToken("goner", "phone", "fcm", "pushtoken"); err != nil {
		t.Fatal(err)
	}
	// транзит для планшета
	if code := sendBlob(t, ts, token, "goner", "chat", "tablet", "WFla"); code != http.StatusOK {
		t.Fatalf("отправка в очередь: %d", code)
	}

	// без пароля — 400
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/account/delete", token, map[string]string{})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("без пароля: %d (ожидали 400)", resp.StatusCode)
	}
	// неверный пароль — 401, аккаунт жив
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/account/delete", token,
		map[string]string{"password": "wrong-pass"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("неверный пароль: %d, тело=%s", resp.StatusCode, body)
	}
	if auth, _ := srv.store.AuthByToken(token); auth == nil {
		t.Fatal("аккаунт умер от неверного пароля")
	}

	// верный пароль — аккаунт снесён
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/account/delete", token,
		map[string]string{"password": "secret123"})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, "deleted") {
		t.Fatalf("удаление: %d, тело=%s", resp.StatusCode, body)
	}
	if auth, _ := srv.store.AuthByToken(token); auth != nil {
		t.Fatal("токен телефона жив после удаления аккаунта")
	}
	if auth, _ := srv.store.AuthByToken("token-tablet"); auth != nil {
		t.Fatal("токен планшета жив после удаления аккаунта")
	}
	if acc, _ := srv.store.AccountByLogin("goner"); acc != nil {
		t.Fatal("аккаунт жив после удаления")
	}
	if exists, _ := srv.store.DeviceExists("goner", "phone"); exists {
		t.Fatal("устройство живо после удаления аккаунта")
	}
	msgs, _, err := srv.store.Drain("goner", "tablet", time.Now(), drainDefaultLimit, drainByteCeiling, 0)
	if err != nil || len(msgs) != 0 {
		t.Fatalf("очередь планшета жива: %v err=%v", msgs, err)
	}
	if login, _ := srv.store.LoginByEmailHash(hashEmail("goner@test.example")); login != "" {
		t.Fatal("email-хэш жив после удаления аккаунта")
	}
	if count, _ := srv.store.QueueDepths(); len(count) > 0 {
		t.Fatalf("глубины очереди не пусты: %v", count)
	}
	// токен мёртв — повторный вызов 401
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/account/delete", token,
		map[string]string{"password": "secret123"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("повторное удаление: %d (ожидали 401)", resp.StatusCode)
	}
	// логин свободен: свежий аккаунт с тем же логином создаётся
	// (без email-кода — cooldown между кодами в тесте не важен)
	if conflict, err := srv.store.CreateAccount(
		&account{login: "goner", pkdfKey: []byte("k"), salt: []byte("s"), iters: 40000},
		"phone2", "pub-2", "token-new"); conflict || err != nil {
		t.Fatalf("логин не освободился: conflict=%v err=%v", conflict, err)
	}
}

// AI2: статус «в сети» — онлайн по живому WS, «был(а)» после разрыва,
// скрытый режим не раскрывает ничего, своего не скрывает от себя
func TestOnlinePresence(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	aliceToken, _ := registerUser(t, ts, "alice", "phone")
	bobToken, _ := registerUser(t, ts, "bob", "phone")

	// A5: несуществующий пользователь — тот же ответ, что и скрытый
	// (200, online:0, last_seen:0): 404 позволял отличать «нет такого»
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
		map[string]string{"user": "nobody"})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"online":0`) || !strings.Contains(body, `"last_seen":0`) {
		t.Fatalf("несуществующий: %d, тело=%s (ожидали 200 online:0 last_seen:0)", resp.StatusCode, body)
	}

	// без WS — не в сети, время неизвестно
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
		map[string]string{"user": "bob"})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"online":0`) || !strings.Contains(body, `"last_seen":0`) {
		t.Fatalf("офлайн: %d, тело=%s", resp.StatusCode, body)
	}

	// bob подключает WS — онлайн
	header := http.Header{"Authorization": []string{"Bearer " + bobToken}}
	wsURL := strings.Replace(ts.URL, "http", "ws", 1) + "/v1/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatal(err)
	}
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
		map[string]string{"user": "bob"})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"online":1`) {
		t.Fatalf("онлайн: %d, тело=%s", resp.StatusCode, body)
	}

	// bob скрывает статус — для alice он снова «неизвестно»
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/presence/privacy", bobToken,
		map[string]bool{"hide": true})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("скрытие статуса: %d", resp.StatusCode)
	}
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
		map[string]string{"user": "bob"})
	if !strings.Contains(body, `"online":0`) || !strings.Contains(body, `"last_seen":0`) {
		t.Fatalf("скрытый: тело=%s", body)
	}

	// разрыв WS под скрытым режимом — время не фиксируем на виду,
	// но после снятия скрытия last_seen остаётся нулём, пока сокет
	// не закрылся при снятом флаге: закрываем, снимаем, спрашиваем
	conn.Close()
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/presence/privacy", bobToken,
		map[string]bool{"hide": false})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("снятие скрытия: %d", resp.StatusCode)
	}
	// WS закрылся ДО снятия флага — TouchLastSeen под скрытым не звался?
	// сервер фиксирует момент последнего разрыва независимо от флага,
	// но не раскрывает его, пока флаг стоит: после снятия ответ несёт время
	deadline := time.Now().Add(2 * time.Second)
	online := 1
	for time.Now().Before(deadline) {
		resp, body = authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
			map[string]string{"user": "bob"})
		if strings.Contains(body, `"online":0`) && !strings.Contains(body, `"last_seen":0`) {
			online = 0
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if online != 0 {
		t.Fatalf("после разрыва WS bob должен быть офлайн с временем, тело=%s", body)
	}

	// мусорные запросы
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
		map[string]string{"user": "Не-Логин!"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("мусорный логин: %d (ожидали 400)", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/presence/privacy", bobToken, map[string]any{})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("пустая приватность: %d (ожидали 400)", resp.StatusCode)
	}
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/online", "",
		map[string]string{"user": "bob"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без Bearer: %d (ожидали 401)", resp.StatusCode)
	}
}

// AJ (аналог Delete account if inactive у Telegram): порог простоя
// ставится с подтверждением пароля; простаивавший свыше порога аккаунт
// сносится прогоном sweep — по пути ручного удаления (AI1).
func TestAccountInactivity(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	registerUser(t, ts, "sleeper", "phone")
	token := tokenFor(t, ts, "sleeper")

	// недопустимый порог — 400
	resp, _ := authedJSON(t, ts, http.MethodPost, "/v1/account/inactivity", token,
		map[string]any{"months": 5, "password": "secret123"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("кривой порог: %d (ожидали 400)", resp.StatusCode)
	}
	// без пароля — 400
	resp, _ = authedJSON(t, ts, http.MethodPost, "/v1/account/inactivity", token,
		map[string]any{"months": 3})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("без пароля: %d (ожидали 400)", resp.StatusCode)
	}
	// неверный пароль — 401, порог не встал
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/account/inactivity", token,
		map[string]any{"months": 3, "password": "wrong"})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("неверный пароль: %d, тело=%s", resp.StatusCode, body)
	}
	// верный пароль — порог встал
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/account/inactivity", token,
		map[string]any{"months": 3, "password": "secret123"})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"months":3`) {
		t.Fatalf("порог: %d, тело=%s", resp.StatusCode, body)
	}

	// свежий аккаунт — кандидатов нет
	logins, err := srv.store.InactiveAccountLogins(time.Now())
	if err != nil || len(logins) != 0 {
		t.Fatalf("свежий аккаунт уже кандидат: %v err=%v", logins, err)
	}

	// 4 месяца без визитов — кандидат появился
	if err := srv.store.TouchLastSeen("sleeper", time.Now().AddDate(0, -4, 0)); err != nil {
		t.Fatal(err)
	}
	logins, err = srv.store.InactiveAccountLogins(time.Now())
	if err != nil || len(logins) != 1 || logins[0] != "sleeper" {
		t.Fatalf("кандидаты: %v err=%v", logins, err)
	}

	// прогон: аккаунт снесён, токен мёртв, метрика выросла
	srv.sweepInactiveAccounts()
	if acc, _ := srv.store.AccountByLogin("sleeper"); acc != nil {
		t.Fatal("аккаунт жив после sweep")
	}
	if auth, _ := srv.store.AuthByToken(token); auth != nil {
		t.Fatal("токен жив после sweep")
	}
	if !strings.Contains(renderMetrics(srv), "chat_accounts_inactive_deleted_total 1") {
		t.Fatal("метрика сноса не выросла")
	}
}

// AJ: порог 0 — аккаунт вечен, сколько бы он ни простаивал
func TestInactivityOffNeverSweeps(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	registerUser(t, ts, "keeper", "phone")
	token := tokenFor(t, ts, "keeper")

	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/account/inactivity", token,
		map[string]any{"months": 12, "password": "secret123"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("порог: %d, тело=%s", resp.StatusCode, body)
	}
	// снятие порога — 0
	resp, body = authedJSON(t, ts, http.MethodPost, "/v1/account/inactivity", token,
		map[string]any{"months": 0, "password": "secret123"})
	if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"months":0`) {
		t.Fatalf("снятие: %d, тело=%s", resp.StatusCode, body)
	}
	if err := srv.store.TouchLastSeen("keeper", time.Now().AddDate(-2, 0, 0)); err != nil {
		t.Fatal(err)
	}
	srv.sweepInactiveAccounts()
	if acc, _ := srv.store.AccountByLogin("keeper"); acc == nil {
		t.Fatal("аккаунт снесён при выключенном пороге")
	}
}

// 2.1: справочник ключей ограничен по IP — после keysPerHour чтений 429,
// окно прошло — снова 200. До правки лимитер не вызывался вовсе.
func TestGetKeysRateLimited(t *testing.T) {
	srv := newServer()
	base := time.Now()
	srv.now = func() time.Time { return base }
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()

	for i := 0; i < keysPerHour; i++ {
		resp, _ := authedJSON(t, ts, http.MethodGet, "/v1/keys/alice", "", nil)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("keys %d: %d (ожидали 200)", i, resp.StatusCode)
		}
	}
	resp, _ := authedJSON(t, ts, http.MethodGet, "/v1/keys/alice", "", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("keys сверх лимита: %d (ожидали 429)", resp.StatusCode)
	}
	srv.now = func() time.Time { return base.Add(61 * time.Minute) }
	resp, _ = authedJSON(t, ts, http.MethodGet, "/v1/keys/alice", "", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("после окна: %d (ожидали 200)", resp.StatusCode)
	}
}

// A5: квота отправителя в очереди получателя — после maxQueuePerSender
// сообщений от одного отправителя — 429, но ДРУГОЙ отправитель проходит:
// флуд не вытесняет чужие сообщения жертвы
func TestQueueSenderQuota(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	base := time.Now()
	srv.now = func() time.Time { return base }
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "alice", "phone")
	_, _ = registerUser(t, ts, "mallory", "phone")
	_, _ = registerUser(t, ts, "bob", "phone")
	aliceToken := tokenFor(t, ts, "alice")
	malloryToken := tokenFor(t, ts, "mallory")
	blob := base64.StdEncoding.EncodeToString([]byte("flood"))
	for i := 0; i < maxQueuePerSender; i++ {
		if code := sendBlob(t, ts, malloryToken, "bob", "chat", "phone", blob); code != http.StatusOK {
			t.Fatalf("mallory %d: %d (ожидали 200)", i, code)
		}
	}
	if code := sendBlob(t, ts, malloryToken, "bob", "chat", "phone", blob); code != http.StatusTooManyRequests {
		t.Fatalf("сверх per-sender квоты: %d (ожидали 429)", code)
	}
	// другой отправитель не пострадал
	if code := sendBlob(t, ts, aliceToken, "bob", "chat", "phone", blob); code != http.StatusOK {
		t.Fatalf("alice после флуда mallory: %d (ожидали 200)", code)
	}
	// очередь bob содержит и чужое сообщение — не вытеснено
	msgs := drainAuth(t, ts, tokenFor(t, ts, "bob"))
	fromAlice := 0
	for _, m := range msgs {
		if m.From == "alice" {
			fromAlice++
		}
	}
	if fromAlice == 0 {
		t.Fatalf("сообщение alice вытеснено флудом — квота не сработала")
	}
}

// A5: потолок на число РАЗНЫХ отправителей в одной очереди
func TestQueueSenderCountCap(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	base := time.Now()
	srv.now = func() time.Time { return base }
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	_, _ = registerUser(t, ts, "bob", "phone")
	blob := base64.StdEncoding.EncodeToString([]byte("x"))
	oldCap := maxQueueSenders
	maxQueueSenders = 3
	defer func() { maxQueueSenders = oldCap }()
	for i := 0; i < maxQueueSenders; i++ {
		login := fmt.Sprintf("u%03d", i)
		_, _ = registerUser(t, ts, login, "phone")
		if code := sendBlob(t, ts, tokenFor(t, ts, login), "bob", "chat", "phone", blob); code != http.StatusOK {
			t.Fatalf("отправитель %d: %d (ожидали 200)", i, code)
		}
	}
	_, _ = registerUser(t, ts, "unlucky", "phone")
	if code := sendBlob(t, ts, tokenFor(t, ts, "unlucky"), "bob", "chat", "phone", blob); code != http.StatusTooManyRequests {
		t.Fatalf("новый отправитель сверх потолка: %d (ожидали 429)", code)
	}
}

// A5: last_seen в /v1/online огрублён до начала суток UTC —
// точное время визита не раскрывается
func TestOnlineLastSeenBucketed(t *testing.T) {
	t.Setenv("CHAT_DEV_MODE", "1")
	srv := newServer()
	now := time.Date(2026, 10, 29, 15, 42, 11, 0, time.UTC)
	srv.now = func() time.Time { return now }
	ts := httptest.NewServer(srv.routes())
	defer ts.Close()
	aliceToken, _ := registerUser(t, ts, "alice", "phone")
	bobToken, _ := registerUser(t, ts, "bob", "phone")
	// bob был в сети 3 часа назад
	if err := srv.store.TouchLastSeen("bob", now.Add(-3*time.Hour)); err != nil {
		t.Fatal(err)
	}
	_ = bobToken
	resp, body := authedJSON(t, ts, http.MethodPost, "/v1/online", aliceToken,
		map[string]string{"user": "bob"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("online: %d", resp.StatusCode)
	}
	want := time.Date(2026, 10, 29, 0, 0, 0, 0, time.UTC).Unix()
	if !strings.Contains(body, fmt.Sprintf(`"last_seen":%d`, want)) {
		t.Fatalf("last_seen должен быть огрублён до начала суток (%d), тело=%s", want, body)
	}
	// точное время (15:42-3ч = 12:42) не должно светиться
	if strings.Contains(body, fmt.Sprintf(`"last_seen":%d`, now.Add(-3*time.Hour).Unix())) {
		t.Fatalf("точный last_seen утёк: %s", body)
	}
}

// A6.5: коллизия логина даёт alice2, а не alic2
func TestLoginCollisionSuffix(t *testing.T) {
	candidate := func(login string, i int) string { return fmt.Sprintf("%s%d", login, i) }
	c := candidate("alice", 2)
	if c != "alice2" {
		t.Fatalf("ожидался alice2, got %q", c)
	}
}

// A6.6: пустой токен не регистрируется как валидный Bearer
func TestPutDeviceKeyRejectsEmptyToken(t *testing.T) {
	m := newMemStore()
	if _, err := m.PutDeviceKey("u", "dev", "pub", ""); err == nil {
		t.Fatal("пустой токен должен отклоняться")
	}
	if _, ok := m.byToken[hashToken("")]; ok {
		t.Fatal("hashToken(\"\") не должен быть валидным bearer")
	}
}

// A6.1: все поля сервера типа *ipLimiter обязаны входить в sweepLimiters
func TestAllIpLimitersSwept(t *testing.T) {
	s := &server{}
	// собираем имена полей *ipLimiter через reflect
	rv := reflect.ValueOf(s).Elem()
	rt := rv.Type()
	names := []string{}
	for i := 0; i < rt.NumField(); i++ {
		if rt.Field(i).Type == reflect.TypeOf((*ipLimiter)(nil)) {
			names = append(names, rt.Field(i).Name)
		}
	}
	if len(names) == 0 {
		t.Fatal("не найдено полей *ipLimiter — тест устарел?")
	}
	// парсим sweep.go и проверяем, что каждое поле там упомянуто
	src, err := os.ReadFile("sweep.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, n := range names {
		if !strings.Contains(string(src), "s."+n) {
			t.Errorf("лимитер %s не чистится в sweepLimiters", n)
		}
	}
}

// A6.3: hex-валидация хэшей e-mail
func TestIsHex64(t *testing.T) {
	good := strings.Repeat("a1", 32)
	if !isHex64(good) {
		t.Fatal("валидный hex отклонён")
	}
	bad := []string{
		strings.Repeat("z", 64),
		strings.Repeat("a", 63),
		strings.Repeat("a", 65),
		strings.Repeat("a", 63) + "-",
	}
	for _, b := range bad {
		if isHex64(b) {
			t.Errorf("невалидный hex принят: %q", b)
		}
	}
}
