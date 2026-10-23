package main

import (
	"amdl/internal/streamx"
	"bytes"
	"context"
	"io"
	"strings"
	"testing"
)

func TestStdoutStreamsBeforeProducerCompletes(t *testing.T) {
	reader, writer := io.Pipe()
	release := make(chan struct{})
	finished := make(chan error, 1)
	var progress bytes.Buffer
	produce := streamx.Producer(func(ctx context.Context, id string, rate int, emit func(int, []byte, float64) error) error {
		if id != "123" || rate != 96000 {
			t.Error("song selection lost")
		}
		if err := emit(-2, nil, 2); err != nil {
			return err
		}
		if err := emit(-1, []byte("init"), 0); err != nil {
			return err
		}
		if err := emit(0, []byte("fragment"), 2); err != nil {
			return err
		}
		<-release
		return nil
	})
	go func() {
		finished <- streamSong(context.Background(), produce, "123", 96000, writer, &progress)
		writer.Close()
	}()
	data := make([]byte, len("initfragment"))
	if _, err := io.ReadFull(reader, data); err != nil {
		t.Fatal(err)
	}
	if string(data) != "initfragment" {
		t.Fatalf("media polluted: %q", data)
	}
	select {
	case <-finished:
		t.Fatal("waited for complete production")
	default:
	}
	close(release)
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	reader.Close()
	if !strings.Contains(progress.String(), `"fragments":1`) {
		t.Fatalf("missing progress: %s", progress.String())
	}
}

func TestStdoutCancellationAndBrokenConsumer(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	produce := streamx.Producer(func(_ context.Context, _ string, _ int, emit func(int, []byte, float64) error) error {
		return emit(0, []byte("fragment"), 2)
	})
	if err := streamSong(ctx, produce, "123", 48000, io.Discard, io.Discard); err != context.Canceled {
		t.Fatalf("cancellation: %v", err)
	}
	reader, writer := io.Pipe()
	reader.Close()
	if err := streamSong(context.Background(), produce, "123", 48000, writer, io.Discard); err == nil {
		t.Fatal("ignored closed media pipe")
	}
	writer.Close()
}
