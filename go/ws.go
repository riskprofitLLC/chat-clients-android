package main

// WebSocket-канал нотификаций: сервер пишет кадр при каждом Enqueue адресату.
// Клиент по сигналу немедленно дрейнит свою очередь через REST — мгновенная
// доставка без ожидания поллинга. Контент по WS не ходит, только wake-сигнал.
//
// S5: запись — горутина-писатель на соединение с буферизованным каналом;
// «залипший» клиент не блокирует доставку остальным (запись больше не
// идёт под общим локом), SetWriteDeadline ограничивает зависание записи,
// переполнение буфера рвёт соединение — клиент переподключится и дрейнит.
// Чтение — SetReadDeadline, продлевается по pong; сервер пингует сам.

import (
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// web-клиент: браузер не может поставить заголовок Authorization на WebSocket,
// поэтому токен едет подпротоколом — он не попадает в логи доступа, в отличие
// от query-параметра. Заголовок остаётся основным путём (Android).
const wsAuthProtocol = "chat.bearer."

const (
	wsWakeMessage  = "newmsg"
	wsFrameQueue   = 16 // кадров в буфере на соединение
	wsWriteWait    = 10 * time.Second
	wsPongWait     = 60 * time.Second
	wsPingPeriod   = 50 * time.Second // меньше pongWait: до таймаута успеет два пинга
	wsMaxFrameSize = 256
	// потолок соединений на аккаунт: иначе один аккаунт открывал тысячи
	// коннектов и выедал горутины, память и файловые дескрипторы
	wsMaxConnsPerAccount = 8
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  wsMaxFrameSize,
	WriteBufferSize: wsMaxFrameSize,
	// CSWSH не страшен: авторизация — Bearer или токен в подпротоколе, куки
	// не используются, чужой сайт токена не знает. Оригин НЕ сверяем: за
	// реверс-прокси Host и Origin расходятся (changeOrigin у Vite/Caddy),
	// и законный клиент получал 403 на апгрейде.
	CheckOrigin: func(*http.Request) bool { return true },
}

// одно WS-соединение: писатель-горутина + буфер кадров
type wsClient struct {
	conn *websocket.Conn
	auth *deviceAuth
	out  chan []byte
	done chan struct{} // закрывается в remove — писатель выходит
}

type wsHub struct {
	mu    sync.Mutex
	conns map[*wsClient]struct{}
	// A6.9: индекс login → клиенты — notify/onlineCount не обходят все соединения
	byLogin map[string]map[*wsClient]struct{}
}

func newWsHub() *wsHub {
	return &wsHub{
		conns:   make(map[*wsClient]struct{}),
		byLogin: make(map[string]map[*wsClient]struct{}),
	}
}

func (h *wsHub) add(c *wsClient) {
	h.mu.Lock()
	h.conns[c] = struct{}{}
	set := h.byLogin[c.auth.login]
	if set == nil {
		set = make(map[*wsClient]struct{})
		h.byLogin[c.auth.login] = set
	}
	set[c] = struct{}{}
	h.mu.Unlock()
}

// единственный закрыватель done: закрытие идиоматично, повторный вызов — no-op
func (h *wsHub) remove(c *wsClient) {
	h.mu.Lock()
	_, alive := h.conns[c]
	delete(h.conns, c)
	if set := h.byLogin[c.auth.login]; set != nil {
		delete(set, c)
		if len(set) == 0 {
			delete(h.byLogin, c.auth.login)
		}
	}
	h.mu.Unlock()
	if alive {
		close(c.done)
		c.conn.Close()
	}
}

// deliver: кадр в буфер писателя; переполнение или мёртвый писатель —
// соединение закрывается (клиент переподключится и заберёт очередь дрейном)
func (h *wsHub) deliver(c *wsClient, frame []byte) {
	select {
	case c.out <- frame:
	default:
		h.remove(c)
	}
}

// notify будит все открытые коннекты устройства получателя
func (h *wsHub) notify(login, deviceID string) {
	h.mu.Lock()
	targets := make([]*wsClient, 0, 2)
	for c := range h.byLogin[login] {
		if c.auth.deviceID == deviceID {
			targets = append(targets, c)
		}
	}
	h.mu.Unlock()
	for _, c := range targets {
		h.deliver(c, []byte(wsWakeMessage))
	}
}

// notifyAll шлёт кадр всем устройствам пользователя (без фильтра по device)
func (h *wsHub) notifyAll(login, message string) {
	h.mu.Lock()
	targets := make([]*wsClient, 0, len(h.byLogin[login]))
	for c := range h.byLogin[login] {
		targets = append(targets, c)
	}
	h.mu.Unlock()
	for _, c := range targets {
		h.deliver(c, []byte(message))
	}
}

// AI2 (аналог статуса «в сети»): живые WS-соединения пользователя
func (h *wsHub) onlineCount(login string) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.byLogin[login])
}

