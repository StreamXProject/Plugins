package main

import (
	"amdl/internal/streamx"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"syscall"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:12440", "HTTP listen address; use a TLS reverse proxy for remote access")
	lite := flag.String("wrapper", "http://127.0.0.1:12340", "Authenticated wrapper-lite service")
	cache := flag.String("cache", "", "Private temporary cache directory (default OS temp)")
	song := flag.String("song", "", "Stream one catalogue song as fragmented ALAC MP4 to stdout, then exit")
	rate := flag.Int("max-sample-rate", 48000, "ALAC sample-rate ceiling: 48000, 96000 or 192000")
	configFile := flag.String("runtime-config", "", "Private JSON runtime configuration (keeps credentials out of argv)")
	flag.Parse()
	token := os.Getenv("STREAMX_ALAC_TOKEN")
	if *configFile != "" {
		var config struct {
			Wrapper string `json:"wrapper"`
			Token   string `json:"token"`
			Listen  string `json:"listen"`
			Cache   string `json:"cache"`
		}
		file, err := os.Open(*configFile)
		if err != nil {
			log.Fatal("Private runtime configuration unavailable")
		}
		err = json.NewDecoder(io.LimitReader(file, 16384)).Decode(&config)
		file.Close()
		if err != nil {
			log.Fatal("Invalid private runtime configuration")
		}
		*lite, token = config.Wrapper, config.Token
		if config.Listen != "" {
			*listen = config.Listen
		}
		if config.Cache != "" {
			*cache = config.Cache
		}
	}
	if *song != "" {
		if !regexp.MustCompile(`^[0-9]{1,20}$`).MatchString(*song) || (*rate != 48000 && *rate != 96000 && *rate != 192000) {
			log.Fatal("Expected a numeric catalogue song ID and a supported sample-rate ceiling")
		}
		ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer cancel()
		if err := streamSong(ctx, streamx.New(*lite, "", "").Produce, *song, *rate, os.Stdout, os.Stderr); err != nil {
			log.Fatal(err)
		}
		return
	}
	if len(token) < 24 {
		log.Fatal("Set STREAMX_ALAC_TOKEN to at least 24 random characters")
	}
	server := streamx.New(*lite, token, *cache)
	server.Log = func(message string) { log.Print(message) }
	defer server.Close()
	go func() {
		for now := range time.Tick(15 * time.Second) {
			server.Reap(now)
		}
	}()
	log.Printf("ALAC bridge listening on %s", *listen)
	log.Fatal((&http.Server{Addr: *listen, Handler: server, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second}).ListenAndServe())
}

// stdout contains media only. New fragments are written immediately; the pipe
// applies backpressure without accumulating a recording in memory or on disk.
func streamSong(ctx context.Context, produce streamx.Producer, id string, rate int, output, progress io.Writer) error {
	total, done, duration := 0, 0, 0.0
	encode := json.NewEncoder(progress)
	return produce(ctx, id, rate, func(index int, data []byte, seconds float64) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if index == -2 {
			total++
			duration += seconds
			return nil
		}
		n, err := output.Write(data)
		if err != nil {
			return fmt.Errorf("media output: %w", err)
		}
		if n != len(data) {
			return io.ErrShortWrite
		}
		if index >= 0 {
			done++
		}
		return encode.Encode(map[string]any{"fragments": done, "total": total, "duration": duration})
	})
}
