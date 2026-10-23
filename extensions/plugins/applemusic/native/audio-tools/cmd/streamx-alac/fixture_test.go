package main

import (
	fairplay "amdl/internal/fairplay-rip"
	"amdl/internal/streamx"
	"bytes"
	"context"
	"io"
	"log"
	"net/http"
	"os"
	"testing"
	"time"
)

// Explicit opt-in local fixture for Android transport/decoder verification.
// This is test code only and cannot be selected by the production executable.
func TestDeviceFixture(t *testing.T) {
	path := os.Getenv("STREAMX_ALAC_TEST_FILE")
	if path == "" {
		t.Skip("device fixture not requested")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	in := bytes.NewReader(raw)
	init, offset, err := fairplay.ReadInitSegment(in)
	if err != nil {
		t.Fatal(err)
	}
	var initial bytes.Buffer
	if err = init.Encode(&initial); err != nil {
		t.Fatal(err)
	}
	var chunks [][]byte
	for {
		frag, next, err := fairplay.ReadNextFragment(in, offset)
		offset = next
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if frag == nil {
			break
		}
		var b bytes.Buffer
		if err = frag.Encode(&b); err != nil {
			t.Fatal(err)
		}
		chunks = append(chunks, b.Bytes())
	}
	if len(chunks) < 2 {
		t.Fatal("fixture must contain several fragments")
	}
	server := streamx.New("", "streamx-device-fixture-123456789012345", t.TempDir())
	defer server.Close()
	server.Produce = func(ctx context.Context, _ string, _ int, emit func(int, []byte, float64) error) error {
		for range chunks {
			if err := emit(-2, nil, 2); err != nil {
				return err
			}
		}
		if err := emit(-1, initial.Bytes(), 0); err != nil {
			return err
		}
		for i, data := range chunks {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(600 * time.Millisecond):
			}
			if err := emit(i, data, 2); err != nil {
				return err
			}
		}
		return nil
	}
	log.Printf("DEVICE FIXTURE READY: %d ALAC fragments", len(chunks))
	t.Fatal(http.ListenAndServe("127.0.0.1:12440", server))
}
