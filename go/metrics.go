package main

import (
	"fmt"
	"strings"
	"sync"
)

// Счётчики событий; отдаются в текстовом формате Prometheus.
// ponytail: средняя латентность без гистограмм; p95 — при реальной нагрузке.
type metrics struct {
	mu           sync.Mutex
	sent         int64
	delivered    int64
	latencySumMs float64
	// AJ: снесено по порогу простоя
	inactiveSweep int64
	// GC: звонковый пайп
	callSessions   int64 // созданных сессий /v1/calls
	callSides      int64 // подключений к пайпу
	callRelayBytes int64 // переслано байт DTLS/SRTP
	callRateHits   int64 // обрывов по темпу
	// GH9: жалобы пользователей
	reports int64
}

func (m *metrics) messageSent() {
	m.mu.Lock()
	m.sent++
	m.mu.Unlock()
}

// AJ: аккаунт снесён по порогу простоя
func (m *metrics) inactiveDeleted() {
	m.mu.Lock()
	m.inactiveSweep++
	m.mu.Unlock()
}

func (m *metrics) callSessionCreated() {
	m.mu.Lock()
	m.callSessions++
	m.mu.Unlock()
}

func (m *metrics) callRateLimited() {
	m.mu.Lock()
	m.callRateHits++
	m.mu.Unlock()
}

func (m *metrics) callSideJoined() {
	m.mu.Lock()
	m.callSides++
	m.mu.Unlock()
}

func (m *metrics) callRelayed(n int) {
	m.mu.Lock()
	m.callRelayBytes += int64(n)
	m.mu.Unlock()
}

func (m *metrics) reportFiled() {
	m.mu.Lock()
	m.reports++
	m.mu.Unlock()
}

func (m *metrics) messageDelivered(ageMs float64) {
	m.mu.Lock()
	m.delivered++
	m.latencySumMs += ageMs
	m.mu.Unlock()
}

// снимок счётчиков под одним локом (для админки и render)
type counters struct {
	sent, delivered                       int64
	latencySumMs                          float64
	inactiveSweep                         int64
	callSessions, callSides               int64
	callRelayBytes, callRateHits, reports int64
}

func (m *metrics) counters() counters {
	m.mu.Lock()
	defer m.mu.Unlock()
	return counters{
		sent: m.sent, delivered: m.delivered, latencySumMs: m.latencySumMs,
		inactiveSweep: m.inactiveSweep, callSessions: m.callSessions,
		callSides: m.callSides, callRelayBytes: m.callRelayBytes,
		callRateHits: m.callRateHits, reports: m.reports,
	}
}

func (m *metrics) render() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return []string{
		fmt.Sprintf("chat_messages_sent_total %d", m.sent),
		fmt.Sprintf("chat_messages_delivered_total %d", m.delivered),
		fmt.Sprintf("chat_delivery_latency_ms_sum %g", m.latencySumMs),
		fmt.Sprintf("chat_delivery_latency_ms_count %d", m.delivered),
		fmt.Sprintf("chat_accounts_inactive_deleted_total %d", m.inactiveSweep),
		fmt.Sprintf("chat_call_sessions_total %d", m.callSessions),
		fmt.Sprintf("chat_call_relay_bytes_total %d", m.callRelayBytes),
		fmt.Sprintf("chat_call_relay_rate_limited_total %d", m.callRateHits),
		fmt.Sprintf("chat_reports_total %d", m.reports),
	}
}

func renderMetrics(s *server) string {
	var b strings.Builder
	b.WriteString("# Метрики релея; очередь и аккаунты — из хранилища\n")
	count, err := s.store.AccountCount()
	if err == nil {
		fmt.Fprintf(&b, "chat_accounts_total %d\n", count)
	}
	for _, line := range s.metrics.render() {
		b.WriteString(line)
		b.WriteByte('\n')
	}
	if depths, err := s.store.QueueDepths(); err == nil {
		// S6: логины — персональные данные, в метрики не попадают;
		// агрегат по сервису достаточен для наблюдения за транзитом
		var total int
		for _, depth := range depths {
			total += depth
		}
		fmt.Fprintf(&b, "chat_queue_depth_total %d\n", total)
	}
	return b.String()
}
