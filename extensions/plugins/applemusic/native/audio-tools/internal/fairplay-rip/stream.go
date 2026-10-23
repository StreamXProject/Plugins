package fairplayrip

import (
	"amdl/internal/media/alacfix"
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"amdl/internal/temari"
	"amdl/internal/wrapper"
	"github.com/grafov/m3u8"
	"github.com/itouakirai/mp4ff/mp4"
)

// StreamFragments keeps only one fragment in memory. The sink supplies backpressure
// and owns caching; cancellation closes the upstream HTTP body immediately.
func StreamFragments(ctx context.Context, client *http.Client, lite, adam, playlistURL string, sink func(int, *mp4.InitSegment, *mp4.Fragment, float64) error) error {
	if client == nil {
		client = &http.Client{Timeout: 2 * time.Minute}
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	streaming := *client
	streaming.Timeout = 0
	get := func(raw string) (*http.Response, error) {
		timer := time.AfterFunc(30*time.Second, cancel)
		defer timer.Stop()
		req, err := http.NewRequestWithContext(ctx, "GET", raw, nil)
		if err != nil {
			return nil, err
		}
		resp, err := streaming.Do(req)
		if err == nil && resp.StatusCode != 200 {
			resp.Body.Close()
			return nil, fmt.Errorf("upstream HTTP %d", resp.StatusCode)
		}
		return resp, err
	}
	resp, err := get(playlistURL)
	if err != nil {
		return err
	}
	manifest, err := io.ReadAll(io.LimitReader(&readIdleBody{body: resp.Body, timeout: 30 * time.Second, cancel: cancel}, 2*1024*1024+1))
	resp.Body.Close()
	if err != nil {
		return err
	}
	if len(manifest) > 2*1024*1024 {
		return fmt.Errorf("manifest too large")
	}
	segments, err := parseMediaPlaylist(io.NopCloser(strings.NewReader(string(manifest))))
	if err != nil {
		return err
	}
	if len(segments) == 0 || segments[0] == nil || segments[0].Limit <= 0 {
		return fmt.Errorf("expected byte-range ALAC playlist")
	}
	for _, segment := range segments {
		if segment != nil {
			if segment.Duration <= 0 {
				return fmt.Errorf("invalid fragment duration")
			}
			if err = sink(-2, nil, nil, segment.Duration); err != nil {
				return err
			}
		}
	}
	base, err := url.Parse(playlistURL)
	if err != nil {
		return err
	}
	ref, err := url.Parse(segments[0].URI)
	if err != nil {
		return err
	}
	resp, err = get(base.ResolveReference(ref).String())
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	in := bufio.NewReader(&readIdleBody{timeout: 30 * time.Second, cancel: cancel, body: resp.Body})
	init, offset, err := ReadInitSegment(in)
	if err != nil {
		return err
	}
	tracks, err := TransformInit(init)
	if err != nil {
		return err
	}
	if err = sanitizeInit(init); err != nil {
		return err
	}
	for _, track := range tracks {
		if track.Trex != nil {
			track.Trex.DefaultSampleDescriptionIndex = 1
		}
	}
	var patchers = make(map[uint32]func([]byte))
	for _, trak := range init.Moov.Traks {
		for _, entry := range trak.Mdia.Minf.Stbl.Stsd.Children {
			if entry.Type() == "alac" {
				var b bytes.Buffer
				if err = entry.Encode(&b); err != nil {
					return err
				}
				patch, patchErr := alacfix.PacketPatcher(b.Bytes())
				if patchErr != nil {
					return patchErr
				}
				patchers[trak.Tkhd.TrackID] = patch
			}
		}
	}
	if len(patchers) == 0 {
		return fmt.Errorf("stream contains no ALAC track")
	}
	if err = sink(-1, init, nil, 0); err != nil {
		return err
	}
	var tmpl *temari.Template
	for index := 0; ; index++ {
		if err = ctx.Err(); err != nil {
			return err
		}
		frag, next, readErr := ReadNextFragment(in, offset)
		offset = next
		if readErr != nil {
			return readErr
		}
		if frag == nil {
			return nil
		}
		if index >= len(segments) || segments[index] == nil {
			return fmt.Errorf("fragment count differs from manifest")
		}
		segment := segments[index]
		if segment.Key != nil && (index < 2) {
			id := adam
			if segment.Key.URI == wrapper.PrefetchKey {
				id = "0"
			}
			// The HTTP client's bounded timeout also covers the wrapper request.
			raw, keyErr := wrapper.NewWithHTTPClient(lite, withContext(ctx, client)).KeyJSON(id, segment.Key.URI)
			if keyErr != nil {
				return fmt.Errorf("wrapper key unavailable")
			}
			tmpl, err = temari.FromJSON(raw)
			if err != nil {
				return fmt.Errorf("invalid wrapper response")
			}
		}
		if err = decryptAndRepairFragment(frag, tracks, patchers, tmpl); err != nil {
			return err
		}
		if err = sink(index, nil, frag, segment.Duration); err != nil {
			return err
		}
	}
}

// ALACPlaylist chooses an actual ALAC variant; it never relabels AAC as lossless.
func ALACPlaylist(ctx context.Context, client *http.Client, masterURL string, maxRate int) (string, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", masterURL, nil)
	if err != nil {
		return "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("manifest HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024+1))
	if err != nil {
		return "", err
	}
	if len(data) > 2*1024*1024 {
		return "", fmt.Errorf("manifest too large")
	}
	decoded, kind, err := m3u8.DecodeFrom(strings.NewReader(string(data)), true)
	if err != nil {
		return "", err
	}
	if kind != m3u8.MASTER {
		return "", fmt.Errorf("expected ALAC master manifest")
	}
	var selected *m3u8.Variant
	for _, v := range decoded.(*m3u8.MasterPlaylist).Variants {
		if v == nil || v.Codecs != "alac" {
			continue
		}
		fields := strings.Split(v.Audio, "-")
		rate := 0
		if len(fields) > 1 {
			fmt.Sscan(fields[len(fields)-2], &rate)
		}
		if rate <= 0 || rate > maxRate {
			continue
		}
		if selected == nil || v.Bandwidth > selected.Bandwidth {
			selected = v
		}
	}
	if selected == nil {
		return "", fmt.Errorf("no ALAC recording within selected sample rate")
	}
	base, err := url.Parse(masterURL)
	if err != nil {
		return "", err
	}
	ref, err := url.Parse(selected.URI)
	if err != nil {
		return "", err
	}
	return base.ResolveReference(ref).String(), nil
}

