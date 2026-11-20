// Email-контакт-лист: автоматическое обнаружение знакомых (аналог
// телефонной книги Telegram, но через SHA-256 хэши email вместо номеров).
// Сервер никогда не видит сырой email — только хэш. Приватность выше,
// чем у Telegram: клиент отправляет хэши, сервер не может их реверснуть.

package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
)

// POST /v1/discover Bearer {"email_hashes": ["hex64", ...]}
// → {"matches": [{"login": "...", "email_hash": "..."}]}
// Ответ содержит только ЛОГИНЫ найденных пользователей — email-адрес
// клиент уже знает (он есть в его контактной книге).
func (s *server) handleDiscover(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	_ = auth // аутентификация есть, но получатель не важен — только совпадения

	// 2.1: за Caddy лимит считается по клиентскому IP, иначе он общий
	if !s.discoverLimit.allow(s.clientIP(r), s.now()) {
		http.Error(w, "слишком много запросов, попробуйте позже", http.StatusTooManyRequests)
		return
	}

	var req struct {
		EmailHashes []string `json:"email_hashes"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if len(req.EmailHashes) == 0 || len(req.EmailHashes) > 500 {
		http.Error(w, "пусто или слишком много (макс 500)", http.StatusBadRequest)
		return
	}

	// валидируем формат хэшей (ровно 64 hex-символа)
	for _, h := range req.EmailHashes {
		if len(h) != 64 || !isHex64(h) {
			http.Error(w, "некорректный хэш", http.StatusBadRequest)
			return
		}
	}
	// A6.3: суточный лимит discover на АККАУНТ (не только по IP) — discover
	// иначе работает оракулом «зарегистрирован ли e-mail X»
	if !s.discoverAccountLimit.allow("login:"+auth.login, s.now()) {
		http.Error(w, "слишком много поисков, попробуйте позже", http.StatusTooManyRequests)
		return
	}

	matches, err := s.store.FindEmailMatches(req.EmailHashes)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]any{"matches": matches})
}

// ровно 64 символа [0-9a-f]
func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F') {
			return false
		}
	}
	return true
}

// хэширование email на сервере (при регистрации)
func hashEmail(email string) string {
	sum := sha256.Sum256([]byte(email))
	return hex.EncodeToString(sum[:])
}

// S5: у http.MaxBytesError НЕТ метода MaxBytesError() — прежняя проверка
// через интерфейс всегда возвращала false, и 413 превращался в до-400
func isMaxBytes(err error) bool {
	var maxErr *http.MaxBytesError
	return errors.As(err, &maxErr)
}