// AI1: рвём все WS-соединения пользователя (аккаунт снесён — токен мёртв)
func (h *wsHub) disconnectUser(login string) {
	h.mu.Lock()
	targets := make([]*wsClient, 0, len(h.byLogin[login]))
	for c := range h.byLogin[login] {
		targets = append(targets, c)
	}
	h.mu.Unlock()
	for _, c := range targets {
		h.remove(c)
	}
}

// wsAuth: Bearer-заголовок, иначе токен из подпротокола. Копия апгрейдера
// на запрос — выбранный подпротокол нужно подтвердить эхом, иначе браузер
// рвёт соединение.
func (s *server) wsAuth(r *http.Request, upgrader *websocket.Upgrader) (*deviceAuth, bool) {
	if auth, ok := s.byBearer(r); ok {
		return auth, true
	}
	for _, proto := range websocket.Subprotocols(r) {
		token, ok := strings.CutPrefix(proto, wsAuthProtocol)
		if !ok || token == "" {
			continue
		}
		auth, err := s.store.AuthByToken(token)
		if err != nil || auth == nil {
			return nil, false
		}
		upgrader.Subprotocols = []string{proto}
		return auth, true
	}
	return nil, false
}

// GET /v1/ws — Bearer; держит соединение, читает только для детекта закрытия
func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	upgrader := wsUpgrader
	auth, ok := s.wsAuth(r, &upgrader)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	if s.hub.onlineCount(auth.login) >= wsMaxConnsPerAccount {
		http.Error(w, "слишком много соединений", http.StatusTooManyRequests)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return // апгрейд уже ответил ошибкой
	}
	client := &wsClient{
		conn: conn,
		auth: auth,
		out:  make(chan []byte, wsFrameQueue),
		done: make(chan struct{}),
	}
	s.hub.add(client)
	defer func() {
		s.hub.remove(client)
		// AI2: последнее соединение пользователя закрылось — фиксируем
		// «был(а) в сети» для отвечающих на POST /v1/online
		if s.hub.onlineCount(auth.login) == 0 {
			_ = s.store.TouchLastSeen(auth.login, s.now())
		}
	}()
	go s.wsWriteLoop(client)
	s.wsReadLoop(client)
}

// читатель: дедлайн продлевается pong'ами; тишина дольше pongWait — обрыв
func (s *server) wsReadLoop(c *wsClient) {
	c.conn.SetReadLimit(wsMaxFrameSize) // от клиента ничего значимого не ждём
	c.conn.SetReadDeadline(time.Now().Add(wsPongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(wsPongWait))
	})
	for {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			return
		}
	}
}

// писатель соединения: кадры из канала + периодический ping (S5)
func (s *server) wsWriteLoop(c *wsClient) {
	ticker := time.NewTicker(wsPingPeriod)
	defer ticker.Stop()
	for {
		select {
		case frame := <-c.out:
			c.conn.SetWriteDeadline(time.Now().Add(wsWriteWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, frame); err != nil {
				log.Printf("ws: ошибка записи: %v", err)
				s.hub.remove(c)
				return
			}
		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(wsWriteWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				s.hub.remove(c)
				return
			}
		case <-c.done:
			return
		}
	}
}