type boundTransport struct {
	ctx  context.Context
	next http.RoundTripper
}

func (t boundTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return t.next.RoundTrip(req.Clone(t.ctx))
}
func withContext(ctx context.Context, client *http.Client) *http.Client {
	copy := *client
	next := client.Transport
	if next == nil {
		next = http.DefaultTransport
	}
	copy.Transport = boundTransport{ctx, next}
	return &copy
}

func decryptAndRepairFragment(frag *mp4.Fragment, tracks map[uint32]mp4.DecryptTrackInfo, patchers map[uint32]func([]byte), tmpl *temari.Template) error {
	// Resolve sample slices while offsets still describe the encrypted boxes.
	// DecryptFragment removes those boxes and adjusts trun offsets for output;
	// the parser's original mdat.StartPos then no longer matches those offsets.
	// These slices alias mdat.Data and see the in-place decrypted bytes below.
	var repair []func()
	for trackID, patch := range patchers {
		ti, ok := tracks[trackID]
		if !ok {
			return fmt.Errorf("missing ALAC track description")
		}
		samples, sampleErr := frag.GetFullSamples(ti.Trex)
		if sampleErr != nil {
			return sampleErr
		}
		repair = append(repair, func() {
			for _, sample := range samples {
				patch(sample.Data)
			}
		})
	}
	if err := DecryptFragment(frag, tracks, tmpl); err != nil {
		return fmt.Errorf("fragment decode failed: %w", err)
	}
	// sanitizeInit keeps one sample entry. Later Apple fragments often still
	// reference entry 2; strict demuxers silently skip all those samples unless
	// the fragment points to the surviving entry (as the full-file defrag does).
	for _, traf := range frag.Moof.Trafs {
		if traf.Tfhd.HasSampleDescriptionIndex() {
			traf.Tfhd.SampleDescriptionIndex = 1
		}
	}
	for _, patch := range repair {
		patch()
	}
	return nil
}

// Only a blocked network read is idle. Waiting for QEMU keys, decrypting a
// fragment, and a slow sink must not cancel an otherwise healthy recording.
type readIdleBody struct {
	body    io.Reader
	timeout time.Duration
	cancel  context.CancelFunc
}

func (b *readIdleBody) Read(p []byte) (int, error) {
	timer := time.AfterFunc(b.timeout, b.cancel)
	defer timer.Stop()
	return b.body.Read(p)
}
