// AH1/AH9: управление аккаунтом с живого Bearer — ревокация
// устройства (аналог Terminate у Telegram) и смена email (аналог
// смены номера). Оба требуют уже валидную сессию владельца.

package main

import (
	"crypto/hmac"
	"crypto/rand"
	"errors"
	"net/http"
)

// POST /v1/devices/revoke {"device_id": "..."} (Bearer).
// Ключ, токен и пуш-токен устройства умирают разом; логин с него
// больше не пройдёт (DeviceExists = false). Текущее устройство
// отзывать нельзя — владелец не должен выпасть из аккаунта мимо.
// GH3 (аналог Active Sessions у Telegram): список своих устройств
func (s *server) handleListDevices(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	devices, err := s.store.ListDevices(auth.login)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	out := make([]map[string]any, 0, len(devices))
	for _, d := range devices {
		out = append(out, map[string]any{
			"device_id": d.DeviceID,
			"current":   d.DeviceID == auth.deviceID,
		})
	}
	writeJSON(w, map[string]any{"devices": out})
}

func (s *server) handleRevokeDevice(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req struct {
		DeviceID string `json:"device_id"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if !deviceRe.MatchString(req.DeviceID) {
		http.Error(w, "некорректное устройство", http.StatusBadRequest)
		return
	}
	if req.DeviceID == auth.deviceID {
		writeErr(w, http.StatusConflict, "current_device",
			"текущее устройство отключить нельзя — выйдите из аккаунта")
		return
	}
	removed, err := s.store.RemoveDevice(auth.login, req.DeviceID)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if !removed {
		http.Error(w, "устройство не найдено", http.StatusNotFound)
		return
	}
	writeJSON(w, map[string]string{"status": "revoked"})
}

// POST /v1/account/email {"new_email": "...", "code": "..."} (Bearer).
// Смена email: код подтверждения высылается заранее обычным
// POST /v1/email-code на НОВУЮ почту; текущая сессия уже доказывает
// владение аккаунтом. Порядок как в регистрации (2.3): код — без
// списания, затем «email занят», и только потом код потребляется.
func (s *server) handleChangeEmail(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req struct {
		NewEmail string `json:"new_email"`
		Code     string `json:"code"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	req.NewEmail = trimLower(req.NewEmail)
	if !emailRe.MatchString(req.NewEmail) || req.Code == "" {
		http.Error(w, "некорректные поля (email или код)", http.StatusBadRequest)
		return
	}
	// код без списания: неверный код не сжигается и не светит email
	if !s.store.CheckEmailCode(req.NewEmail, req.Code, s.now()) {
		writeErr(w, http.StatusBadRequest, "bad_code", "неверный или истёкший код")
		return
	}
	newHash := hashEmail(req.NewEmail)
	existing, err := s.store.LoginByEmailHash(newHash)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if existing != "" && existing != auth.login {
		writeErr(w, http.StatusConflict, "email_taken", "email уже зарегистрирован")
		return
	}
	// расходная операция — последней
	if !s.store.ConsumeEmailCode(req.NewEmail, req.Code, s.now()) {
		writeErr(w, http.StatusBadRequest, "bad_code", "неверный или истёкший код")
		return
	}
	if err := s.store.ReplaceEmail(auth.login, newHash); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"status": "changed"})
}

// AH10 (аналог смены пароля у Telegram): проверка старого пароля —
// по стоимости KDF записи (легаси-аккаунты проверяются по своей), затем
// перехэш до текущей стоимости. Токены не отзываются — сессии живут
func (s *server) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if len(req.NewPassword) < 6 {
		http.Error(w, "новый пароль слишком короткий", http.StatusBadRequest)
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
	// проверка старого — по стоимости KDF записи (как при входе)
	iters := acc.iters
	if iters <= 0 {
		iters = pbkdf2LegacyIterations
	}
	key := pbkdf2([]byte(req.CurrentPassword), acc.salt, iters, 32)
	if !hmac.Equal(key, acc.pkdfKey) {
		writeErr(w, http.StatusUnauthorized, "bad_password", "неверный текущий пароль")
		return
	}
	// новый пароль — сразу по текущей стоимости
	freshSalt := make([]byte, 16)
	if _, err := rand.Read(freshSalt); err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	freshKey := pbkdf2([]byte(req.NewPassword), freshSalt, pbkdf2Iterations, 32)
	if err := s.store.UpdateAccountHash(auth.login, freshSalt, freshKey, pbkdf2Iterations); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"status": "changed"})
}

// GH2 (аналог Cloud Password у Telegram): установка/снятие облачного пароля.
// Требуется текущий пароль аккаунта; пустой cloudpw снимает 2FA.
func (s *server) handleCloudPassword(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req struct {
		CurrentPassword string `json:"current_password"`
		CloudPW         string `json:"cloudpw"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		// readJSONLimited уже ответил (413 при переполнении); не пишем поверх
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if req.CloudPW != "" && len(req.CloudPW) < 4 {
		http.Error(w, "облачный пароль слишком короткий", http.StatusBadRequest)
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
	iters := acc.iters
	if iters <= 0 {
		iters = pbkdf2LegacyIterations
	}
	key := pbkdf2([]byte(req.CurrentPassword), acc.salt, iters, 32)
	if !hmac.Equal(key, acc.pkdfKey) {
		writeErr(w, http.StatusUnauthorized, "bad_password", "неверный текущий пароль")
		return
	}
	var salt, cKey []byte
	if req.CloudPW != "" {
		salt = make([]byte, 16)
		if _, err := rand.Read(salt); err != nil {
			http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
			return
		}
		cKey = pbkdf2([]byte(req.CloudPW), salt, pbkdf2Iterations, 32)
	}
	if err := s.store.SetAccountCloudKey(auth.login, salt, cKey); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"status": "changed"})
}

// AI1 (аналог Delete My Account у Telegram): полное удаление аккаунта
// с подтверждением паролем. Сносит ключи всех устройств, токены,
// пуш-токены, транзитную очередь и email-хэши; WS-соединения рвутся.
// Медиа-блобы остаются (владелец блоба серверу неизвестен — так
// устроен E2E-релей), они истекают по обычному 30-дневному TTL.
func (s *server) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет доступа", http.StatusUnauthorized)
		return
	}
	if !s.actionLimit.allow(auth.login, s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
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
	// проверка пароля — по стоимости KDF записи (как при входе/смене)
	iters := acc.iters
	if iters <= 0 {
		iters = pbkdf2LegacyIterations
	}
	key := pbkdf2([]byte(req.Password), acc.salt, iters, 32)
	if !hmac.Equal(key, acc.pkdfKey) {
		writeErr(w, http.StatusUnauthorized, "bad_password", "неверный пароль")
		return
	}
	if err := s.store.DeleteAccount(auth.login); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	// живые WS-соединения снесённого аккаунта — разрыв (токен уже мёртв)
	s.hub.disconnectUser(auth.login)
	writeJSON(w, map[string]string{"status": "deleted"})
}
