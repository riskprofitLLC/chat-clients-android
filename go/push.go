package main

// Пуш-канал: сервер хранит токены устройств и будит их при Enqueue.
// Контент в пуш не кладём принципиально — только wake-сигнал; реальный
// FCM/APNs-адаптер подключается деплоем через CHAT_PUSH_URL (webhook).
// Доставка асинхронная (S6): медленный webhook не блокирует postMessage.

import (
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

type Pusher interface {
	Notify(login, deviceID string)
}

type noopPusher struct{}

func (noopPusher) Notify(string, string) {}

// webhookPusher шлёт wake-сигнал внешнему пуш-шлюзу (FCM-прокси и т.п.)
type webhookPusher struct {
	url    string
	client *http.Client
	queue  chan pushJob
}

type pushJob struct {
	login    string
	deviceID string
}

// Timeout обязан быть настоящими секундами: голая 5 — это 5 наносекунд,
// любой вызов вебхука падал мгновенно (S6). Очередь ограничена: при
// переполнении wake-сигнал теряется — дрейн всё равно заберёт сообщения
func newWebhookPusher(url string) *webhookPusher {
	p := &webhookPusher{
		url:    url,
		client: &http.Client{Timeout: 5 * time.Second},
		queue:  make(chan pushJob, 256),
	}
	go p.worker()
	return p
}

// неблокирующая постановка: обработчик не ждёт сети вебхука
func (p *webhookPusher) Notify(login, deviceID string) {
	select {
	case p.queue <- pushJob{login, deviceID}:
	default:
		log.Printf("push: очередь wake-сигналов переполнена")
	}
}

func (p *webhookPusher) worker() {
	for job := range p.queue {
		body := fmt.Sprintf(`{"to_user":%q,"to_device":%q,"wake":true}`, job.login, job.deviceID)
		resp, err := p.client.Post(p.url, "application/json", strings.NewReader(body))
		if err != nil {
			log.Printf("push: %v", err)
			continue
		}
		resp.Body.Close()
	}
}

// POST /v1/push Bearer {"token","platform"} — регистрация пуш-токена устройства
func (s *server) handlePushToken(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	var req struct {
		Token    string `json:"token"`
		Platform string `json:"platform"`
	}
	// 2.1: тело читается с лимитом — прежде 200 КБ обрабатывались целиком
	if err := readJSONLimited(w, r, &req, jsonBodyLimit); err != nil {
		if !isMaxBytes(err) {
			http.Error(w, "некорректное тело", http.StatusBadRequest)
		}
		return
	}
	if req.Token == "" || len(req.Token) > 4096 {
		http.Error(w, "некорректное тело", http.StatusBadRequest)
		return
	}
	if req.Platform != "android" && req.Platform != "ios" {
		http.Error(w, "platform: android|ios", http.StatusBadRequest)
		return
	}
	if err := s.store.SavePushToken(auth.login, auth.deviceID, req.Platform, req.Token); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
}
