package commands

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeAPI is a small in-process backend for command-level tests: a
// mux for the /api/v1 surface plus a separate storage server that
// records direct PUT bodies (the session-flow target).
type fakeAPI struct {
	t             *testing.T
	mux           *http.ServeMux
	storage       *httptest.Server
	storagePuts   [][]byte
	server        *httptest.Server
	stdout        strings.Builder
	artifactReads int
}

func newFakeAPI(t *testing.T) *fakeAPI {
	t.Helper()
	f := &fakeAPI{t: t, mux: http.NewServeMux()}
	f.storage = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPut {
			body, _ := io.ReadAll(r.Body)
			f.storagePuts = append(f.storagePuts, body)
			w.WriteHeader(http.StatusOK)
			return
		}
		w.WriteHeader(http.StatusMethodNotAllowed)
	}))
	return f
}

// handleJSON answers path with the JSON encoding of body() on every
// method; the closure runs per request so later state can vary.
func (f *fakeAPI) handleJSON(path string, body func() any) {
	f.mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(path, "/api/v1/artifacts/") && r.Method == http.MethodGet {
			f.artifactReads++
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(body())
	})
}

// HandleFunc registers a raw handler (for method-sensitive paths).
func (f *fakeAPI) HandleFunc(path string, fn http.HandlerFunc) {
	f.mux.HandleFunc(path, fn)
}

// start serves the mux; baseURL becomes the CLI's endpoint.
func (f *fakeAPI) start() {
	f.t.Helper()
	f.server = httptest.NewServer(f.mux)
	f.t.Setenv("SHIPPERDOG_API", f.server.URL)
	f.t.Cleanup(f.close)
}

func (f *fakeAPI) close() {
	if f.server != nil {
		f.server.Close()
	}
	f.storage.Close()
}

// out returns everything printed to stdout so far.
func (f *fakeAPI) out() string { return f.stdout.String() }
