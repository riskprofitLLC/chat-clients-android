// AI2 (аналог статуса «в сети» у Telegram): спрашивающий узнаёт,
// есть ли у собеседника живые WS-соединения, и когда он был в сети
// последний раз. Приватность: владелец может запретить отвечать
// (hide_presence) — тогда для всех он «не в сети». Метаданные уровня
// typing/read, plaintext не касается.
package main

import (
	"errors"
	"net/http"
	"time"
)

// POST /v1/online {"user": "<login>"} (Bearer).
// Ответ: {"online": 0|1, "last_seen": unix-время|0}.
// last_seen = 0 — «не в сети и неизвестно, когда был(а)».
func (s *server) handleOnline(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком часто", http.StatusTooManyRequests)
		return
	}
	var req struct {
		User string `json:"user"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if !loginRe.MatchString(req.User) {
		http.Error(w, "некорректные поля", http.StatusBadRequest)
		return
	}
	// A5: лимит по ЦЕЛИ (caller→target), чтобы один аккаунт не перебирал
	// чужие логины через этот эндпоинт
	if !s.onlineTargetLimit.allow(auth.login+"/"+req.User, s.now()) {
		http.Error(w, "слишком много запросов, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	acc, err := s.store.AccountByLogin(req.User)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	// A5: единый ответ для несуществующего и скрытого — иначе 404
	// позволяет отличать «нет такого» от «скрыл(а)» (слежка/перебор)
	if acc == nil || acc.hidePresence {
		writeJSON(w, map[string]any{"online": 0, "last_seen": 0})
		return
	}
	if n := s.hub.onlineCount(req.User); n > 0 {
		writeJSON(w, map[string]any{"online": 1, "last_seen": 0})
		return
	}
	// A5: точное время визита не раскрываем — last_seen огрублён до
	// начала суток UTC (дня), этого достаточно для «был(а) в сети»
	lastSeen := int64(0)
	if !acc.lastSeen.IsZero() {
		t := acc.lastSeen.UTC()
		dayStart := time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
		lastSeen = dayStart.Unix()
	}
	writeJSON(w, map[string]any{"online": 0, "last_seen": lastSeen})
}

// POST /v1/presence/privacy {"hide": true|false} (Bearer).
// Управление собственным статусом «в сети» — аналог настройки
// приватности Telegram. Текущее значение возвращается в ответе.
func (s *server) handlePresencePrivacy(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком часто", http.StatusTooManyRequests)
		return
	}
	var req struct {
		Hide *bool `json:"hide"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if req.Hide == nil {
		http.Error(w, "некорректные поля", http.StatusBadRequest)
		return
	}
	if err := s.store.SetPresenceHidden(auth.login, *req.Hide); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	hidden := 0
	if *req.Hide {
		hidden = 1
	}
	writeJSON(w, map[string]any{"status": "changed", "hidden": hidden})
}

// AI2: человекочитаемый возраст последнего визита для логов/тестов
func lastSeenAgo(at time.Time, now time.Time) string {
	if at.IsZero() {
		return "неизвестно"
	}
	d := now.Sub(at)
	if d < time.Minute {
		return "только что"
	}
	if d < time.Hour {
		return "недавно"
	}
	return "давно"
}
