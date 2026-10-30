// Email-верификация при регистрации: 4-значный код на почту.
// Отправка через Resend API (CHAT_RESEND_KEY) или Gmail SMTP.
// Код в ответе и в логе — ТОЛЬКО в явном dev-режиме (CHAT_DEV_MODE=1);
// в проде код не утекает ни в HTTP-ответ, ни в лог.

package main

import (
	"bytes"
	"crypto/rand"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/smtp"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	emailCodeTTL      = 15 * time.Minute
	maxCodeAttempts   = 5  // S3: неудачных попыток до сгорания кода
	codePerDayPerMail = 20 // S3: кодов на один email в сутки
)

// явный dev-режим: код в ответе и в логе (локальный дев-релей и e2e-тесты)
func devMode() bool {
	v := os.Getenv("CHAT_DEV_MODE")
	return v == "1" || v == "true"
}

// email скрытый от логов: «a***@example.com»
func maskEmail(email string) string {
	at := strings.Index(email, "@")
	if at <= 0 {
		return "***"
	}
	return email[:1] + "***" + email[at:]
}

// cooldown + суточный лимит отправки кодов на один email (S3)
type emailGate struct {
	mu       sync.Mutex
	lastSent map[string]time.Time
	sent     map[string][]time.Time
}

func newEmailGate() *emailGate {
	return &emailGate{lastSent: make(map[string]time.Time), sent: make(map[string][]time.Time)}
}

// allow=false, если код на этот email уже улетел недавно или
// суточный потолок исчерпан; при allow фиксируется отправка
func (g *emailGate) allow(email string, now time.Time, cooldown time.Duration) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if now.Sub(g.lastSent[email]) < cooldown {
		return false
	}
	// свежие отправки за сутки
	dayStart := now.Add(-24 * time.Hour)
	fresh := g.sent[email][:0]
	for _, t := range g.sent[email] {
		if t.After(dayStart) {
			fresh = append(fresh, t)
		}
	}
	if len(fresh) >= codePerDayPerMail {
		g.sent[email] = fresh
		return false
	}
	g.sent[email] = append(fresh, now)
	g.lastSent[email] = now
	return true
}

// периодическая чистка: email без активности за сутки не копятся
func (g *emailGate) cleanup(now time.Time) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	dayStart := now.Add(-24 * time.Hour)
	removed := 0
	for email, times := range g.sent {
		fresh := false
		for _, t := range times {
			if t.After(dayStart) {
				fresh = true
				break
			}
		}
		if !fresh {
			delete(g.sent, email)
			delete(g.lastSent, email)
			removed++
		}
	}
	return removed
}

// POST /v1/email-code {"email": "..."} → {"status": "sent"}
func (s *server) handleEmailCode(w http.ResponseWriter, r *http.Request) {
	if !s.emailCodeLimit.allow(s.clientIP(r), s.now()) {
		http.Error(w, "слишком много попыток, попробуйте позже", http.StatusTooManyRequests)
		return
	}

	var req struct {
		Email string `json:"email"`
	}
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	req.Email = trimLower(req.Email)
	if !emailRe.MatchString(req.Email) {
		http.Error(w, "укажите корректный email", http.StatusBadRequest)
		return
	}

	// пауза между кодами и суточный потолок на один email (S3):
	// спам кнопкой «выслать код» не прожигает лимиты соседей
	if !s.codes.allow(req.Email, s.now(), s.emailCooldown) {
		http.Error(w, "код уже отправлен, попробуйте позже", http.StatusTooManyRequests)
		return
	}

	// генерируем 4-значный код
	code, err := generateCode4()
	if err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}

	// сохраняем с TTL; новый код гасит прежние (S3, внутри стора)
	if err := s.store.SaveEmailCode(req.Email, code, s.now().Add(emailCodeTTL)); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}

	// отправляем (или логируем в dev-режиме). 2.3: сбой отправки в не-dev
	// отдаётся клиенту как 503 — «sent» при недоставленном коде лгал
	if err := s.sendEmailBounded(req.Email, code); err != nil {
		log.Printf("email: ошибка отправки на %s: %v", maskEmail(req.Email), err)
		if !devMode() {
			http.Error(w, "почтовый сервис недоступен, попробуйте позже", http.StatusServiceUnavailable)
			return
		}
	}

	// код в ответе — только в явном dev-режиме (S3)
	if devMode() {
		writeJSON(w, map[string]string{"status": "sent", "code": code})
		return
	}
	writeJSON(w, map[string]string{"status": "sent"})
}

// проверяет и потребляет код
func (s *server) verifyEmailCode(email, code string) bool {
	return s.store.ConsumeEmailCode(trimLower(email), code, s.now())
}

