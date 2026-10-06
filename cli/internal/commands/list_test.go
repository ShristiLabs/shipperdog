package commands

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ShristiLabs/shipperdog/cli/internal/config"
)

const (
	appUUID  = "11111111-1111-1111-1111-111111111111"
	verUUID  = "22222222-2222-2222-2222-222222222222"
	artUUID  = "33333333-3333-3333-3333-333333333333"
	appUUID2 = "44444444-4444-4444-4444-444444444444"
	verUUID2 = "55555555-5555-5555-5555-555555555555"
	artUUID2 = "66666666-6666-6666-6666-666666666666"
)

// newListWorld wires two apps (Banking, Retail), each with one version
// carrying an artifact slot.
func newListWorld(t *testing.T) *fakeAPI {
	t.Helper()
	f := newFakeAPI(t)
	f.handleJSON("/api/v1/auth/me", func() any {
		return map[string]any{"id": "u-1", "orgId": "o-1"}
	})
	f.handleJSON("/api/v1/applications", func() any {
		return map[string]any{"items": []map[string]any{
			{"id": appUUID, "name": "Banking"},
			{"id": appUUID2, "name": "Retail"},
		}}
	})
	f.handleJSON("/api/v1/applications/"+appUUID+"/versions", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": verUUID, "versionName": "7.12.0", "status": "ACTIVE", "tags": []string{"PILOT"},
			"artifactsByPlatform": map[string]any{
				"IOS": []map[string]any{{"id": artUUID, "platform": "IOS", "status": "READY", "format": "IPA", "size": 2048, "uploadedAt": "2026-10-07T09:00:00Z", "sha256": "cafe"}},
			},
		}}}
	})
	f.handleJSON("/api/v1/applications/"+appUUID2+"/versions", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": verUUID2, "versionName": "3.0.0", "status": "DRAFT",
			"artifactsByPlatform": map[string]any{
				"ANDROID": []map[string]any{{"id": artUUID2, "platform": "ANDROID", "status": "FAILED", "format": "APK", "size": 4096, "uploadedAt": "2026-10-07T09:05:00Z", "sha256": "beef"}},
			},
		}}}
	})
	f.start()
	return f
}

func runCLI(t *testing.T, f *fakeAPI, args ...string) error {
	t.Helper()
	t.Setenv("HOME", t.TempDir())
	t.Setenv(config.EnvToken, "sdp_test")
	root := NewRoot()
	root.SetArgs(args)
	root.SetOut(&f.stdout)
	return root.ExecuteContext(context.Background())
}

func TestArtifactListTableAndJSON(t *testing.T) {
	f := newListWorld(t)
	if err := runCLI(t, f, "artifact", "list"); err != nil {
		t.Fatalf("artifact list: %v", err)
	}
	table := f.out()
	for _, want := range []string{"Banking", "7.12.0", "IOS", "READY", "IPA", "Retail", "ANDROID", "FAILED"} {
		if !strings.Contains(table, want) {
			t.Errorf("table missing %q:\n%s", want, table)
		}
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "artifact", "list", "--json"); err != nil {
		t.Fatalf("artifact list --json: %v", err)
	}
	var rows []map[string]any
	if err := json.Unmarshal([]byte(f.out()), &rows); err != nil {
		t.Fatalf("json decode: %v\n%s", err, f.out())
	}
	if len(rows) != 2 || rows[0]["application"] != "Banking" || rows[1]["status"] != "FAILED" {
		t.Errorf("rows = %+v", rows)
	}
}

func TestArtifactListVersionFilterAndAppArg(t *testing.T) {
	f := newListWorld(t)
	if err := runCLI(t, f, "artifact", "list", "banking", "--version", "7.12.0"); err != nil {
		t.Fatalf("list: %v", err)
	}
	table := f.out()
	if !strings.Contains(table, artUUID) {
		t.Errorf("filtered row missing:\n%s", table)
	}
	if strings.Contains(table, "Retail") || strings.Contains(table, "3.0.0") {
		t.Errorf("filter leaked other app/version:\n%s", table)
	}
}

func TestVersionListCommand(t *testing.T) {
	f := newListWorld(t)
	if err := runCLI(t, f, "version", "list", "Banking"); err != nil {
		t.Fatalf("version list: %v", err)
	}
	table := f.out()
	for _, want := range []string{"7.12.0", "ACTIVE", "PILOT", "IOS", "1"} {
		if !strings.Contains(table, want) {
			t.Errorf("table missing %q:\n%s", want, table)
		}
	}
}

func TestLogoutCommandClearsCredentials(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv(config.EnvToken, "")
	if err := config.SaveToken("sdp_to_remove"); err != nil {
		t.Fatal(err)
	}
	root := NewRoot()
	root.SetArgs([]string{"logout"})
	if err := root.ExecuteContext(context.Background()); err != nil {
		t.Fatalf("logout: %v", err)
	}
	if _, err := config.LoadToken(); err == nil {
		t.Error("credentials still present after logout")
	}
}

