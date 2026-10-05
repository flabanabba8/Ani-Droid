package main

import "testing"

func TestFallbackStreamMetadata(t *testing.T) {
	stream, err := streamResponse("https://vixsrc.to/playlist/1?token=test|subs=https%3A%2F%2Fvixsrc.to%2Fen.vtt|referer=https%3A%2F%2Fvixsrc.to%2F")
	if err != nil {
		t.Fatal(err)
	}
	if stream["url"] != "https://vixsrc.to/playlist/1?token=test" {
		t.Fatal("metadata leaked into playback URL")
	}
	if stream["headers"].(map[string]string)["Origin"] != "https://vixsrc.to" {
		t.Fatal("wrong fallback headers")
	}
	if stream["captions"].([]map[string]string)[0]["url"] != "https://vixsrc.to/en.vtt" {
		t.Fatal("caption lost")
	}
	if _, err := streamResponse("https://example.com/video|referer=https%3A%2F%2Fevil.example%2F"); err == nil {
		t.Fatal("unexpected origin accepted")
	}
	if _, err := streamResponse("https://example.com/video|subs=file%3A%2F%2F%2Ftmp%2Fx"); err == nil {
		t.Fatal("unsafe caption accepted")
	}
}

func TestLookMovieOriginAndSourceReports(t *testing.T) {
	s, err := streamResponse("https://cdn.example/movie.m3u8|referer=https%3A%2F%2Fwww.lookmovie2.to%2F|sources=Cinejoy+unavailable%2C+VixSrc+unavailable%2C+LookMovie+available")
	if err != nil {
		t.Fatal(err)
	}
	if s["headers"].(map[string]string)["Origin"] != "https://www.lookmovie2.to" {
		t.Fatal("wrong fallback origin")
	}
	if len(s["sources"].([]string)) != 3 {
		t.Fatal("missing fallback report")
	}
}
