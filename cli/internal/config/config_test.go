package config

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func isolate(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv(EnvAPIBase, "")
	t.Setenv(EnvToken, "")
	return home
}

func TestSaveTokenFilePermissions(t *testing.T) {
	isolate(t)
	if err := SaveToken("sdp_example_token"); err != nil {
		t.Fatalf("SaveToken: %v", err)
	}
	info, err := os.Stat(filepath.Join(homeDir(t), ".shipperdog", "credentials"))
	if err != nil {
		t.Fatalf("stat credentials: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("credentials mode = %o, want 600", perm)
	}
	dir, err := os.Stat(filepath.Join(homeDir(t), ".shipperdog"))
	if err != nil {
		t.Fatalf("stat dir: %v", err)
	}
	if perm := dir.Mode().Perm(); perm != 0o700 {
		t.Errorf("config dir mode = %o, want 700", perm)
	}
}

// A pre-existing world-readable credentials file must be tightened, not
// inherited.
func TestSaveTokenTightensExistingFile(t *testing.T) {
	isolate(t)
	dir := filepath.Join(homeDir(t), ".shipperdog")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "credentials")
	if err := os.WriteFile(path, []byte("old\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := SaveToken("sdp_new"); err != nil {
		t.Fatalf("SaveToken: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("mode after save = %o, want 600", perm)
	}
}

func TestLoadAndClearToken(t *testing.T) {
	isolate(t)
	if _, err := LoadToken(); !errors.Is(err, ErrNoCredentials) {
		t.Errorf("empty store: got %v, want ErrNoCredentials", err)
	}
	if err := SaveToken("sdp_token_value"); err != nil {
		t.Fatal(err)
	}
	got, err := LoadToken()
	if err != nil || got != "sdp_token_value" {
		t.Fatalf("LoadToken = %q, %v", got, err)
	}
	if err := ClearToken(); err != nil {
		t.Fatalf("ClearToken: %v", err)
	}
	if _, err := LoadToken(); !errors.Is(err, ErrNoCredentials) {
		t.Errorf("after logout: got %v, want ErrNoCredentials", err)
	}
	// Logout is idempotent.
	if err := ClearToken(); err != nil {
		t.Errorf("second ClearToken: %v", err)
	}
}

func TestAPIBasePrecedence(t *testing.T) {
	isolate(t)
	if got := ResolveAPIBase(""); got != DefaultAPIBase {
		t.Errorf("default = %q, want %q", got, DefaultAPIBase)
	}
	if err := SaveAPIBase("http://config:9000/"); err != nil {
		t.Fatal(err)
	}
	if got := ResolveAPIBase(""); got != "http://config:9000" {
		t.Errorf("config = %q", got)
	}
	t.Setenv(EnvAPIBase, "http://env:8081/")
	if got := ResolveAPIBase(""); got != "http://env:8081" {
		t.Errorf("env = %q", got)
	}
	if got := ResolveAPIBase("http://flag:1"); got != "http://flag:1" {
		t.Errorf("flag = %q", got)
	}
}

func TestResolveTokenPrecedence(t *testing.T) {
	isolate(t)
	if err := SaveToken("sdp_file_token"); err != nil {
		t.Fatal(err)
	}
	got, err := ResolveToken("")
	if err != nil || got != "sdp_file_token" {
		t.Fatalf("file token = %q, %v", got, err)
	}
	t.Setenv(EnvToken, "sdp_env_token")
	got, _ = ResolveToken("")
	if got != "sdp_env_token" {
		t.Errorf("env should win over file, got %q", got)
	}
	got, _ = ResolveToken("sdp_flag_token")
	if got != "sdp_flag_token" {
		t.Errorf("flag should win, got %q", got)
	}
}

func TestSaveTokenRefusesEmpty(t *testing.T) {
	isolate(t)
	if err := SaveToken("   "); err == nil {
		t.Error("empty token accepted")
	}
}

func homeDir(t *testing.T) string {
	t.Helper()
	home, err := os.UserHomeDir()
	if err != nil {
		t.Fatal(err)
	}
	return home
}