func TestUploadNoWaitReturnsImmediately(t *testing.T) {
	f := newFakeAPI(t)
	registerRegistryForUpload(f)
	f.handleJSON("/api/v1/artifacts", func() any {
		return map[string]any{"id": artUUID, "versionId": verUUID, "status": "VALIDATING", "fileName": "app.ipa", "sha256": "aa", "size": 8}
	})
	f.start()

	file := filepath.Join(t.TempDir(), "app.ipa")
	if err := os.WriteFile(file, []byte("12345678"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := runCLI(t, f, "artifact", "upload", file, "--app", "Banking", "--version", "7.12.0",
		"--platform", "ios", "--no-wait"); err != nil {
		t.Fatalf("no-wait upload: %v", err)
	}
	if f.artifactReads != 0 {
		t.Errorf("--no-wait polled the artifact read %d times", f.artifactReads)
	}
}

// A file larger than --multipart-limit must take the session route
// (create session, direct PUT, finalize) and never POST /api/v1/artifacts.
func TestUploadRoutesLargeFileToSession(t *testing.T) {
	f := newFakeAPI(t)
	registerRegistryForUpload(f)
	multipartRefused := false
	f.HandleFunc("/api/v1/artifacts", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			multipartRefused = true
		}
		w.WriteHeader(http.StatusNotFound)
	})
	f.handleJSON("/api/v1/artifacts/upload-sessions", func() any {
		return map[string]any{"sessionId": "s-1", "artifactId": artUUID, "versionId": verUUID, "uploadUrl": f.storage.URL + "/upload"}
	})
	f.handleJSON("/api/v1/artifacts/"+artUUID+"/finalize", func() any {
		return map[string]any{"created": true, "artifact": map[string]any{"id": artUUID, "versionId": verUUID, "status": "VALIDATING", "fileName": "big.ipa", "sha256": "bb", "size": 9}}
	})
	f.start()

	file := filepath.Join(t.TempDir(), "big.ipa")
	if err := os.WriteFile(file, []byte("123456789"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := runCLI(t, f, "artifact", "upload", file, "--app", "Banking", "--version", "7.12.0",
		"--platform", "ios", "--multipart-limit", "8", "--no-wait"); err != nil {
		t.Fatalf("session upload: %v", err)
	}
	if multipartRefused {
		t.Error("large file must not use the multipart route")
	}
	if len(f.storagePuts) != 1 || string(f.storagePuts[0]) != "123456789" {
		t.Errorf("storage PUTs = %v", f.storagePuts)
	}
}

func TestRevalidateCommandPollsToReady(t *testing.T) {
	f := newFakeAPI(t)
	registerRegistryForUpload(f)
	f.handleJSON("/api/v1/artifacts/"+artUUID+"/revalidate", func() any {
		return map[string]any{"id": artUUID, "versionId": verUUID, "status": "VALIDATING"}
	})
	f.handleJSON("/api/v1/artifacts/"+artUUID, func() any {
		return map[string]any{"id": artUUID, "versionId": verUUID, "status": "READY", "fileName": "app.ipa", "sha256": "aa",
			"validation": map[string]any{"status": "PASSED", "attempts": 2, "durationMs": 12, "stages": []map[string]any{{"name": "FILE", "status": "PASSED"}}}}
	})
	f.start()

	if err := runCLI(t, f, "artifact", "revalidate", artUUID, "--app", "Banking", "--version", "7.12.0", "--timeout", "5s"); err != nil {
		t.Fatalf("revalidate: %v", err)
	}
	if !strings.Contains(f.out(), "READY") {
		t.Errorf("output missing READY:\n%s", f.out())
	}
}

func TestLoginReadsTokenFromPipedStdin(t *testing.T) {
	f := newFakeAPI(t)
	f.handleJSON("/api/v1/auth/me", func() any {
		return map[string]any{"id": "u-9", "email": "piped@example.com"}
	})
	f.start()
	t.Setenv(config.EnvToken, "")

	tmp := filepath.Join(t.TempDir(), "stdin")
	if err := os.WriteFile(tmp, []byte("sdp_piped_token\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	original := os.Stdin
	piped, err := os.Open(tmp)
	if err != nil {
		t.Fatal(err)
	}
	os.Stdin = piped
	defer func() { os.Stdin = original; piped.Close() }()

	root := NewRoot()
	root.SetArgs([]string{"login"})
	if err := root.ExecuteContext(context.Background()); err != nil {
		t.Fatalf("login via pipe: %v", err)
	}
	token, err := config.LoadToken()
	if err != nil || token != "sdp_piped_token" {
		t.Fatalf("stored = %q, %v", token, err)
	}
}

// registerRegistryForUpload wires the Banking app + 7.12.0 version the
// upload-path tests resolve against.
func registerRegistryForUpload(f *fakeAPI) {
	f.handleJSON("/api/v1/auth/me", func() any {
		return map[string]any{"id": "u-1", "orgId": "o-1"}
	})
	f.handleJSON("/api/v1/applications", func() any {
		return map[string]any{"items": []map[string]any{{"id": appUUID, "name": "Banking"}}}
	})
	f.handleJSON("/api/v1/applications/"+appUUID+"/versions", func() any {
		return map[string]any{"items": []map[string]any{{"id": verUUID, "versionName": "7.12.0"}}}
	})
}

// A version UUID needs no --app (the artifact surface is
// body-addressed by version id) - regression lock for the live-smoke
// finding that view/upload wrongly demanded an application context.
func TestVersionUUIDNeedsNoApp(t *testing.T) {
	f := newFakeAPI(t)
	seenVersionID := ""
	f.HandleFunc("/api/v1/artifacts", func(w http.ResponseWriter, r *http.Request) {
		seenVersionID = r.URL.Query().Get("version_id")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"` + artUUID + `","versionId":"` + verUUID + `","status":"VALIDATING"}`))
	})
	f.start()

	file := filepath.Join(t.TempDir(), "app.ipa")
	if err := os.WriteFile(file, []byte("ipa"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := runCLI(t, f, "artifact", "upload", file, "--version", verUUID,
		"--platform", "ios", "--no-wait"); err != nil {
		t.Fatalf("uuid-only upload: %v", err)
	}
	if seenVersionID != verUUID {
		t.Errorf("upload used version_id %q", seenVersionID)
	}
}
