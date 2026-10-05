// Ani-Droid's bounded JSON adapter to the pinned upstream Luffy engine.
// GPL-3.0-or-later; source and notices are documented in README.md.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/demonkingswarn/luffy/core"
	"github.com/demonkingswarn/luffy/core/providers"
)

type request struct {
	Action  string `json:"action"`
 Source string `json:"source"`
	Query   string `json:"query"`
	ID      string `json:"id"`
	Kind    string `json:"kind"`
	Page    int    `json:"page"`
	Season  int    `json:"season"`
	Episode int    `json:"episode"`
	Title   string `json:"title"`
	Year    string `json:"year"`
}
type title struct {
	Provider string   `json:"provider"`
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Poster   string   `json:"poster"`
	Info     string   `json:"info"`
	Series   bool     `json:"series"`
	Genres   []string `json:"genres"`
}
type genre struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}
type media struct {
	ID        int     `json:"id"`
	Title     string  `json:"title"`
	Name      string  `json:"name"`
	MediaType string  `json:"media_type"`
	Poster    string  `json:"poster_path"`
	Date      string  `json:"release_date"`
	AirDate   string  `json:"first_air_date"`
	GenreIDs  []int   `json:"genre_ids"`
	Genres    []genre `json:"genres"`
	Overview  string  `json:"overview"`
	Seasons   []struct {
		Number   int `json:"season_number"`
		Episodes int `json:"episode_count"`
	} `json:"seasons"`
}

var idPattern = regexp.MustCompile(`^(movie|tv)-([0-9]{1,12})$`)
var httpClient = core.NewClient()

