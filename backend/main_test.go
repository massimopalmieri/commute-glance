package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func testServer() *server {
	return &server{client: &http.Client{Timeout: time.Second}, cache: map[string]entry{}, now: time.Now}
}
func TestCacheFreshness(t *testing.T) {
	s := testServer()
	now := time.Now()
	s.now = func() time.Time { return now }
	calls := 0
	load := func() (Board, error) { calls++; return Board{Source: "test"}, nil }
	a, _ := s.cached("x", load)
	now = now.Add(10 * time.Second)
	_, _ = s.cached("x", load)
	if calls != 1 {
		t.Fatal("cache missed")
	}
	now = now.Add(30 * time.Second)
	b, e := s.cached("x", func() (Board, error) { return Board{}, errors.New("offline") })
	if e != nil || !b.Stale || !a.FetchedAt.Equal(b.FetchedAt) {
		t.Fatal("failed refresh must retain original timestamp")
	}
	now = now.Add(11 * time.Minute)
	_, e = s.cached("x", func() (Board, error) { return Board{}, errors.New("offline") })
	if e == nil {
		t.Fatal("expired data must not be served")
	}
}
func TestBusesFilterAndSort(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`[{"id":"a","lineName":"405","destinationName":"Croydon","expectedArrival":"2026-11-02T08:05:00Z"},{"id":"b","lineName":"166","expectedArrival":"2026-11-02T08:02:00Z"},{"id":"c","lineName":"645","expectedArrival":"2026-11-02T08:01:00Z"}]`))
	}))
	defer up.Close()
	s := testServer()
	s.tfl = up.URL
	r := httptest.NewRequest("GET", "/v1/buses?stop=490007669N&routes=166,405", nil)
	w := httptest.NewRecorder()
	s.handler().ServeHTTP(w, r)
	var b Board
	json.Unmarshal(w.Body.Bytes(), &b)
	if w.Code != 200 || len(b.Departures) != 2 || b.Departures[0].Route != "166" {
		t.Fatal(w.Body.String())
	}
}
func TestAuthAndValidation(t *testing.T) {
	s := testServer()
	s.key = "secret"
	for _, tc := range []struct {
		path, auth string
		status     int
	}{{"/healthz", "", 200}, {"/v1/buses?stop=valid", "", 401}, {"/v1/buses?stop=../bad", "Bearer secret", 400}, {"/v1/trains?from=ECR&to=LBG", "Bearer secret", 503}} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("GET", tc.path, nil)
		r.Header.Set("Authorization", tc.auth)
		s.handler().ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("%s: %d", tc.path, w.Code)
		}
	}
}
func TestRailDurationAndCancellation(t *testing.T) {
	var raw railBoard
	json.Unmarshal([]byte(`{"generatedAt":"2026-11-02T23:50:00Z","platformAvailable":true,"trainServices":[{"serviceID":"fast","std":"23:58","etd":"00:05","platform":"4","subsequentCallingPoints":[{"callingPoint":[{"crs":"ECR","st":"00:13"}]}]},{"serviceID":"slow","std":"23:58","etd":"On time","subsequentCallingPoints":[{"callingPoint":[{"crs":"ECR","st":"00:40"}]}]},{"serviceID":"cancel","std":"23:59","etd":"On time","isCancelled":true,"subsequentCallingPoints":[{"callingPoint":[{"crs":"ECR","st":"00:15"}]}]}]}`), &raw)
	b := normaliseRail(raw, "ECR", 20, time.Now())
	if len(b.Departures) != 2 {
		t.Fatalf("wrong fast filter: %+v", b)
	}
	if b.Departures[0].DurationMinutes != 15 || b.Departures[0].ExpectedAt.Day() != 3 {
		t.Fatal("midnight failed")
	}
	if b.Departures[1].ExpectedAt != nil || b.Departures[1].Status != "Cancelled" {
		t.Fatal("cancelled has prediction")
	}
	raw.PlatformAvailable = false
	b = normaliseRail(raw, "ECR", 20, time.Now())
	if b.Departures[0].Platform != "" {
		t.Fatal("platform suppression ignored")
	}
}
func TestRailEndpoint(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/20220120/GetDepBoardWithDetails/ECR" || r.URL.Query().Get("filterCrs") != "LBG" || r.Header.Get("Authorization") != "Basic test" {
			t.Error("bad upstream request")
		}
		w.Write([]byte(`{"trainServices":[]}`))
	}))
	defer up.Close()
	s := testServer()
	s.rail = up.URL
	s.railAuth = "Basic test"
	w := httptest.NewRecorder()
	s.handler().ServeHTTP(w, httptest.NewRequest("GET", "/v1/trains?from=ECR&to=LBG", nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"departures":[]`) {
		t.Fatal(w.Body.String())
	}
}
func TestUpstreamFailure(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) }))
	defer up.Close()
	s := testServer()
	var v any
	if e := s.get(context.Background(), up.URL, "", &v); e == nil {
		t.Fatal("must reject upstream error")
	}
}
func TestRailDST(t *testing.T) {
	ref := time.Date(2026, 7, 6, 6, 30, 0, 0, time.UTC)
	got, ok := railTime("07:40", ref)
	if !ok || got.UTC().Hour() != 6 {
		t.Fatal("London BST incorrect", got)
	}
}

func TestStopPickerExcludesParentGroups(t *testing.T) {
	var raw tflStop
	json.Unmarshal([]byte(`{"naptanId":"490G00007669","commonName":"Haling Park Gardens","lines":[{"name":"166"}],"children":[{"naptanId":"490007669N","commonName":"Haling Park Gardens","stopLetter":"GW","lines":[{"name":"166"},{"name":"405"}]}]}`), &raw)
	out := []stop{}
	flatten(raw, &out)
	if len(out) != 1 || out[0].ID != "490007669N" || out[0].Letter != "GW" {
		t.Fatalf("stop groups must not be selectable: %+v", out)
	}
}
