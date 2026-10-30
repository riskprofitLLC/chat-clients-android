package main

// Очистка медиа-транзита: блобы старше maxMediaAge стираются.
// Модель: расшифрованная копия живёт на устройстве получателя; сервер — транзит.

import (
	"log"
	"os"
	"time"
)

const maxMediaAge = 30 * 24 * time.Hour // как у транзита сообщений

// удаляет файлы старше now-maxMediaAge; возвращает число удалённых
func (f *fsStore) sweep(now time.Time) int {
	entries, err := os.ReadDir(f.dir)
	if err != nil {
		return 0
	}
	removed := 0
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil || info.IsDir() {
			continue
		}
		if now.Sub(info.ModTime()) > maxMediaAge {
			if os.Remove(f.path(entry.Name())) == nil {
				removed++
			}
		}
	}
	return removed
}

// периодическая очистка; ошибка тикера не останавливает сервер.
// Кроме медиа чистим и лимитеры (S1), и просроченный транзит очереди (S4)
func startMediaSweeper(s *server, stop <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				if fs, ok := s.media.(*fsStore); ok {
					if removed := fs.sweep(time.Now()); removed > 0 {
						log.Printf("media: удалено устаревших блобов: %d", removed)
					}
				}
				if removed, err := s.store.SweepExpired(time.Now()); err == nil && removed > 0 {
					log.Printf("queue: удалено просроченных сообщений: %d", removed)
				}
				if dropped := s.calls.sweep(time.Now()); dropped > 0 {
					log.Printf("calls: TTL чистка: %d сессий", dropped)
				}
				s.sweepLimiters(time.Now())
			}
		}
	}()
}

// чистка in-memory лимитеров: ключи без свежих попаданий удаляются
func (s *server) sweepLimiters(now time.Time) {
	for _, l := range []*ipLimiter{
		s.linkLimit, s.discoverLimit, s.emailCodeLimit, s.loginLimit,
		s.loginNameLimit, s.sendLimit, s.registerLimit, s.keysLimit,
		s.mediaRate, // 2.1: actionLimit прежде не чистился — map рос вечно
		s.actionLimit,
		s.loginIPLimit,      // A6.1: прежде не чистились — ключи росли вечно (DoS)
		s.onlineTargetLimit,
		s.discoverAccountLimit,
	} {
		l.cleanup(now)
	}
	s.codes.cleanup(now)
	s.mediaBytes.cleanup(now) // 2.1: байтовая квота медиа
}
