package main

// Ограничения на границе доверия: лимит тел запросов и rate-limit линковки.
// ponytail: лимитеры in-memory, на инстанс; многоинстансовый прод — общий стор.

import (
	"errors"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	jsonBodyLimit    = 64 << 10 // маленькие JSON-эндпоинты: 64 КБ
	messageBodyLimit = 12 << 20 // сообщения: один блоб до 10 МБ + запас
	batchBodyLimit   = 64 << 20 // 2.2: batch — до 128 получателей в одном теле
	linkInitPerHour  = 10       // инициаций линковки с одного IP в час
	registerPerHour  = 10       // 2.1: регистраций с одного IP в час
	keysPerHour      = 300      // 2.1: чтений справочника ключей с IP в час
	mediaPutsPerMin  = 30       // 2.1: загрузок медиа на аккаунт в минуту

)

// чтение JSON с лимитом тела; превышение — 413
func readJSONLimited(w http.ResponseWriter, r *http.Request, v any, limit int64) error {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	if err := decodeJSONBody(r, v); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			http.Error(w, "тело слишком велико", http.StatusRequestEntityTooLarge)
		}
		return err
	}
	return nil
}

// скользящий лимитер по ключу (IP, логин); окно настраивается —
// час для анти-спама, секунда для действий реального времени
type ipLimiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	limit  int
	window time.Duration
}

func newIpLimiter(limit int, window time.Duration) *ipLimiter {
	return &ipLimiter{hits: make(map[string][]time.Time), limit: limit, window: window}
}

// allow=true, если ключ укладывается в окно
func (l *ipLimiter) allow(ip string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	windowStart := now.Add(-l.window)
	fresh := l.hits[ip][:0]
	for _, t := range l.hits[ip] {
		if t.After(windowStart) {
			fresh = append(fresh, t)
		}
	}
	if len(fresh) >= l.limit {
		l.hits[ip] = fresh
		return false
	}
	l.hits[ip] = append(fresh, now)
	return true
}

// периодическая чистка: ключи без свежих попаданий удаляются,
// иначе hits растёт вечно на потоке одноразовых IP
func (l *ipLimiter) cleanup(now time.Time) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	windowStart := now.Add(-l.window)
	removed := 0
	for ip, hits := range l.hits {
		fresh := false
		for _, t := range hits {
			if t.After(windowStart) {
				fresh = true
				break
			}
		}
		if !fresh {
			delete(l.hits, ip)
			removed++
		}
	}
	return removed
}

// 2.1: байтовая квота на ключ за скользящее окно (медиа на аккаунт в сутки).
// Отдельный тип: считает объём, а не число попаданий
type byteLimiter struct {
	mu     sync.Mutex
	puts   map[string][]bytePut
	window time.Duration
	ceil   int64
}

type bytePut struct {
	at   time.Time
	size int64
}

func newByteLimiter(ceil int64, window time.Duration) *byteLimiter {
	return &byteLimiter{puts: make(map[string][]bytePut), window: window, ceil: ceil}
}

// allow=true, если сумма загрузок за окно + size укладывается в потолок
func (l *byteLimiter) allow(key string, size int64, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	windowStart := now.Add(-l.window)
	old := l.puts[key]
	fresh := old[:0]
	var total int64
	for _, put := range old {
		if put.at.After(windowStart) {
			fresh = append(fresh, put)
			total += put.size
		}
	}
	if total+size > l.ceil {
		l.puts[key] = fresh
		return false
	}
	l.puts[key] = append(fresh, bytePut{now, size})
	return true
}

func (l *byteLimiter) cleanup(now time.Time) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	windowStart := now.Add(-l.window)
	removed := 0
	for key, puts := range l.puts {
		fresh := false
		for _, put := range puts {
			if put.at.After(windowStart) {
				fresh = true
				break
			}
		}
		if !fresh {
			delete(l.puts, key)
			removed++
		}
	}
	return removed
}

func remoteIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// хост из «ip:port» или сырой строки, если порта нет
func hostOnly(s string) string {
	if host, _, err := net.SplitHostPort(s); err == nil {
		return host
	}
	return s
}

// доверенные прокси: loopback всегда + список из CHAT_TRUSTED_PROXIES
// (IP или CIDR через запятую). Только от них читаем X-Forwarded-For.
func (s *server) isTrustedProxy(ip string) bool {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return false
	}
	if parsed.IsLoopback() {
		return true
	}
	for _, cidr := range s.trustedProxies {
		if cidr.Contains(parsed) {
			return true
		}
	}
	return false
}

// реальный IP клиента. За Caddy RemoteAddr всегда 127.0.0.1 — без этой
// логики все лимиты общие на весь сервис. XFF/Real-IP учитываем ТОЛЬКО
// от доверенного прокси; из цепочки берём крайний справа недоверенный
// адрес (клиент не может его подделать: свои адреса прокси вырезает).
func (s *server) clientIP(r *http.Request) string {
	remote := remoteIP(r)
	if !s.isTrustedProxy(remote) {
		return remote
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		for i := len(parts) - 1; i >= 0; i-- {
			ip := hostOnly(strings.TrimSpace(parts[i]))
			if ip == "" {
				continue
			}
			if !s.isTrustedProxy(ip) {
				return ip
			}
		}
		// вся цепочка доверенная — берём крайний слева (самый дальний)
		for _, part := range parts {
			if ip := hostOnly(strings.TrimSpace(part)); ip != "" {
				return ip
			}
		}
	}
	// X-Real-IP — только если это настоящий IP: иначе клиент подсунул бы
	// произвольную строку в ключи лимитеров (рост памяти/обход лимитов)
	if real := strings.TrimSpace(r.Header.Get("X-Real-IP")); real != "" {
		if ip := hostOnly(real); net.ParseIP(ip) != nil {
			return ip
		}
	}
	return remote
}

// разбор CHAT_TRUSTED_PROXIES: «10.0.0.0/8, 192.168.1.5» → []*net.IPNet
func parseTrustedProxies(spec string) []*net.IPNet {
	var nets []*net.IPNet
	for _, item := range strings.Split(spec, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if !strings.Contains(item, "/") {
			// одиночный IP — превращаем в /32 или /128
			if ip := net.ParseIP(item); ip != nil {
				bits := 32
				if ip.To4() == nil {
					bits = 128
				}
				nets = append(nets, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
			}
			continue
		}
		if _, ipNet, err := net.ParseCIDR(item); err == nil {
			nets = append(nets, ipNet)
		}
	}
	return nets
}
