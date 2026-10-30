package main

// Хранилище медиа-блобов: сервер видит только зашифрованные байты.
// Файловая реализация — для песочницы/дева; прод — S3/R2-адаптер при деплое
// (ObjectStore-интерфейс). Идентификатор — 32 случайных байта (capability).
// ponytail: без авто-очистки старых блобов; прод — lifecycle-правила бакета.

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
)

const maxMediaSize = 10 << 20 // 10 МБ, потолок медиа-блоба

type ObjectStore interface {
	Put(id string, data []byte) error
	Get(id string) ([]byte, error)
}

type fsStore struct{ dir string }

func newFsStore(dir string) (*fsStore, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &fsStore{dir: dir}, nil
}

func (f *fsStore) path(id string) string {
	// идентификаторы строго hex-валидируются выше, но на всякий случай
	return filepath.Join(f.dir, filepath.Base(id))
}

func (f *fsStore) Put(id string, data []byte) error {
	return os.WriteFile(f.path(id), data, 0o600)
}

func (f *fsStore) Get(id string) ([]byte, error) {
	data, err := os.ReadFile(f.path(id))
	if errors.Is(err, os.ErrNotExist) {
		return nil, errNotFound
	}
	return data, err
}

var mediaIDRe = func(id string) bool {
	if len(id) != 64 {
		return false
	}
	_, err := hex.DecodeString(id)
	return err == nil
}

// POST /v1/media Bearer (тело — зашифрованный блоб) → {"media_id"}.
// 2.1: rate на аккаунт + суточная байтовая квота — без них заполнение
// диска перебором загрузок было бесплатным
func (s *server) handleMediaPut(w http.ResponseWriter, r *http.Request) {
	auth, ok := s.byBearer(r)
	if !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, maxMediaSize+1))
	if err != nil {
		http.Error(w, "некорректное тело", http.StatusBadRequest)
		return
	}
	if len(data) == 0 || len(data) > maxMediaSize {
		http.Error(w, "блоб пуст или велик", http.StatusRequestEntityTooLarge)
		return
	}
	if !s.mediaRate.allow(auth.login, s.now()) ||
		!s.mediaBytes.allow(auth.login, int64(len(data)), s.now()) {
		http.Error(w, "слишком много загрузок, попробуйте позже", http.StatusRequestEntityTooLarge)
		return
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		http.Error(w, "внутренняя ошибка", http.StatusInternalServerError)
		return
	}
	id := hex.EncodeToString(raw)
	if err := s.media.Put(id, data); err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"media_id": id})
}

// GET /v1/media/{id} Bearer → байты (application/octet-stream)
func (s *server) handleMediaGet(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.byBearer(r); !ok {
		http.Error(w, "нет авторизации", http.StatusUnauthorized)
		return
	}
	id := r.PathValue("id")
	if !mediaIDRe(id) {
		http.Error(w, "некорректный идентификатор", http.StatusBadRequest)
		return
	}
	data, err := s.media.Get(id)
	if errors.Is(err, errNotFound) {
		http.Error(w, "не найдено", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, "хранилище недоступно", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	if _, err := w.Write(data); err != nil {
		log.Printf("media: ошибка записи: %v", err)
	}
}