// 4 случайные цифры
func generateCode4() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(10000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%04d", n.Int64()), nil
}

func trimLower(s string) string {
	return toLower(trimSpace(s))
}

func toLower(s string) string {
	b := []byte(s)
	for i := range b {
		if b[i] >= 'A' && b[i] <= 'Z' {
			b[i] += 32
		}
	}
	return string(b)
}

func trimSpace(s string) string {
	start, end := 0, len(s)
	for start < end && (s[start] == ' ' || s[start] == '\t') {
		start++
	}
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t') {
		end--
	}
	return s[start:end]
}

// отправка: Resend API (HTTP) или Gmail SMTP (587)
func (s *server) sendEmail(to, code string) error {
	// код в лог — только в dev-режиме; в проде адрес маскирован (S3)
	if devMode() {
		log.Printf("EMAIL CODE для %s: %s (dev-режим)", to, code)
	} else {
		log.Printf("email: код отправлен на %s", maskEmail(to))
	}
	subject := "Код подтверждения: " + code
	text := "Ваш код для регистрации в Чате: " + code + "\nКод действителен 15 минут."

	// вариант 1: Resend API (CHAT_RESEND_KEY)
	if apiKey := os.Getenv("CHAT_RESEND_KEY"); apiKey != "" {
		return sendViaResend(apiKey, to, subject, text)
	}

	// вариант 2: Gmail SMTP (CHAT_SMTP_USER + CHAT_SMTP_PASS)
	if user := os.Getenv("CHAT_SMTP_USER"); user != "" {
		pass := os.Getenv("CHAT_SMTP_PASS")
		return sendViaGmailSMTP(user, pass, to, subject, text)
	}

	// ничего не настроено: код не опубликован, ошибка — в вызавший код
	return fmt.Errorf("email-сервис не настроен (CHAT_RESEND_KEY/CHAT_SMTP_USER)")
}

// 2.3: отправка с потолком 10 с — DefaultClient без таймаута подвешивал
// обработчик на неответном сервисе
var emailHTTPClient = &http.Client{Timeout: 10 * time.Second}

// Resend API — простой HTTP POST
func sendViaResend(apiKey, to, subject, text string) error {
	body := map[string]any{
		"from":    "Чат <onboarding@resend.dev>",
		"to":      []string{to},
		"subject": subject,
		"text":    text,
	}
	jsonBody, _ := json.Marshal(body)
	req, err := http.NewRequest("POST", "https://api.resend.com/emails",
		bytes.NewReader(jsonBody))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+apiKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := emailHTTPClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("resend: %d", resp.StatusCode)
	}
	return nil
}

// Gmail SMTP через порт 587 (STARTTLS); таймаут — на Dial (SendMail его не принимает)
func sendViaGmailSMTP(user, pass, to, subject, text string) error {
	from := user
	msg := "From: " + from + "\r\n" +
		"To: " + to + "\r\n" +
		"Subject: " + subject + "\r\n" +
		"Content-Type: text/plain; charset=UTF-8\r\n" +
		"\r\n" +
		text

	auth := smtp.PlainAuth("", from, pass, "smtp.gmail.com")
	// 2.3: коннект с дедлайном 10 с — smtp.SendMail таймаута не умеет
	conn, err := net.DialTimeout("tcp", "smtp.gmail.com:587", 10*time.Second)
	if err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	defer conn.Close()
	c, err := smtp.NewClient(conn, "smtp.gmail.com")
	if err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	defer c.Close()
	// A6.2: без STARTTLS PlainAuth отклоняется («unencrypted connection»)
	if err := c.StartTLS(&tls.Config{ServerName: "smtp.gmail.com"}); err != nil {
		return fmt.Errorf("gmail smtp starttls: %v", err)
	}
	if err := c.Auth(auth); err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	if err := c.Mail(from); err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	if err := c.Rcpt(to); err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	w, err := c.Data()
	if err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	if _, err := w.Write([]byte(msg)); err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	if err := w.Close(); err != nil {
		return fmt.Errorf("gmail smtp: %v", err)
	}
	return c.Quit()
}

// 2.3: отправка с потолком ожидания — медленный/умерший сервис не тянет
// обработчик дольше 10 секунд (таймауты — внутри клиентов; здесь — общая планка)
func (s *server) sendEmailBounded(to, code string) error {
	done := make(chan error, 1)
	go func() { done <- s.sendEmail(to, code) }()
	select {
	case err := <-done:
		return err
	case <-time.After(10 * time.Second):
		return errors.New("email: таймаут отправки 10 с")
	}
}
