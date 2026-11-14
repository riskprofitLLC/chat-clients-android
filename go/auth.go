package main

// Аккаунты: логин + пароль. PBKDF2 (RFC 8018) — композиция над stdlib
// HMAC-SHA256, проверяется векторами RFC 7914. Одно устройство на аккаунт
// (мультидевайс — Этап 3). Токен — 32 случайных байта, ротируется при логине.

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"strings"
	"time"
)

const (
	pbkdf2Iterations       = 600_000 // S6: OWASP для PBKDF2-HMAC-SHA256
	pbkdf2LegacyIterations = 40_000  // S6: старые записи, перехэш при входе
	loginAttemptsPerHour   = 20      // S2: попытки входа с одного IP и на один логин
)

// фиктивная соль: одинаковая стоимость ответа «нет аккаунта» и «неверный пароль»
var loginDummySalt = []byte("chat-login-dummy")

var (
	loginRe  = regexp.MustCompile(`^[a-z0-9_-]{3,32}$`)
	deviceRe = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)
	emailRe  = regexp.MustCompile(`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`)
)

type accountRequest struct {
	Login    string `json:"login"` // авто-генерируется из email
	Password string `json:"password"`
	DeviceID string `json:"device_id"`
	PubSPKI  string `json:"pub_spki"`
	Email    string `json:"email"` // обязателен
	Code     string `json:"code"`  // 4-значный код с почты
	// GH2: облачный пароль 2FA — обязателен при входе, если включён
	CloudPW string `json:"cloudpw"`
}

