// Package commands wires the CLI's command tree (login, logout,
// artifact upload/list/view/revalidate, version list) onto the api and
// config packages, and maps every failure onto the stable exit-code
// contract in cli/README.md.
package commands

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/config"
	"github.com/ShristiLabs/shipperdog/cli/internal/exitcode"
)

// UsageError marks locally-detected invocation problems (exit 2).
type UsageError struct{ msg string }

func (e *UsageError) Error() string { return e.msg }

func usagef(format string, args ...any) error {
	return &UsageError{msg: fmt.Sprintf(format, args...)}
}

// FailureError marks a completed-but-negative outcome the API reported
// (an artifact that settled validation FAILED) - exit 4, the
// CI-friendly validation-failure class.
type FailureError struct{ msg string }

func (e *FailureError) Error() string { return e.msg }

// ExitCode classifies err per the documented exit-code contract and
// prints the diagnostic to stderr. It is the single exit-decision point
// of the CLI.
func ExitCode(err error) int {
	if err == nil {
		return exitcode.OK
	}
	var usage *UsageError
	var failure *FailureError
	var resolution *api.ResolutionError
	var apiErr *api.APIError
	switch {
	case errors.As(err, &usage):
		fmt.Fprintf(os.Stderr, "Error: %s\n", usage.msg)
		fmt.Fprintln(os.Stderr, "Run with --help for usage.")
		return exitcode.Usage
	case errors.As(err, &failure):
		fmt.Fprintf(os.Stderr, "Error: %s\n", failure.msg)
		return exitcode.Validation
	case errors.As(err, &resolution):
		fmt.Fprintf(os.Stderr, "Error: %s\n", resolution.Error())
		return exitcode.Usage
	case errors.Is(err, config.ErrNoCredentials):
		fmt.Fprintf(os.Stderr, "Error: %s\n", config.ErrNoCredentials.Error())
		return exitcode.Auth
	case isCobraUsageError(err):
		// Cobra does not type its command-resolution errors; its
		// wording is stable, and cobra's own help/suggestion text
		// follows this line on stderr.
		fmt.Fprintf(os.Stderr, "Error: %s\n", err.Error())
		return exitcode.Usage
	case errors.As(err, &apiErr):
		fmt.Fprintf(os.Stderr, "Error: %s\n", apiErr.Error())
		if trace := apiErr.TraceID(); trace != "" {
			fmt.Fprintf(os.Stderr, "trace_id: %s\n", trace)
		}
		switch {
		case apiErr.Network:
			return exitcode.Network
		case apiErr.Problem.Status == 401:
			return exitcode.Auth
		case apiErr.Problem.Status >= 400 && apiErr.Problem.Status < 500:
			return exitcode.Validation
		default:
			return exitcode.Server
		}
	default:
		fmt.Fprintf(os.Stderr, "Error: %s\n", err.Error())
		return exitcode.Server
	}
}

// isCobraUsageError recognizes cobra's untyped command-resolution
// failures (unknown command / required subcommand) so they land in the
// usage exit class like every other invocation mistake. Flag errors
// are already typed via the root's FlagErrorFunc.
func isCobraUsageError(err error) bool {
	msg := err.Error()
	return strings.HasPrefix(msg, "unknown command ") ||
		strings.HasPrefix(msg, "unknown shorthand flag ") ||
		strings.Contains(msg, "for one of its subcommands")
}
