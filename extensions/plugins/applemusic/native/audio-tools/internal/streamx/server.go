// Package streamx exposes the downloader as bounded, progressively cached HLS.
// Apple account material remains in wrapper-lite, outside the Android plugin.
package streamx

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	fairplay "amdl/internal/fairplay-rip"
	"amdl/internal/wrapper"
	"github.com/itouakirai/mp4ff/mp4"
)

type Producer func(context.Context, string, int, func(int, []byte, float64) error) error

// Server caps concurrent/cache sessions and each cached recording's disk use.
type Server struct {
	Token, Cache string
	Produce      Producer
	Log          func(string)
	mu           sync.Mutex
	sessions     map[string]*session
}
type session struct {
	mu        sync.Mutex
	dir       string
	durations []float64
	ready     chan struct{}
	notify    chan struct{}
	files     map[int]bool
	err       error
	bytes     int64
	last      time.Time
	cancel    context.CancelFunc
	done      chan struct{}
}

var songID = regexp.MustCompile(`^[0-9]{1,20}$`)

func New(lite, token, cache string) *Server {
	s := &Server{Token: token, Cache: cache, sessions: make(map[string]*session)}
	client := &http.Client{Timeout: 90 * time.Second}
	s.Produce = func(ctx context.Context, id string, rate int, emit func(int, []byte, float64) error) error {
		master, err := wrapper.NewWithHTTPClient(lite, contextHTTP(ctx, client)).M3U8(id)
		if err != nil {
			return fmt.Errorf("wrapper unavailable")
		}
		playlist, err := fairplay.ALACPlaylist(ctx, client, master, rate)
		if err != nil {
			return err
		}
		return fairplay.StreamFragments(ctx, client, lite, id, playlist, func(index int, init *mp4.InitSegment, frag *mp4.Fragment, duration float64) error {
			var b bytes.Buffer
			if init != nil {
				if err := init.Encode(&b); err != nil {
					return err
				}
			}
			if frag != nil {
				if err := frag.Encode(&b); err != nil {
					return err
				}
			}
			return emit(index, b.Bytes(), duration)
		})
	}
	return s
}
func (s *Server) authorized(r *http.Request) bool {
	return len(s.Token) >= 24 && subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+s.Token)) == 1
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.URL.Path == "/v1/health" {
		if !s.authorized(r) {
			http.Error(w, "Unauthorized", 401)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"ok":true,"format":"alac","protocol":1}`)
		return
	}
	if r.URL.Path == "/v1/sessions" && r.Method == "POST" {
		if !s.authorized(r) {
			http.Error(w, "Unauthorized", 401)
			return
		}
		s.create(w, r)
		return
	}
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) != 4 || parts[0] != "v1" || parts[1] != "sessions" || len(parts[2]) != 48 || (r.Method != "GET" && r.Method != "HEAD") {
		http.NotFound(w, r)
		return
	}
	s.mu.Lock()
	job := s.sessions[parts[2]]
	s.mu.Unlock()
	if job == nil {
		http.Error(w, "Stream expired; select the track again", 410)
		return
	}
	job.mu.Lock()
	job.last = time.Now()
	job.mu.Unlock()
	ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
	defer cancel()
	if parts[3] == "index.m3u8" {
		select {
		case <-job.ready:
		case <-ctx.Done():
			http.Error(w, "ALAC preparation timed out", 504)
			return
		}
		job.mu.Lock()
		defer job.mu.Unlock()
		if job.err != nil {
			http.Error(w, "ALAC preparation failed", 502)
			return
		}
		target := 1
		for _, d := range job.durations {
			if int(math.Ceil(d)) > target {
				target = int(math.Ceil(d))
			}
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		fmt.Fprintf(w, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:%d\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-MAP:URI=\"init.mp4\"\n", target)
		for i, d := range job.durations {
			fmt.Fprintf(w, "#EXTINF:%.6f,\n%d.m4s\n", d, i)
		}
		fmt.Fprint(w, "#EXT-X-ENDLIST\n")
		return
	}
	index := -1
	if parts[3] != "init.mp4" {
		if !strings.HasSuffix(parts[3], ".m4s") {
			http.NotFound(w, r)
			return
		}
		var err error
		index, err = strconv.Atoi(strings.TrimSuffix(parts[3], ".m4s"))
		if err != nil || index < 0 || index > 100000 {
			http.NotFound(w, r)
			return
		}
	}
	for {
		job.mu.Lock()
		ready := job.files[index]
		failure := job.err
		notify := job.notify
		job.mu.Unlock()
		if ready {
			break
		}
		if failure != nil {
			http.Error(w, "ALAC fragment unavailable", 502)
			return
		}
		select {
		case <-notify:
		case <-ctx.Done():
			http.Error(w, "ALAC fragment timed out", 504)
			return
		}
	}
	w.Header().Set("Content-Type", "audio/mp4")
	http.ServeFile(w, r, filepath.Join(job.dir, fmt.Sprintf("%d.mp4", index)))
}
func (s *Server) create(w http.ResponseWriter, r *http.Request) {
	var input struct {
		ID      string `json:"id"`
		MaxRate int    `json:"max_sample_rate"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&input) != nil || !songID.MatchString(input.ID) {
		http.Error(w, "Expected catalogue song ID", 400)
		return
	}
	if input.MaxRate == 0 {
		input.MaxRate = 48000
	}
	if input.MaxRate != 48000 && input.MaxRate != 96000 && input.MaxRate != 192000 {
		http.Error(w, "Invalid sample-rate limit", 400)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.sessions) >= 2 {
		var oldestID string
		oldest := time.Now()
		for key, existing := range s.sessions {
			existing.mu.Lock()
			last := existing.last
			existing.mu.Unlock()
			if last.Before(oldest) {
				oldest = last
				oldestID = key
			}
		}
		if oldestID != "" {
			s.retireLocked(oldestID)
		}
	}
	if len(s.sessions) >= 2 {
		http.Error(w, "Two recordings are already cached; wait for expiry", 429)
		return
	}
	var raw [24]byte
	if _, err := rand.Read(raw[:]); err != nil {
		http.Error(w, "Session unavailable", 500)
		return
	}
	id := hex.EncodeToString(raw[:])
	dir, err := os.MkdirTemp(s.Cache, "alac-")
	if err != nil {
		http.Error(w, "Cache unavailable", 500)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	job := &session{dir: dir, ready: make(chan struct{}), notify: make(chan struct{}), files: make(map[int]bool), last: time.Now(), cancel: cancel, done: make(chan struct{})}
	s.sessions[id] = job
	go s.run(ctx, job, input.ID, input.MaxRate)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(201)
	json.NewEncoder(w).Encode(map[string]any{"path": "/v1/sessions/" + id + "/index.m3u8", "format": "alac", "protocol": 1})
}
func (s *Server) run(ctx context.Context, job *session, id string, rate int) {
	if s.Log != nil {
		s.Log("Connecting to wrapper; preparing ALAC fragments")
	}
	defer close(job.done)
	defer job.cancel()
	ready := false
	err := s.Produce(ctx, id, rate, func(index int, data []byte, duration float64) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		job.mu.Lock()
		defer job.mu.Unlock()
		if index == -2 {
			if len(job.durations) >= 100000 || duration <= 0 || math.IsNaN(duration) || math.IsInf(duration, 0) {
				return fmt.Errorf("invalid duration")
			}
			job.durations = append(job.durations, duration)
			return nil
		}
		if job.bytes+int64(len(data)) > 512*1024*1024 {
			return fmt.Errorf("cache limit exceeded")
		}
		if err := os.WriteFile(filepath.Join(job.dir, fmt.Sprintf("%d.mp4", index)), data, 0600); err != nil {
			return err
		}
		job.bytes += int64(len(data))
		job.files[index] = true
		if s.Log != nil && index >= 0 {
			s.Log(fmt.Sprintf("ALAC fragment %d/%d ready", index+1, len(job.durations)))
		}
		close(job.notify)
		job.notify = make(chan struct{})
		if index == -1 {
			if len(job.durations) == 0 {
				return fmt.Errorf("empty recording")
			}
			close(job.ready)
			ready = true
		}
		return nil
	})
	job.mu.Lock()
	if err == nil && len(job.files) != len(job.durations)+1 {
		err = fmt.Errorf("incomplete recording")
	}
	job.err = err
	if s.Log != nil {
		if err == nil {
			s.Log("ALAC recording buffered")
		} else {
			s.Log("ALAC stream ended before completion")
		}
	}
	if !ready {
		close(job.ready)
	}
	close(job.notify)
	job.notify = make(chan struct{})
	job.mu.Unlock()
}

// Reap cancels abandoned downloads. Cached fragments survive short pauses/seeks.
func (s *Server) Reap(now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, job := range s.sessions {
		job.mu.Lock()
		expired := now.Sub(job.last) > 5*time.Minute
		job.mu.Unlock()
		if expired {
			s.retireLocked(id)
		}
	}
}
func (s *Server) Close() { s.Reap(time.Now().Add(time.Hour)) }

func (s *Server) retireLocked(id string) {
	job := s.sessions[id]
	job.cancel()
	delete(s.sessions, id)
	go func() { <-job.done; os.RemoveAll(job.dir) }()
}

type contextRoundTripper struct {
	ctx  context.Context
	next http.RoundTripper
}

func (t contextRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) {
	return t.next.RoundTrip(r.Clone(t.ctx))
}
func contextHTTP(ctx context.Context, client *http.Client) *http.Client {
	copy := *client
	next := client.Transport
	if next == nil {
		next = http.DefaultTransport
	}
	copy.Transport = contextRoundTripper{ctx, next}
	return &copy
}
