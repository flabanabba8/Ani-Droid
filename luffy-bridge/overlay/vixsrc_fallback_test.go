package providers

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
)

type fallbackTransport func(*http.Request) (*http.Response, error)

func (f fallbackTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestCinejoyFailureUsesIndependentFallback(t *testing.T) {
	client := &http.Client{Transport: fallbackTransport(func(r *http.Request) (*http.Response, error) {
		status := 200
		body := ""
		switch {
		case r.URL.Host == "api.shegu.st":
			status = 502
		case r.URL.Path == "/api/movie/9340":
			body = `{"src":"/embed/test"}`
		case r.URL.Path == "/embed/test":
			body = `url: 'https://vixsrc.to/playlist/1?b=1', 'token': 'test', 'expires': '123'`
		case r.URL.Path == "/playlist/1":
			if r.URL.Query().Get("h") != "1" || r.URL.Query().Get("b") != "1" {
				return nil, fmt.Errorf("required playlist flags missing")
			}
			body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://vixsrc.to/video.m3u8\n"
		case r.URL.Path == "/video.m3u8":
			body = "#EXTM3U\n#EXTINF:1,\nhttps://vixsrc.to/segment.ts\n"
		default:
			return nil, fmt.Errorf("unexpected request")
		}
		return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}, nil
	})}
	link, err := NewCinejoy(client).GetLink("movie|9340|The Goonies|1985")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(link, "|referer=https%3A%2F%2Fvixsrc.to%2F") {
		t.Fatal("fallback origin lost")
	}
	if !strings.HasPrefix(link, "https://vixsrc.to/playlist/1?") {
		t.Fatal("master playlist lost")
	}
}
