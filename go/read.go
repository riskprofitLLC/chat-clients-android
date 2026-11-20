// POST /v1/read Bearer {"from_user":...}
// — читатель сообщает автору, что сообщения открыты (синие галочки).
// Метаданные уровня delivered, plaintext не касается.
package main

import (
	"net/http"
)

func (s *server) handleRead(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		From string `json:"from_user"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	// S5: автор — по регэкспу, поток — лимитом на аккаунт
	if req.From == "" || !loginRe.MatchString(req.From) {
		http.Error(w, "некорректные поля", http.StatusBadRequest)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком часто", http.StatusTooManyRequests)
		return
	}
	// будим все устройства автора кадром "read:<читатель>"
	// A6.10: метаданные только для существующего получателя
	if acc, err := s.store.AccountByLogin(req.From); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	} else if acc == nil {
		w.WriteHeader(http.StatusOK) // получателя нет — тихо игнорируем
		return
	}
	s.hub.notifyAll(req.From, "read:"+auth.login)
	w.WriteHeader(http.StatusOK)
}
