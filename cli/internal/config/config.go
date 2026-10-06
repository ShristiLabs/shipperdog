// Package config owns the CLI's on-disk state under ~/.shipperdog:
// the personal access token (credentials, 0600) and the remembered API
// base URL (config, 0600, no secrets).
//
// The PAT lives in a 0600 file rather than an OS keychain (the ADR-0004
// trade-off: keychain access needs cgo or OS-specific libraries, which
// would break the single static cross-compiled binary).
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// DefaultAPIBase is the local docker compose API endpoint.
const DefaultAPIBase = "http://localhost:8080"

// EnvAPIBase and EnvToken override the on-disk state (CI-friendly).
const (
	EnvAPIBase = "SHIPPERDOG_API"
	EnvToken   = "SHIPPERDOG_TOKEN"
)

// ErrNoCredentials is returned when no PAT is stored and none was
// supplied - the caller should point the user at `shipperdog login`.
var ErrNoCredentials = errors.New("no stored credentials; run `shipperdog login` or set SHIPPERDOG_TOKEN")

// Dir returns ~/.shipperdog, created with 0700 when missing.
func Dir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("locating home directory: %w", err)
	}
	dir := filepath.Join(home, ".shipperdog")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", fmt.Errorf("creating %s: %w", dir, err)
	}
	return dir, nil
}

// CredentialsPath returns the PAT file path (~/.shipperdog/credentials).
func CredentialsPath() (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "credentials"), nil
}

// LoadToken reads the stored PAT. Missing file yields ErrNoCredentials.
func LoadToken() (string, error) {
	path, err := CredentialsPath()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", ErrNoCredentials
		}
		return "", fmt.Errorf("reading %s: %w", path, err)
	}
	token := strings.TrimSpace(string(data))
	if token == "" {
		return "", ErrNoCredentials
	}
	return token, nil
}

// SaveToken writes the PAT with 0600 permissions (AC: the credentials
// file is never world-readable). An existing file's mode is re-forced
// in case anything loosened it.
func SaveToken(token string) error {
	if strings.TrimSpace(token) == "" {
		return errors.New("refusing to store an empty token")
	}
	path, err := CredentialsPath()
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, []byte(token+"\n"), 0o600); err != nil {
		return fmt.Errorf("writing %s: %w", path, err)
	}
	// Belt and braces: WriteFile does not tighten an existing file's mode.
	if err := os.Chmod(path, 0o600); err != nil {
		return fmt.Errorf("chmod %s: %w", path, err)
	}
	return nil
}

// ClearToken removes the stored PAT (logout). A missing file is success.
func ClearToken() error {
	path, err := CredentialsPath()
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("removing %s: %w", path, err)
	}
	return nil
}

// SaveAPIBase remembers the API endpoint the token was verified against.
// The config file carries no secrets but keeps 0600 for uniformity.
func SaveAPIBase(base string) error {
	dir, err := Dir()
	if err != nil {
		return err
	}
	path := filepath.Join(dir, "config")
	line := "api_base=" + strings.TrimSpace(base) + "\n"
	if err := os.WriteFile(path, []byte(line), 0o600); err != nil {
		return fmt.Errorf("writing %s: %w", path, err)
	}
	return os.Chmod(path, 0o600)
}

// LoadAPIBase returns the remembered API base URL, if present.
func LoadAPIBase() (string, bool) {
	dir, err := Dir()
	if err != nil {
		return "", false
	}
	data, err := os.ReadFile(filepath.Join(dir, "config"))
	if err != nil {
		return "", false
	}
	for _, ln := range strings.Split(string(data), "\n") {
		if value, ok := strings.CutPrefix(ln, "api_base="); ok {
			value = strings.TrimSpace(value)
			if value != "" {
				return value, true
			}
		}
	}
	return "", false
}

// ResolveAPIBase applies the precedence flag > env > config > default.
func ResolveAPIBase(flagValue string) string {
	if flagValue != "" {
		return strings.TrimRight(flagValue, "/")
	}
	if env := os.Getenv(EnvAPIBase); env != "" {
		return strings.TrimRight(env, "/")
	}
	if saved, ok := LoadAPIBase(); ok {
		return strings.TrimRight(saved, "/")
	}
	return DefaultAPIBase
}

// ResolveToken applies the precedence flag > env > credentials file.
func ResolveToken(flagValue string) (string, error) {
	if flagValue != "" {
		return strings.TrimSpace(flagValue), nil
	}
	if env := os.Getenv(EnvToken); env != "" {
		return strings.TrimSpace(env), nil
	}
	return LoadToken()
}
