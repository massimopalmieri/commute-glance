package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata"
)

type Departure struct {
	ID              string     `json:"id"`
	Route           string     `json:"route"`
	Destination     string     `json:"destination"`
	ScheduledAt     *time.Time `json:"scheduledAt,omitempty"`
	ExpectedAt      *time.Time `json:"expectedAt,omitempty"`
	Platform        string     `json:"platform,omitempty"`
	Status          string     `json:"status"`
	DurationMinutes int        `json:"durationMinutes,omitempty"`
}
type Board struct {
	Source     string      `json:"source"`
	FetchedAt  time.Time   `json:"fetchedAt"`
	SourceAt   *time.Time  `json:"sourceAt,omitempty"`
	Stale      bool        `json:"stale"`
	Warning    string      `json:"warning,omitempty"`
	Departures []Departure `json:"departures"`
}
type entry struct {
	board   Board
	expires time.Time
}
type server struct {
	client                                       *http.Client
	tfl, rail, railAuth, railAPIKey, key, tflKey string
	mu                                           sync.Mutex
	cache                                        map[string]entry
	now                                          func() time.Time
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
func main() {
	key := os.Getenv("API_TOKEN")
	addr := env("LISTEN_ADDR", "127.0.0.1:8080")
	if key == "" && addr != "127.0.0.1:8080" {
		log.Fatal("API_TOKEN is required when using a custom listen address")
	}
	s := &server{client: &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error { return http.ErrUseLastResponse }}, tfl: "https://api.tfl.gov.uk", rail: os.Getenv("RAIL_BASE_URL"), railAuth: os.Getenv("RAIL_AUTHORIZATION"), railAPIKey: os.Getenv("RAIL_API_KEY"), key: key, tflKey: os.Getenv("TFL_APP_KEY"), cache: map[string]entry{}, now: time.Now}
	if s.rail != "" {
		u, e := url.Parse(s.rail)
		if e != nil || u.Scheme != "https" || u.Host == "" {
			log.Fatal("RAIL_BASE_URL must be an HTTPS LDBWS base URL")
		}
	}
	h := &http.Server{Addr: addr, Handler: s.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = h.Shutdown(c)
	}()
	log.Printf("Commute Glance listening on %s", addr)
	if e := h.ListenAndServe(); e != nil && !errors.Is(e, http.ErrServerClosed) {
		log.Fatal(e)
	}
}
func (s *server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { write(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("GET /v1/stops", s.stops)
	mux.HandleFunc("GET /v1/buses", s.buses)
	mux.HandleFunc("GET /v1/trains", s.trains)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if r.URL.Path != "/healthz" && s.key != "" && subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+s.key)) != 1 {
			write(w, 401, map[string]string{"error": "Unauthorised"})
			return
		}
		mux.ServeHTTP(w, r)
	})
}
func write(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func (s *server) get(ctx context.Context, path, auth string, out any) error {
	headers := http.Header{}
	if auth != "" {
		headers.Set("Authorization", auth)
	}
	return s.getWithHeaders(ctx, path, headers, out)
}

func (s *server) getWithHeaders(ctx context.Context, path string, headers http.Header, out any) error {
	req, e := http.NewRequestWithContext(ctx, "GET", path, nil)
	if e != nil {
		return e
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "CommuteGlance/0.1 (personal departure board)")
	for name, values := range headers {
		for _, value := range values {
			req.Header.Add(name, value)
		}
	}
	res, e := s.client.Do(req)
	if e != nil {
		return errors.New("transport provider unavailable")
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return fmt.Errorf("transport provider returned HTTP %d", res.StatusCode)
	}
	return json.NewDecoder(http.MaxBytesReader(nil, res.Body, 4<<20)).Decode(out)
}
func (s *server) tflURL(path string, q url.Values) string {
	if q == nil {
		q = url.Values{}
	}
	if s.tflKey != "" {
		q.Set("app_key", s.tflKey)
	}
	return s.tfl + path + "?" + q.Encode()
}

// Cache keys include every filter. A failed refresh never advances the data timestamp.
func (s *server) cached(key string, load func() (Board, error)) (Board, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	old, ok := s.cache[key]
	if ok && now.Before(old.expires) {
		return old.board, nil
	}
	b, e := load()
	if e != nil {
		if ok && now.Sub(old.board.FetchedAt) < 10*time.Minute {
			b = old.board
			b.Stale = true
			b.Warning = "Refresh failed; showing cached predictions"
			return b, nil
		}
		return Board{}, e
	}
	if b.Departures == nil {
		b.Departures = []Departure{}
	}
	b.FetchedAt = s.now()
	if len(s.cache) >= 128 {
		clear(s.cache)
	}
	s.cache[key] = entry{b, s.now().Add(30 * time.Second)}
	return b, nil
}
func (s *server) reply(w http.ResponseWriter, b Board, e error) {
	if e != nil {
		write(w, 502, map[string]string{"error": e.Error()})
		return
	}
	write(w, 200, b)
}

var stopID = regexp.MustCompile(`^[A-Za-z0-9]{5,30}$`)

func (s *server) buses(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("stop")
	if !stopID.MatchString(id) {
		write(w, 400, map[string]string{"error": "Valid stop ID required"})
		return
	}
	routes := strings.Split(r.URL.Query().Get("routes"), ",")
	if len(routes) > 20 {
		write(w, 400, map[string]string{"error": "Too many routes"})
		return
	}
	sort.Strings(routes)
	b, e := s.cached("bus:"+id+":"+strings.Join(routes, ","), func() (Board, error) {
		var raw []struct {
			ID, LineName, DestinationName string
			ExpectedArrival               time.Time
			Timestamp                     time.Time
		}
		if e := s.get(r.Context(), s.tflURL("/StopPoint/"+id+"/Arrivals", nil), "", &raw); e != nil {
			return Board{}, e
		}
		b := Board{Source: "TfL"}
		for _, v := range raw {
			if v.ExpectedArrival.IsZero() {
				continue
			}
			if routes[0] != "" && !contains(routes, v.LineName) {
				continue
			}
			if !v.Timestamp.IsZero() && (b.SourceAt == nil || v.Timestamp.Before(*b.SourceAt)) {
				stamp := v.Timestamp
				b.SourceAt = &stamp
			}
			t := v.ExpectedArrival
			b.Departures = append(b.Departures, Departure{ID: v.ID, Route: v.LineName, Destination: v.DestinationName, ExpectedAt: &t, Status: "Predicted"})
		}
		sort.Slice(b.Departures, func(i, j int) bool { return b.Departures[i].ExpectedAt.Before(*b.Departures[j].ExpectedAt) })
		return b, nil
	})
	s.reply(w, b, e)
}
func contains(a []string, v string) bool {
	for _, x := range a {
		if x == v {
			return true
		}
	}
	return false
}

type stop struct {
	ID     string   `json:"id"`
	Name   string   `json:"name"`
	Letter string   `json:"letter"`
	Routes []string `json:"routes"`
}
type tflStop struct {
	ID         string
	NaptanID   string
	CommonName string
	StopLetter string
	Lines      []struct{ Name string }
	Children   []tflStop
}

func flatten(v tflStop, out *[]stop) {
	if v.NaptanID != "" && len(v.Lines) > 0 && len(v.Children) == 0 {
		routes := []string{}
		for _, l := range v.Lines {
			routes = append(routes, l.Name)
		}
		*out = append(*out, stop{v.NaptanID, v.CommonName, v.StopLetter, routes})
	}
	for _, c := range v.Children {
		flatten(c, out)
	}
}
func (s *server) stops(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(q) < 3 || len(q) > 100 {
		write(w, 400, map[string]string{"error": "Enter 3–100 characters"})
		return
	}
	var result struct{ Matches []tflStop }
	if e := s.get(r.Context(), s.tflURL("/StopPoint/Search", url.Values{"query": {q}, "modes": {"bus"}, "maxResults": {"8"}}), "", &result); e != nil {
		write(w, 502, map[string]string{"error": e.Error()})
		return
	}
	// Fetch the bounded set of stop details concurrently so search fits the request timeout.
	matches := result.Matches
	if len(matches) > 8 {
		matches = matches[:8]
	}
	results := make(chan []stop, len(matches))
	for _, m := range matches {
		go func(m tflStop) {
			var detail tflStop
			if e := s.get(r.Context(), s.tflURL("/StopPoint/"+url.PathEscape(m.ID), nil), "", &detail); e != nil {
				results <- nil
				return
			}
			flat := []stop{}
			flatten(detail, &flat)
			results <- flat
		}(m)
	}
	out := []stop{}
	seen := map[string]bool{}
	failed := 0
	for range matches {
		flat := <-results
		if flat == nil {
			failed++
			continue
		}
		for _, v := range flat {
			if !seen[v.ID] {
				seen[v.ID] = true
				out = append(out, v)
			}
		}
	}
	if len(matches) > 0 && failed == len(matches) {
		write(w, 502, map[string]string{"error": "Unable to load stop details; try again"})
		return
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name+out[i].Letter < out[j].Name+out[j].Letter })
	write(w, 200, out)
}

