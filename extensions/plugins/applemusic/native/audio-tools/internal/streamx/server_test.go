package streamx

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testToken = "streamx-test-token-12345678901234567890"

func createSession(t *testing.T, base string) string {
	t.Helper()
	req, _ := http.NewRequest("POST", base+"/v1/sessions", strings.NewReader(`{"id":"123","max_sample_rate":48000}`))
	req.Header.Set("Authorization", "Bearer "+testToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 201 {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("create %d %s", resp.StatusCode, b)
	}
	var result struct{ Path string }
	if err = json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	return result.Path
}
func TestProgressiveFragmentsBeforeDownloadCompletes(t *testing.T) {
	s := New("", testToken, t.TempDir())
	defer s.Close()
	release := make(chan struct{})
	defer close(release)
	s.Produce = func(ctx context.Context, _ string, _ int, emit func(int, []byte, float64) error) error {
		for i := 0; i < 2; i++ {
			if err := emit(-2, nil, 2); err != nil {
				return err
			}
		}
		if err := emit(-1, []byte("init"), 0); err != nil {
			return err
		}
		if err := emit(0, []byte("first"), 2); err != nil {
			return err
		}
		select {
		case <-release:
			return emit(1, []byte("second"), 2)
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	server := httptest.NewServer(s)
	defer server.Close()
	path := createSession(t, server.URL)
	client := &http.Client{Timeout: time.Second}
	for _, suffix := range []string{"index.m3u8", "init.mp4", "0.m4s"} {
		url := server.URL + strings.TrimSuffix(path, "index.m3u8") + suffix
		resp, err := client.Get(url)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != 200 {
			t.Fatalf("%s: %d", suffix, resp.StatusCode)
		}
		if suffix == "index.m3u8" && (!strings.Contains(string(b), "1.m4s") || !strings.Contains(string(b), "#EXT-X-ENDLIST")) {
			t.Fatal("manifest did not describe full recording")
		}
		if suffix == "0.m4s" && string(b) != "first" {
			t.Fatal("wrong first fragment")
		}
	}
	// Seeking to an unfinished fragment waits and cancels cleanly with the client.
	_, err := client.Get(server.URL + strings.TrimSuffix(path, "index.m3u8") + "1.m4s")
	if err == nil {
		t.Fatal("unfinished fragment returned before production")
	}
}
func TestAuthorizationFailuresAndRetirement(t *testing.T) {
	s := New("", testToken, t.TempDir())
	defer s.Close()
	cancelled := make(chan struct{}, 3)
	s.Produce = func(ctx context.Context, _ string, _ int, _ func(int, []byte, float64) error) error {
		<-ctx.Done()
		cancelled <- struct{}{}
		return ctx.Err()
	}
	server := httptest.NewServer(s)
	defer server.Close()
	resp, err := http.Get(server.URL + "/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatal("missing authorization accepted")
	}
	path := createSession(t, server.URL)
	s.Reap(time.Now().Add(6 * time.Minute))
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("abandoned download not cancelled")
	}
	resp, err = http.Get(server.URL + path)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 410 {
		t.Fatal("expired session still accessible")
	}
}
func TestProductionFailureDoesNotLeaveReaderHanging(t *testing.T) {
	s := New("", testToken, t.TempDir())
	defer s.Close()
	s.Produce = func(context.Context, string, int, func(int, []byte, float64) error) error {
		return fmt.Errorf("failed")
	}
	server := httptest.NewServer(s)
	defer server.Close()
	path := createSession(t, server.URL)
	resp, err := http.Get(server.URL + path)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 502 {
		t.Fatal("expected preparation failure")
	}
}