func (s *server) handleRegister(w http.ResponseWriter, r *http.Request) {
	// 2.1: перебор регистраций с одного IP ограничен — без лимита
	// молотить email-коды и enumerate логины было бесплатно
	if !s.registerLimit.allow(s.clientIP(r), s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	var req accountRequest
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	req.Email = trimLower(req.Email)
	if !emailRe.MatchString(req.Email) || len(req.Password) < 6 || !deviceRe.MatchString(req.DeviceID) {
		http.Error(w, "некорректные поля (email, пароль или устройство)", http.StatusBadRequest)
		return
	}
	if req.Code == "" {
		http.Error(w, "введите код с почты", http.StatusBadRequest)
		return
	}
	// все проверки полей — ДО потребления кода (S3): неудачная попытка
	// с кривым ключом не сжигает код и не тратит попытки перебора
	if _, err := decodePub(req.PubSPKI); err != nil {
		http.Error(w, "ключ некорректен", http.StatusBadRequest)
		return
	}
	// 2.3: порядок — сначала проверка кода БЕЗ списания (попытки считаются),
	// затем «email уже зарегистрирован»: перебор email больше не раскрывает
	// факт регистрации без подтверждения владения почтой
	if !s.store.CheckEmailCode(req.Email, req.Code, s.now()) {
		writeErr(w, http.StatusBadRequest, "bad_code", "неверный или истёкший код")
		return
	}
	existing, err := s.store.LoginByEmailHash(hashEmail(req.Email))
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if existing != "" {
		writeErr(w, http.StatusConflict, "email_taken", "email уже зарегистрирован")
		return
	}
	// логин = email до @, авто-генерация
	req.Login = loginFromEmail(req.Email)
	// пространство имён grp_ зарезервировано за групповыми чатами:
	// человек с логином grp_* путался бы с идентификатором группы
	if strings.HasPrefix(req.Login, "grp_") {
		http.Error(w, "логин зарезервирован", http.StatusBadRequest)
		return
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	token, err := randomToken()
	if err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	acc := &account{
		login:   req.Login,
		pkdfKey: pbkdf2([]byte(req.Password), salt, pbkdf2Iterations, 32),
		salt:    salt,
		iters:   pbkdf2Iterations,
	}
	// хэш почты занимается АТОМАРНО с аккаунтом: параллельная регистрация
	// с той же почтой получает честный 409, а не 500 и сироту-аккаунт
	emailHash := hashEmail(req.Email)
	conflict, err := s.store.CreateAccount(acc, req.DeviceID, req.PubSPKI, token, emailHash)
	if err != nil {
		if errors.Is(err, errEmailTaken) {
			writeErr(w, http.StatusConflict, "email_taken", "email уже зарегистрирован")
			return
		}
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if conflict {
		// логин занят → добавляем номер
		for i := 2; i < 100; i++ {
			candidate := fmt.Sprintf("%s%d", req.Login, i)
			if len(candidate) > 32 {
				candidate = candidate[:32]
			}
			if !loginRe.MatchString(candidate) {
				break
			}
			acc.login = candidate
			conflict, err = s.store.CreateAccount(acc, req.DeviceID, req.PubSPKI, token, emailHash)
			if err != nil {
				http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
				return
			}
			if !conflict {
				req.Login = candidate
				break
			}
		}
		if conflict {
			// код намеренно НЕ сожжён: все 98 логинов из префикса заняты —
			// пользователь повторит попытку с тем же кодом (прежде код
			// уходил в расход до подбора логина и второй попытки не было)
			writeErr(w, http.StatusConflict, "login_busy", "не удалось создать логин, попробуйте ещё раз")
			return
		}
	}
	// код — расходная операция, потребляем после создания аккаунта (S3)
	if !s.verifyEmailCode(req.Email, req.Code) {
		http.Error(w, "неверный или истёкший код", http.StatusBadRequest)
		return
	}
	s.statRegistered(req.Login)
	writeJSON(w, map[string]string{"token": token, "user_id": req.Login})
}

func (s *server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	// резолв идентификатора (S2): email → логин по хэшу; иначе — trimLower.
	// Прежний код проверял пароль по найденному аккаунту, а устройство
	// искал по СЫРОЙ строке — вход по email всегда кончался 401
	login := req.Login
	if strings.Contains(login, "@") {
		login = trimLower(login)
		found, err := s.store.LoginByEmailHash(hashEmail(login))
		if err != nil {
			http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
			return
		}
		if found != "" {
			login = found
		}
	} else {
		login = trimLower(login)
	}
	// A5: логин валидируем ДО лимитеров и PBKDF2 — мусорная строка до 64 КБ
	// не должна попадать в ключи лимитеров (рост памяти) и запускать KDF
	if !loginRe.MatchString(login) {
		http.Error(w, "неверный логин или пароль", http.StatusUnauthorized)
		return
	}
	// rate-limit попыток (2.3): по паре (IP, логин) — перебор пароля дорогим
	// не делаем; отдельный высокий порог по логину НЕ позволяет заблокировать
	// вход жертве с чужого IP (прежние 20/ч на голый логин били по любому).
	// A5: плюс общий порог по IP — защита от перебора многих логинов
	// с одного адреса (дорогой PBKDF2 на каждый)
	if !s.loginIPLimit.allow("ip:"+s.clientIP(r), s.now()) ||
		!s.loginLimit.allow("ip:"+s.clientIP(r)+"/"+login, s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}
	acc, err := s.store.AccountByLogin(login)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if acc == nil {
		// фиктивный PBKDF2: время ответа «нет аккаунта» = «неверный пароль» (S2)
		pbkdf2([]byte(req.Password), loginDummySalt, pbkdf2Iterations, 32)
		http.Error(w, "неверный логин или пароль", http.StatusUnauthorized)
		return
	}
	// стоимость KDF — из записи: старые аккаунты проверяются со старым
	// числом итераций, иначе после поднятия потолка вход ломался бы
	iters := acc.iters
	if iters <= 0 {
		iters = pbkdf2LegacyIterations
	}
	key := pbkdf2([]byte(req.Password), acc.salt, iters, 32)
	if !hmac.Equal(key, acc.pkdfKey) {
		// A6.8: по голому логину считаем ТОЛЬКО неудачные попытки —
		// атакующий с чужого IP не должен блокировать вход жертве
		if !s.loginNameLimit.allow("login:"+login, s.now()) {
			http.Error(w, "слишком много неудачных попыток, попробуйте позже", http.StatusTooManyRequests)
			return
		}
		http.Error(w, "неверный логин или пароль", http.StatusUnauthorized)
		return
	}
	// GH2: облачный пароль включён — без него вход не проходит (отдельный
	// код ошибки, чтобы клиент понял, что спросить 2FA, а не «неверный пароль»)
	if len(acc.cloudKey) > 0 {
		// пустой пароль отсекаем ДО KDF: 600k итераций на заведомо неверный
		// ввод — бесплатная нагрузка на сервер
		if len(req.CloudPW) == 0 {
			writeErr(w, http.StatusUnauthorized, "cloudpw_required", "требуется облачный пароль")
			return
		}
		cKey := pbkdf2([]byte(req.CloudPW), acc.cloudSalt, pbkdf2Iterations, 32)
		if !hmac.Equal(cKey, acc.cloudKey) {
			writeErr(w, http.StatusUnauthorized, "cloudpw_required", "требуется облачный пароль")
			return
		}
	}
	// перехэш до текущей стоимости (S6): слабый хэш не должен жить
	// дольше первого успешного входа
	if iters < pbkdf2Iterations {
		freshSalt := make([]byte, 16)
		if _, err := rand.Read(freshSalt); err == nil {
			freshKey := pbkdf2([]byte(req.Password), freshSalt, pbkdf2Iterations, 32)
			if err := s.store.UpdateAccountHash(login, freshSalt, freshKey, pbkdf2Iterations); err != nil {
				log.Printf("rehash %s не сохранён: %v", login, err)
			}
		}
	}
	// дальше — только резолвнутый логин (S2), не сырой req.Login
	exists, err := s.store.DeviceExists(login, req.DeviceID)
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	if !exists {
		// устройство не привязано (мультидевайс — только через линковку)
		http.Error(w, "устройство не привязано к аккаунту", http.StatusUnauthorized)
		return
	}
	// ротация: старый токен этого устройства умирает
	token, err := randomToken()
	if err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	if err := s.store.RotateToken(login, req.DeviceID, token); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"token": token, "user_id": acc.login})
}

func (s *server) byBearer(r *http.Request) (*deviceAuth, bool) {
	header := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return nil, false
	}
	auth, err := s.store.AuthByToken(strings.TrimPrefix(header, prefix))
	if err != nil || auth == nil {
		return nil, false
	}
	return auth, true
}

func randomToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

// базовая валидация публичного ключа на границе доверия
func decodePub(pubB64 string) ([]byte, error) {
	pub, err := base64.StdEncoding.DecodeString(pubB64)
	if err != nil || len(pub) == 0 || len(pub) > 4096 {
		return nil, errors.New("некорректный ключ")
	}
	return pub, nil
}

// PBKDF2 (RFC 8018) над stdlib HMAC-SHA256; векторы — в тестах (RFC 7914)
func pbkdf2(password, salt []byte, iter, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	hashLen := prf.Size()
	blocks := (keyLen + hashLen - 1) / hashLen
	dk := make([]byte, 0, blocks*hashLen)
	counter := make([]byte, 4)
	for i := 1; i <= blocks; i++ {
		prf.Reset()
		prf.Write(salt)
		binary.BigEndian.PutUint32(counter, uint32(i))
		prf.Write(counter)
		u := prf.Sum(nil)
		t := make([]byte, len(u))
		copy(t, u)
		for n := 1; n < iter; n++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		dk = append(dk, t...)
	}
	return dk[:keyLen]
}

// токены устройств хранятся только хэшем SHA-256 (S6): сырые значения
// в БД — готовый источник утечки при дампе/бэкапе.
// 1.2: префикс версии h1: — хэш из дампа не проходит поиск по хэшу
// (прежде легаси-ветка принимала сам хэш как Bearer); версия позволит
// сменить алгоритм без неоднозначности строк в БД
func hashToken(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return "h1:" + hex.EncodeToString(sum[:])
}

// генерирует логин из email: alice@gmail.com → alice, alice2, alice3...
func loginFromEmail(email string) string {
	prefix := email[:strings.Index(email, "@")]
	// очищаем от недопустимых символов
	var clean []byte
	for _, c := range strings.ToLower(prefix) {
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' {
			clean = append(clean, byte(c))
		}
	}
	login := string(clean)
	if len(login) < 3 {
		login = "user" + fmt.Sprintf("%d", time.Now().UnixNano()%10000)
	}
	if len(login) > 32 {
		login = login[:32]
	}
	// префикс grp_ — пространство имён групповых чатов: сгенерированный
	// логин обязан его избегать (клиент зеркально отвергает grp_-отправителей)
	if strings.HasPrefix(login, "grp_") {
		login = "u" + login
	}
	return login
}
