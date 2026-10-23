package fairplayrip

import (
	"bytes"
	"context"
	"github.com/itouakirai/mp4ff/mp4"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestALACVariantSelectionAndNoAACSubstitution(t *testing.T) {
	manifest := "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\",AUDIO=\"audio-stereo-256\"\naac.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS=\"alac\",AUDIO=\"audio-alac-stereo-48000-24\"\nstandard.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS=\"alac\",AUDIO=\"audio-alac-stereo-192000-24\"\nhires.m3u8\n"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(manifest)) }))
	defer server.Close()
	for rate, suffix := range map[int]string{48000: "standard.m3u8", 192000: "hires.m3u8"} {
		url, err := ALACPlaylist(context.Background(), server.Client(), server.URL+"/master.m3u8", rate)
		if err != nil || !strings.HasSuffix(url, suffix) {
			t.Fatalf("rate %d: %s %v", rate, url, err)
		}
	}
	manifest = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\",AUDIO=\"audio-stereo-256\"\naac.m3u8\n"
	if _, err := ALACPlaylist(context.Background(), server.Client(), server.URL, 48000); err == nil {
		t.Fatal("AAC substituted for ALAC")
	}
}

// Parsed mdat positions describe the original headers, before protection boxes
// are removed. Repair must retain sample slices across that header shrink.
func TestRepairUsesOriginalOffsetsBeforeHeaderShrinks(t *testing.T) {
	fragment, err := mp4.CreateFragment(1, 1)
	if err != nil {
		t.Fatal(err)
	}
	protection, err := mp4.NewPsshBox(mp4.UUIDFairPlay, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = fragment.Moof.AddChild(protection); err != nil {
		t.Fatal(err)
	}
	fragment.Moof.Traf.Tfhd.Flags |= mp4.TfhdSampleDescriptionIndexPresentFlag
	fragment.Moof.Traf.Tfhd.SampleDescriptionIndex = 2
	fragment.AddFullSample(mp4.FullSample{Sample: mp4.NewSample(0, 1024, 4, 0), Data: []byte{1, 2, 3, 4}})
	var encoded bytes.Buffer
	if err = fragment.Encode(&encoded); err != nil {
		t.Fatal(err)
	}
	parsed, _, err := ReadNextFragment(bytes.NewReader(encoded.Bytes()), 0)
	if err != nil {
		t.Fatal(err)
	}
	tracks := map[uint32]mp4.DecryptTrackInfo{1: {TrackID: 1, Trex: mp4.CreateTrex(1)}}
	calls := 0
	patchers := map[uint32]func([]byte){1: func(data []byte) {
		calls++
		if !bytes.Equal(data, []byte{1, 2, 3, 4}) {
			t.Errorf("wrong sample: %v", data)
		}
		data[0] = 9
	}}
	if err = decryptAndRepairFragment(parsed, tracks, patchers, nil); err != nil {
		t.Fatal(err)
	}
	if calls != 1 || parsed.Mdat.Data[0] != 9 {
		t.Fatal("repair did not modify original sample")
	}
	var output bytes.Buffer
	if err = parsed.Encode(&output); err != nil {
		t.Fatal(err)
	}
	reparsed, _, err := ReadNextFragment(bytes.NewReader(output.Bytes()), 0)
	if err != nil {
		t.Fatal(err)
	}
	if reparsed.Moof.Traf.Tfhd.SampleDescriptionIndex != 1 {
		t.Fatal("stale sample description survived sanitized init")
	}
	samples, err := reparsed.GetFullSamples(tracks[1].Trex)
	if err != nil || len(samples) != 1 || samples[0].Data[0] != 9 {
		t.Fatalf("output offsets invalid: %v", err)
	}
}

func TestStreamIdleTimeoutDoesNotIncludeDecryptionOrKeyWait(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	reader := &readIdleBody{body: strings.NewReader("ab"), timeout: 20 * time.Millisecond, cancel: cancel}
	buffer := make([]byte, 1)
	if _, err := reader.Read(buffer); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond) // the guest is working, not reading the network
	if ctx.Err() != nil {
		t.Fatal("guest processing incorrectly expired the network idle timer")
	}
	if _, err := reader.Read(buffer); err != nil {
		t.Fatal(err)
	}
}

func TestStreamIdleTimeoutStillCancelsAStalledNetwork(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	input, output := io.Pipe()
	defer input.Close()
	defer output.Close()
	reader := &readIdleBody{body: input, timeout: 20 * time.Millisecond, cancel: cancel}
	done := make(chan struct{})
	go func() { defer close(done); reader.Read(make([]byte, 1)) }()
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("stalled read was never cancelled")
	}
	input.Close()
	<-done
}
