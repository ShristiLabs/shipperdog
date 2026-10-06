package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func tempFile(t *testing.T, content string) (string, int64) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "app.ipa")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(path)
	return path, info.Size()
}

func TestUploadMultipartSendsClaimsAsQuery(t *testing.T) {
	build := int64(842)
	var gotQuery, gotContentType, gotIdempotency, gotCorrelation string
	var gotFormFile []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		gotContentType = r.Header.Get("Content-Type")
		gotIdempotency = r.Header.Get("Idempotency-Key")
		gotCorrelation = r.Header.Get("X-Correlation-Id")
		if r.Method != http.MethodPost || r.URL.Path != pathArtifacts {
			t.Errorf("request %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
			return
		}
		file, header, err := r.FormFile("file")
		if err != nil {
			t.Errorf("FormFile: %v", err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		defer file.Close()
		gotFormFile, _ = io.ReadAll(file)
		if header.Filename != "app.ipa" {
			t.Errorf("filename = %q", header.Filename)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(ArtifactView{ID: "art-1", VersionID: "v-1", Status: "VALIDATING"})
	}))
	defer server.Close()

	path, _ := tempFile(t, "ipa-bytes")
	client := New(server.URL, "token")
	view, err := client.UploadMultipart(context.Background(), UploadOptions{
		VersionID:   "v-1",
		Platform:    "IOS",
		BuildNumber: &build,
		GitCommit:   "abc123abc123abc123abc123abc123abc123abc1",
		GitBranch:   "main",
		Pipeline:    "nightly",
		CiRunUrl:    "https://ci.example.com/run/842",
	}, path, nil)
	if err != nil {
		t.Fatalf("UploadMultipart: %v", err)
	}
	if view.ID != "art-1" {
		t.Errorf("view.ID = %q", view.ID)
	}
	if string(gotFormFile) != "ipa-bytes" {
		t.Errorf("file body = %q", gotFormFile)
	}
	for _, want := range []string{
		"version_id=v-1", "platform=IOS", "build_number=842",
		"git_commit=abc123abc123abc123abc123abc123abc123abc1",
		"git_branch=main", "pipeline=nightly",
		"ci_run_url=https%3A%2F%2Fci.example.com%2Frun%2F842",
	} {
		if !strings.Contains(gotQuery, want) {
			t.Errorf("query missing %q (got %q)", want, gotQuery)
		}
	}
	if !strings.HasPrefix(gotContentType, "multipart/form-data") {
		t.Errorf("content type = %q", gotContentType)
	}
	if gotIdempotency == "" {
		t.Error("Idempotency-Key not sent")
	}
	if gotCorrelation == "" {
		t.Error("X-Correlation-Id not sent")
	}
}

func TestUploadMultipartSurfaces413(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusRequestEntityTooLarge)
		_, _ = w.Write([]byte(`{"status":413,"code":"ARTIFACT_TOO_LARGE","detail":"above the multipart threshold"}`))
	}))
	defer server.Close()

	path, _ := tempFile(t, "x")
	client := New(server.URL, "token")
	_, err := client.UploadMultipart(context.Background(), UploadOptions{VersionID: "v", Platform: "IOS"}, path, nil)
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("error type %T", err)
	}
	if apiErr.Problem.Code != "ARTIFACT_TOO_LARGE" || apiErr.Problem.Status != 413 {
		t.Errorf("problem = %+v", apiErr.Problem)
	}
}

func TestSessionFlowHappyPath(t *testing.T) {
	var putBytes []byte
	storage := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut {
			t.Errorf("storage method %s", r.Method)
		}
		if r.Header.Get("Authorization") != "" {
			t.Error("storage PUT must not carry the API bearer token")
		}
		putBytes, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusOK)
	}))
	defer storage.Close()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case pathArtifactSessions:
			var body CreateSessionRequest
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body.VersionID != "v-1" || body.Platform != "IOS" || body.FileName != "big.ipa" || body.SizeHint != 9 {
				t.Errorf("session request = %+v", body)
			}
			_ = json.NewEncoder(w).Encode(UploadSessionView{ArtifactID: "art-9", VersionID: "v-1", UploadURL: storage.URL + "/session"})
		case expand(pathArtifactFinalize, "art-9"):
			var body map[string]string
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["versionId"] != "v-1" {
				t.Errorf("finalize body = %+v", body)
			}
			_ = json.NewEncoder(w).Encode(FinalizeView{Created: true, Artifact: &ArtifactView{ID: "art-9", Status: "VALIDATING"}})
		default:
			t.Errorf("unexpected path %q", r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	path, _ := tempFile(t, "big-bytes")
	client := New(apiServer.URL, "token")
	session, err := client.CreateUploadSession(context.Background(), CreateSessionRequest{VersionID: "v-1", Platform: "IOS", FileName: "big.ipa", SizeHint: 9})
	if err != nil {
		t.Fatalf("CreateUploadSession: %v", err)
	}
	if err := client.PutToStorage(context.Background(), session.UploadURL, path, 9, nil); err != nil {
		t.Fatalf("PutToStorage: %v", err)
	}
	if string(putBytes) != "big-bytes" {
		t.Errorf("stored bytes = %q", putBytes)
	}
	final, err := client.Finalize(context.Background(), "art-9", "v-1")
	if err != nil || !final.Created || final.Artifact.ID != "art-9" {
		t.Fatalf("Finalize = %+v, %v", final, err)
	}
}

func TestFinalizeReplay(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"created":false,"artifact":{"id":"art-9","status":"READY"}}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	final, err := client.Finalize(context.Background(), "art-9", "v-1")
	if err != nil {
		t.Fatalf("Finalize: %v", err)
	}
	if final.Created || final.Artifact.Status != "READY" {
		t.Errorf("final = %+v", final)
	}
}

func TestWaitForSettlePollsUntilTerminal(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls++
		w.Header().Set("Content-Type", "application/json")
		status := "VALIDATING"
		if calls >= 3 {
			status = "FAILED"
		}
		_, _ = w.Write([]byte(`{"id":"art-1","status":"` + status + `","validation":{"status":"FAILED","stages":[{"name":"SIGNING","status":"FAILED","code":"SIGNING_ABSENT","reason":"no profile"}]}}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	view, err := client.WaitForSettle(context.Background(), "art-1", "v-1", time.Millisecond, time.Now().Add(5*time.Second), nil)
	if err != nil {
		t.Fatalf("WaitForSettle: %v", err)
	}
	if view.Status != "FAILED" || len(view.Validation.Stages) != 1 {
		t.Errorf("view = %+v", view)
	}
	if calls != 3 {
		t.Errorf("calls = %d, want 3", calls)
	}
}

func TestRevalidateSurfaces409(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"status":409,"code":"ARTIFACT_NOT_VALIDATABLE","detail":"READY artifacts cannot re-enter validation"}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	_, err := client.Revalidate(context.Background(), "art-1", "v-1")
	apiErr, ok := err.(*APIError)
	if !ok || apiErr.Problem.Code != "ARTIFACT_NOT_VALIDATABLE" {
		t.Fatalf("err = %v", err)
	}
}