func metadata(path string, params url.Values, out any) error {
	if params == nil {
		params = url.Values{}
	}
	params.Set("api_key", core.TMDB_API_KEY)
	params.Set("language", "en-US")
	req, err := core.NewRequest("GET", core.TMDB_BASE_URL+path+"?"+params.Encode())
	if err != nil {
		return fmt.Errorf("invalid metadata request")
	}
	response, err := httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("metadata network unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return fmt.Errorf("metadata returned HTTP %d", response.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(response.Body, 4<<20)).Decode(out)
}
func tags() (map[int]string, error) {
	result := map[int]string{}
	for _, kind := range []string{"movie", "tv"} {
		var response struct {
			Genres []genre `json:"genres"`
		}
		if err := metadata("/genre/"+kind+"/list", nil, &response); err != nil {
			return nil, err
		}
		for _, g := range response.Genres {
			result[g.ID] = g.Name
		}
	}
	return result, nil
}
func toTitle(m media, kind string, genres map[int]string) title {
	if m.MediaType != "" {
		kind = m.MediaType
	}
	name := m.Title
	if name == "" {
		name = m.Name
	}
	date := m.Date
	if date == "" {
		date = m.AirDate
	}
	if len(date) > 4 {
		date = date[:4]
	}
	poster := ""
	if m.Poster != "" {
		poster = "https://image.tmdb.org/t/p/w500" + m.Poster
	}
	gs := []string{}
	for _, g := range m.Genres {
		gs = append(gs, g.Name)
	}
	for _, id := range m.GenreIDs {
		if g := genres[id]; g != "" {
			gs = append(gs, g)
		}
	}
	label := "Movie"
	if kind == "tv" {
		label = "Series"
	}
	return title{"luffy", fmt.Sprintf("%s-%d", kind, m.ID), name, poster, strings.TrimSpace(date + " · " + label), kind == "tv", gs}
}
func run(r request) (any, error) {
	switch r.Action {
	case "search", "browse":
		if r.Page < 1 || r.Page > 500 {
			return nil, fmt.Errorf("invalid page")
		}
		params := url.Values{"page": {strconv.Itoa(r.Page)}, "include_adult": {"false"}}
		path := "/search/multi"
		kind := ""
		if r.Action == "search" {
			if len(strings.TrimSpace(r.Query)) == 0 || len(r.Query) > 200 {
				return nil, fmt.Errorf("invalid query")
			}
			params.Set("query", r.Query)
		} else {
			if r.Kind != "movie" && r.Kind != "tv" {
				return nil, fmt.Errorf("invalid kind")
			}
			kind = r.Kind
			path = "/discover/" + kind
			params.Set("sort_by", "popularity.desc")
			params.Set("with_original_language", "en")
		}
		var response struct {
			Results    []media `json:"results"`
			TotalPages int     `json:"total_pages"`
		}
		if err := metadata(path, params, &response); err != nil {
			return nil, err
		}
		genres, err := tags()
		if err != nil {
			return nil, err
		}
		items := []title{}
		for _, m := range response.Results {
			if r.Action == "search" && m.MediaType != "movie" && m.MediaType != "tv" {
				continue
			}
			items = append(items, toTitle(m, kind, genres))
		}
		total := min(response.TotalPages, 500)
		return map[string]any{"items": items, "hasMore": r.Page < total, "totalPages": total}, nil
	case "details", "genres":
		parts := idPattern.FindStringSubmatch(r.ID)
		if parts == nil {
			return nil, fmt.Errorf("invalid title ID")
		}
		var m media
		if err := metadata("/"+parts[1]+"/"+parts[2], nil, &m); err != nil {
			return nil, err
		}
		t := toTitle(m, parts[1], nil)
		if r.Action == "genres" {
			return t, nil
		}
		episodes := []map[string]any{}
		if parts[1] == "movie" {
			episodes = append(episodes, map[string]any{"id": r.ID, "season": 0, "number": "0", "name": ""})
		} else {
			for _, s := range m.Seasons {
				if s.Number < 1 || s.Episodes > 2000 {
					continue
				}
				for ep := 1; ep <= s.Episodes; ep++ {
					episodes = append(episodes, map[string]any{"id": fmt.Sprintf("%s-s%d-e%d", r.ID, s.Number, ep), "season": s.Number, "number": strconv.Itoa(ep), "name": ""})
				}
			}
		}
		return map[string]any{"title": t, "description": m.Overview, "episodes": episodes, "audio": []map[string]string{{"id": "original", "label": "Original audio"}}}, nil
	case "streams":
		parts := idPattern.FindStringSubmatch(r.ID)
		if parts == nil || len(r.Title) > 500 || len(r.Year) > 100 {
			return nil, fmt.Errorf("invalid stream request")
		}
		name := strings.ReplaceAll(r.Title, "|", "-")
		year := strings.ReplaceAll(r.Year, "|", "-")
		id := "movie|" + parts[2] + "|" + name + "|" + year
		if parts[1] == "tv" {
			if r.Season < 1 || r.Season > 1000 || r.Episode < 1 || r.Episode > 10000 {
				return nil, fmt.Errorf("invalid episode")
			}
			id = fmt.Sprintf("series|%s|%d|%d|%s|%s", parts[2], r.Season, r.Episode, name, year)
		}
		var link string
        var err error
        switch r.Source {
        case "", "auto": link,err=providers.NewCinejoy(httpClient).GetLink(id)
        case "cinejoy": link,err=providers.NewCinejoy(httpClient).GetCinejoyLink(id)
        case "vixsrc": link,err=providers.NewVixSrc(httpClient).GetLink(id);link += "|referer="+url.QueryEscape(providers.VixSrcBaseURL+"/")
        case "lookmovie": link,err=providers.NewLookMovie(httpClient).GetLink(id);link += "|referer="+url.QueryEscape(providers.LookMovieBaseURL+"/")
        default: return nil,fmt.Errorf("unsupported source")
        }
		if err != nil {
			return nil, fmt.Errorf("Selected sources could not resolve this title. Retry or choose another source")
		}
		stream, err := streamResponse(link)
		if err != nil {
			return nil, err
		}
		streams := []map[string]any{stream}
		return map[string]any{"streams": streams}, nil
	default:
		return nil, fmt.Errorf("unsupported Luffy operation")
	}
}
func main() {
	httpClient.Timeout = 12 * time.Second
	var r request
	err := json.NewDecoder(io.LimitReader(os.Stdin, 65536)).Decode(&r)
	var data any
	if err == nil {
		data, err = run(r)
	}
	if err != nil {
		json.NewEncoder(os.Stdout).Encode(map[string]string{"error": err.Error()})
		os.Exit(1)
	}
	if err = json.NewEncoder(os.Stdout).Encode(data); err != nil {
		os.Exit(1)
	}
}

// Decode the engine's existing link metadata before handing URLs to Media3/webOS.
func streamResponse(link string) (map[string]any, error) {
	parts := strings.Split(link, "|")
	u, err := url.Parse(parts[0])
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
		return nil, fmt.Errorf("provider returned an unsupported stream")
	}
	origin := providers.CinejoyBaseURL
	captions := []map[string]string{}
	sources := []string{}
	for _, part := range parts[1:] {
		key, value, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		decoded, err := url.QueryUnescape(value)
		if err != nil {
			return nil, fmt.Errorf("invalid stream metadata")
		}
		switch key {
		case "referer":
			if decoded != providers.VixSrcBaseURL+"/" && decoded != providers.LookMovieBaseURL+"/" {
				return nil, fmt.Errorf("unsupported stream origin")
			}
			origin = strings.TrimSuffix(decoded, "/")
		case "sources":
			sources = strings.Split(decoded, ", ")
		case "subs":
			for _, caption := range strings.Split(decoded, ",") {
				c, err := url.Parse(caption)
				if err != nil || c.Scheme != "https" || c.Host == "" || c.User != nil {
					return nil, fmt.Errorf("unsupported caption URL")
				}
				captions = append(captions, map[string]string{"name": "English", "url": caption})
			}
		}
	}
	label := "Auto · Luffy"
	if origin == providers.VixSrcBaseURL {
		label += " · VixSrc"
	}
	if origin == providers.LookMovieBaseURL {
		label += " · LookMovie"
	}
	headers := map[string]string{"Referer": origin + "/", "Origin": origin, "User-Agent": "Mozilla/5.0"}
	return map[string]any{"label": label, "url": parts[0], "headers": headers, "height": 0, "captions": captions, "sources": sources}, nil
}
