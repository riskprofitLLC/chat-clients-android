// AJ (аналог Delete account if inactive у Telegram): аккаунт без
// визитов дольше выбранного порога сносится целиком — по пути AI1.
// Порог ставит владелец (0/3/6/12 месяцев) с подтверждением пароля;
// база простоя — последний разрыв WS, у никогда не заходивших —
// время создания. Живые прямо сейчас аккаунты sweep не трогает.
package main

import (
	"crypto/hmac"
	"errors"
	"log"
	"net/http"
	"time"
)

// разрешённые пороги, месяцев
var inactivityMonthsAllowed = map[int]bool{0: true, 3: true, 6: true, 12: true}

// POST /v1/account/inactivity {"months": 0|3|6|12, "password": …} (Bearer).
// Ответ: {"months": N}.
func (s *server) handleAccountInactivity(w http.ResponseWriter, r *http.Request) {
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
		Months   int    `json:"months"`
		Password string `json:"password"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if !inactivityMonthsAllowed[req.Months] {
		http.Error(w, "недопустимый порог", http.StatusBadRequest)
		return
	}
	if req.Password == "" {
		http.Error(w, "пароль обязателен", http.StatusBadRequest)
		return
	}
	acc, err := s.store.AccountByLogin(auth.login)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if acc == nil {
		http.Error(w, "аккаунт не найден", http.StatusNotFound)
		return
	}
	// пароль — по стоимости KDF записи (как при удалении аккаунта)
	iters := acc.iters
	if iters <= 0 {
		iters = pbkdf2LegacyIterations
	}
	key := pbkdf2([]byte(req.Password), acc.salt, iters, 32)
	if !hmac.Equal(key, acc.pkdfKey) {
		writeErr(w, http.StatusUnauthorized, "bad_password", "неверный пароль")
		return
	}
	if err := s.store.SetAccountInactivity(auth.login, req.Months); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]int{"months": req.Months})
}

// один прогон: простаивавшие свыше порога аккаунты сносятся тем же
// путём, что и ручное удаление (AI1); онлайн — неприкосновенны
func (s *server) sweepInactiveAccounts() {
	logins, err := s.store.InactiveAccountLogins(s.now())
	if err != nil {
		log.Printf("inactivity: список простаивающих не получен: %v", err)
		return
	}
	for _, login := range logins {
		// живое соединение — аккаунт не простаивает, как бы ни
		// выглядел last_seen
		if s.hub.onlineCount(login) > 0 {
			continue
		}
		if err := s.store.DeleteAccount(login); err != nil {
			if errors.Is(err, errAccountNotFound) {
				continue
			}
			log.Printf("inactivity: не удалось снести %s: %v", login, err)
			continue
		}
		s.hub.disconnectUser(login)
		s.statInactiveDeleted(login)
		log.Printf("inactivity: аккаунт %s удалён после простоя", login)
	}
}

// ежедневный прогон + нагон при старте
func startAccountSweeper(s *server, stop <-chan struct{}) {
	s.sweepInactiveAccounts() // старт: нагоняем упущенное за время простоя релея
	go func() {
		ticker := time.NewTicker(24 * time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				s.sweepInactiveAccounts()
			}
		}
	}()
}
