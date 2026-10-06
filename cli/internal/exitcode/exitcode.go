// Package exitcode defines the CLI's process exit-code contract.
//
// CI pipelines branch on these codes (issue #25: "Exit codes distinguish
// auth failure / validation failure / network"), so every value is
// documented in cli/README.md and must remain stable.
package exitcode

// Process exit codes.
const (
	// OK: the command succeeded.
	OK = 0
	// Server: unexpected internal error or a 5xx from the API.
	Server = 1
	// Usage: bad flags, missing arguments, unresolvable or ambiguous
	// name references, or a locally rejected request shape.
	Usage = 2
	// Auth: authentication failure - no stored credentials, or the API
	// answered 401.
	Auth = 3
	// Validation: the API rejected the request (a 4xx problem+json:
	// validation, conflict, too large, unsupported type, concealed
	// 404), or an uploaded artifact settled validation as FAILED.
	Validation = 4
	// Network: connection refused, DNS failure, or timeout reaching the
	// API.
	Network = 5
)