var crs = regexp.MustCompile(`^[A-Z]{3}$`)

type railBoard struct {
	GeneratedAt       time.Time
	PlatformAvailable bool
	TrainServices     []struct {
		ServiceID, Std, Etd, Platform, Operator string
		IsCancelled, FilterLocationCancelled    bool
		SubsequentCallingPoints                 []struct {
			ServiceChangeRequired bool
			CallingPoint          []struct {
				Crs, St     string
				IsCancelled bool
			}
		}
	}
}

func minutes(v string) (int, bool) {
	t, e := time.Parse("15:04", v)
	return t.Hour()*60 + t.Minute(), e == nil
}
func duration(from, to string) (int, bool) {
	a, ok := minutes(from)
	b, ok2 := minutes(to)
	return (b - a + 1440) % 1440, ok && ok2
}
func railTime(clock string, reference time.Time) (time.Time, bool) {
	loc, _ := time.LoadLocation("Europe/London")
	r := reference.In(loc)
	t, e := time.ParseInLocation("2006-01-02 15:04", r.Format("2006-01-02")+" "+clock, loc)
	if e != nil {
		return time.Time{}, false
	}
	// Choose the nearest occurrence, including around midnight.
	if t.Sub(r) < -12*time.Hour {
		t = t.AddDate(0, 0, 1)
	} else if t.Sub(r) > 12*time.Hour {
		t = t.AddDate(0, 0, -1)
	}
	return t, true
}
func normaliseRail(raw railBoard, to string, max int, now time.Time) Board {
	ref := raw.GeneratedAt
	if ref.IsZero() {
		ref = now
	}
	b := Board{Source: "National Rail", SourceAt: &ref}
	for _, v := range raw.TrainServices {
		durationMin := -1
		cancelled := v.IsCancelled || v.FilterLocationCancelled
		for _, group := range v.SubsequentCallingPoints {
			if group.ServiceChangeRequired {
				continue
			}
			for _, p := range group.CallingPoint {
				if p.Crs == to {
					if d, ok := duration(v.Std, p.St); ok {
						durationMin = d
					}
					cancelled = cancelled || p.IsCancelled
				}
			}
		}
		if durationMin < 0 || durationMin > max {
			continue
		}
		scheduled, ok := railTime(v.Std, ref)
		if !ok {
			continue
		}
		status := v.Etd
		var expected *time.Time
		if cancelled {
			status = "Cancelled"
		} else if v.Etd == "On time" {
			x := scheduled
			expected = &x
		} else if x, ok := railTime(v.Etd, scheduled); ok {
			expected = &x
			status = "Expected " + v.Etd
		} else if status == "" {
			status = "Prediction unavailable"
		}
		platform := ""
		if raw.PlatformAvailable {
			platform = v.Platform
		}
		b.Departures = append(b.Departures, Departure{ID: v.ServiceID, Route: v.Operator, Destination: to, ScheduledAt: &scheduled, ExpectedAt: expected, Platform: platform, Status: status, DurationMinutes: durationMin})
	}
	// A delayed fast service still qualifies by scheduled duration, but its
	// prediction determines its position on the departure board.
	sort.SliceStable(b.Departures, func(i, j int) bool {
		a, z := b.Departures[i], b.Departures[j]
		at, zt := a.ScheduledAt, z.ScheduledAt
		if a.ExpectedAt != nil {
			at = a.ExpectedAt
		}
		if z.ExpectedAt != nil {
			zt = z.ExpectedAt
		}
		return at.Before(*zt)
	})
	return b
}
func (s *server) trains(w http.ResponseWriter, r *http.Request) {
	from, to := r.URL.Query().Get("from"), r.URL.Query().Get("to")
	max, e := strconv.Atoi(envQuery(r, "maxMinutes", "20"))
	if !crs.MatchString(from) || !crs.MatchString(to) || e != nil || max < 1 || max > 120 {
		write(w, 400, map[string]string{"error": "Valid station codes and duration required"})
		return
	}
	if s.rail == "" || (s.railAPIKey == "" && s.railAuth == "") {
		write(w, 503, map[string]string{"error": "Rail not configured: add RAIL_BASE_URL and RAIL_API_KEY (or RAIL_AUTHORIZATION) on the server"})
		return
	}
	b, e := s.cached(fmt.Sprintf("rail:%s:%s:%d", from, to, max), func() (Board, error) {
		var raw railBoard
		q := url.Values{"numRows": {"100"}, "filterCrs": {to}, "filterType": {"to"}, "timeOffset": {"0"}, "timeWindow": {"120"}}
		endpoint := strings.TrimRight(s.rail, "/") + "/api/20220120/GetDepBoardWithDetails/" + from + "?" + q.Encode()
		headers := http.Header{}
		// Current Rail Data Marketplace subscriptions use x-apikey. Preserve
		// Authorization support for direct LDBWS subscriptions, without sending
		// either rail credential to TfL or returning it to the Android client.
		if s.railAPIKey != "" {
			headers.Set("x-apikey", s.railAPIKey)
		} else {
			headers.Set("Authorization", s.railAuth)
		}
		if e := s.getWithHeaders(r.Context(), endpoint, headers, &raw); e != nil {
			return Board{}, e
		}
		return normaliseRail(raw, to, max, s.now()), nil
	})
	s.reply(w, b, e)
}
func envQuery(r *http.Request, k, d string) string {
	if v := r.URL.Query().Get(k); v != "" {
		return v
	}
	return d
}
