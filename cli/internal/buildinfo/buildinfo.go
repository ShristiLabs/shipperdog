// Package buildinfo carries the version stamp injected at build time
// (-ldflags "-X ...buildinfo.Version=... -X ...buildinfo.Commit=...");
// source builds answer "dev".
package buildinfo

var (
	Version = "dev"
	Commit  = "unknown"
)

// String renders the --version answer.
func String() string {
	return Version + " (commit " + Commit + ")"
}
