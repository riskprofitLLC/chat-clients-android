// POST /v1/delivered Bearer {"to_user":...,"to_device":...}
// — получатель подтверждает доставку (для статуса ✓✓ у отправителя).
// Метаданные: кадр "delivered:<кто>:<устройство>" уходит автору по WS
package main

import (
	"net/http"
)

func (s *server) handleDelivered(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		From     string `json:"from_user"`
		ToDevice string `json:"to_device"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	// S5: адресат и устройство валидируются регэкспами — прежде кадр
	// собирался из непроверенной строки, а события не лимитировались
	if req.From == "" || req.ToDevice == "" ||
		!loginRe.MatchString(req.From) || !deviceRe.MatchString(req.ToDevice) {
		http.Error(w, "некорректные поля", http.StatusBadRequest)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком часто", http.StatusTooManyRequests)
		return
	}
	// A6.10: метаданные только для существующего получателя
	if acc, err := s.store.AccountByLogin(req.From); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	} else if acc == nil {
		w.WriteHeader(http.StatusOK) // получателя нет — тихо игнорируем
		return
	}
	s.hub.notifyAll(req.From, "delivered:"+auth.login+":"+req.ToDevice)
	w.WriteHeader(http.StatusOK)
}
