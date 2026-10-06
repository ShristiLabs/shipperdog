package commands

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/config"
	"github.com/ShristiLabs/shipperdog/cli/internal/exitcode"
)

func TestPlatformNormalization(t *testing.T) {
	for in, want := range map[string]string{"ios": "IOS", "IOS": "IOS", "Android": "ANDROID"} {
		got, err := platformFor(in)
		if err != nil || got != want {
			t.Errorf("platformFor(%q) = %q, %v", in, got, err)
		}
	}
	if _, err := platformFor("web"); err == nil {
		t.Error("web accepted")
	}
}

func TestExtensionCoherence(t *testing.T) {
	if err := checkExtensionCoherence("app.ipa", "IOS"); err != nil {
		t.Errorf("ipa/IOS rejected: %v", err)
	}
	if err := checkExtensionCoherence("app.apk", "ANDROID"); err != nil {
		t.Errorf("apk/ANDROID rejected: %v", err)
	}
	if err := checkExtensionCoherence("app.aab", "ANDROID"); err != nil {
		t.Errorf("aab/ANDROID rejected: %v", err)
	}
	for _, bad := range []struct{ name, platform string }{
		{"app.apk", "IOS"}, {"app.ipa", "ANDROID"}, {"app.zip", "IOS"},
	} {
		if err := checkExtensionCoherence(bad.name, bad.platform); err == nil {
			t.Errorf("%s/%s accepted", bad.name, bad.platform)
		}
	}
}

// The exit-code contract (AC): auth, validation, and network failures
// are distinguishable.
func TestExitCodeContract(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{"nil", nil, exitcode.OK},
		{"usage", &UsageError{msg: "bad flag"}, exitcode.Usage},
		{"resolution", &api.ResolutionError{Kind: "application", Ref: "x"}, exitcode.Usage},
		{"no-credentials", config.ErrNoCredentials, exitcode.Auth},
		{"unauthenticated", &api.APIError{Problem: api.Problem{Status: 401, Code: "UNAUTHENTICATED"}}, exitcode.Auth},
		{"validation", &api.APIError{Problem: api.Problem{Status: 400, Code: "INVALID_REQUEST"}}, exitcode.Validation},
		{"conflict", &api.APIError{Problem: api.Problem{Status: 409, Code: "ARTIFACT_BUILD_TAKEN"}}, exitcode.Validation},
		{"settled-failed", &FailureError{msg: "artifact settled FAILED"}, exitcode.Validation},
		{"server", &api.APIError{Problem: api.Problem{Status: 503}}, exitcode.Server},
		{"network", &api.APIError{Network: true, Inner: errors.New("refused")}, exitcode.Network},
	}
	for _, tc := range cases {
		if got := ExitCode(tc.err); got != tc.want {
			t.Errorf("%s: ExitCode = %d, want %d", tc.name, got, tc.want)
		}
	}
}

// End-to-end through the command tree: upload the issue's example
// command shape against a fake API, with a settled-FAILED validation
// answer, and assert the documented exit class.
func TestUploadCommandSettledFailedExitsValidation(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv(config.EnvToken, "sdp_test")
	t.Setenv(config.EnvAPIBase, "")

	var uploadSeen bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/api/v1/auth/me" && r.Method == http.MethodGet:
			_ = json.NewEncoder(w).Encode(map[string]any{"id": "u-1", "orgId": "o-1"})
		case r.URL.Path == "/api/v1/applications":
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []map[string]any{{"id": "11111111-1111-1111-1111-111111111111", "name": "Banking"}}})
		case strings.HasPrefix(r.URL.Path, "/api/v1/applications/11111111-1111-1111-1111-111111111111/versions") && r.Method == http.MethodGet:
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []map[string]any{{"id": "22222222-2222-2222-2222-222222222222", "versionName": "7.12.0"}}})
		case r.URL.Path == "/api/v1/artifacts" && r.Method == http.MethodPost:
			uploadSeen = true
			if r.URL.Query().Get("git_commit") != "abc123" || r.URL.Query().Get("version_id") != "22222222-2222-2222-2222-222222222222" {
				t.Errorf("upload query = %q", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"id":"33333333-3333-3333-3333-333333333333","versionId":"22222222-2222-2222-2222-222222222222","status":"VALIDATING"}`))
		case strings.HasPrefix(r.URL.Path, "/api/v1/artifacts/33333333-3333-3333-3333-333333333333") && r.Method == http.MethodGet:
			_, _ = w.Write([]byte(`{"id":"33333333-3333-3333-3333-333333333333","versionId":"22222222-2222-2222-2222-222222222222","status":"FAILED","fileName":"app.ipa","sha256":"abc","validation":{"status":"FAILED","attempts":1,"durationMs":5,"stages":[{"name":"FILE","status":"PASSED"},{"name":"SIGNING","status":"FAILED","code":"SIGNING_ABSENT","reason":"no provisioning profile"}]}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	t.Setenv(config.EnvAPIBase, server.URL)

	file := filepath.Join(t.TempDir(), "app.ipa")
	if err := os.WriteFile(file, []byte("fake-ipa"), 0o600); err != nil {
		t.Fatal(err)
	}

	root := NewRoot()
	root.SetArgs([]string{"artifact", "upload", file,
		"--version", "7.12.0", "--platform", "ios", "--git-commit", "abc123", "--timeout", "5s"})
	err := root.ExecuteContext(context.Background())
	if !uploadSeen {
		t.Fatal("upload never reached the server")
	}
	var failure *FailureError
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want FailureError", err)
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("exit code = %d, want %d", code, exitcode.Validation)
	}
}

func TestLoginStoresCredentialsAndVerifies(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv(config.EnvToken, "")
	t.Setenv(config.EnvAPIBase, "")

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/auth/me" {
			t.Errorf("path = %q", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer sdp_verify_me" {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"status":401,"code":"UNAUTHENTICATED","detail":"bad token"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"u-1","email":"dev@example.com","fullName":"Dev","orgId":"o-1","authenticatedVia":"PAT"}`))
	}))
	defer server.Close()

	root := NewRoot()
	root.SetArgs([]string{"login", "--api", server.URL, "--token", "sdp_verify_me"})
	var stdout bytes.Buffer
	root.SetOut(&stdout)
	if err := root.ExecuteContext(context.Background()); err != nil {
		t.Fatalf("login: %v", err)
	}

	token, err := config.LoadToken()
	if err != nil || token != "sdp_verify_me" {
		t.Fatalf("stored token = %q, %v", token, err)
	}
	info, err := os.Stat(filepath.Join(home, ".shipperdog", "credentials"))
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("credentials mode = %o", perm)
	}
	if base := config.ResolveAPIBase(""); base != server.URL {
		t.Errorf("saved api base = %q", base)
	}
}

func TestLoginRejectsBadTokenAsAuthExit(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"status":401,"code":"UNAUTHENTICATED"}`))
	}))
	defer server.Close()

	root := NewRoot()
	root.SetArgs([]string{"login", "--api", server.URL, "--token", "sdp_wrong"})
	err := root.ExecuteContext(context.Background())
	if code := ExitCode(err); code != exitcode.Auth {
		t.Errorf("exit code = %d, want auth", code)
	}
	if _, statErr := os.Stat(filepath.Join(home, ".shipperdog", "credentials")); !os.IsNotExist(statErr) {
		t.Error("rejected token must not be stored")
	}
}
