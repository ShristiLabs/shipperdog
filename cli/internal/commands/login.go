package commands

import (
	"bufio"
	"fmt"
	"os"
	"strings"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/buildinfo"
	"github.com/ShristiLabs/shipperdog/cli/internal/config"
	"github.com/ShristiLabs/shipperdog/cli/internal/output"
	"github.com/spf13/cobra"
	"golang.org/x/term"
)

// newLoginCmd: verify a personal access token against /auth/me and
// store it (AC: PAT in ~/.shipperdog/credentials, 0600).
func newLoginCmd() *cobra.Command {
	var tokenFlag string
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "login",
		Short: "Store a personal access token after verifying it",
		Long: "Verifies a personal access token (PAT) against GET /api/v1/auth/me and\n" +
			"stores it in ~/.shipperdog/credentials with 0600 permissions. The token is\n" +
			"read from --token, then " + config.EnvToken + ", then an interactive hidden\n" +
			"prompt (or one piped stdin line for scripts). PATs are minted via\n" +
			"POST /api/v1/auth/tokens and shown exactly once.",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			token := tokenFlag
			if token == "" && os.Getenv(config.EnvToken) != "" {
				token = os.Getenv(config.EnvToken)
			}
			if token == "" {
				prompted, err := readTokenPrompt("Personal access token (sdp_...): ")
				if err != nil {
					return err
				}
				token = prompted
			}
			token = strings.TrimSpace(token)
			if token == "" {
				return usagef("no token provided; pass --token, set %s, or pipe one on stdin", config.EnvToken)
			}

			base := config.ResolveAPIBase(apiBaseFlag)
			client := api.New(base, token)
			me, err := client.Me(cmd.Context())
			if err != nil {
				return fmt.Errorf("token rejected by %s: %w", base, err)
			}

			if err := config.SaveAPIBase(base); err != nil {
				return err
			}
			if err := config.SaveToken(token); err != nil {
				return err
			}

			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), map[string]any{"endpoint": base, "actor": me})
			}
			out := cmd.OutOrStdout()
			output.Printf(out, "Logged in as %s <%s> (user %s", me.FullName, me.Email, me.ID)
			if me.OrgID != "" {
				output.Printf(out, ", organization %s", me.OrgID)
			}
			output.Printf(out, ")\nEndpoint: %s\nCredentials: ~/.shipperdog/credentials (0600)\n", base)
			output.Printf(out, "CLI version: %s\n", buildinfo.String())
			return nil
		},
	}
	cmd.Flags().StringVar(&tokenFlag, "token", "", "personal access token (prefer the prompt or "+config.EnvToken+")")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output")
	return cmd
}

// readTokenPrompt reads a token without echo when stdin is a terminal;
// scripts may pipe one line instead.
func readTokenPrompt(prompt string) (string, error) {
	if term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprint(os.Stderr, prompt)
		secret, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", fmt.Errorf("reading token: %w", err)
		}
		return string(secret), nil
	}
	fmt.Fprint(os.Stderr, prompt)
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return "", fmt.Errorf("reading token from stdin: %w", err)
	}
	return strings.TrimSpace(line), nil
}

// newLogoutCmd clears the stored PAT (AC: logout clears it).
func newLogoutCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "logout",
		Short: "Remove the stored credentials",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := config.ClearToken(); err != nil {
				return err
			}
			output.Line(cmd.OutOrStdout(), "Credentials removed (~/.shipperdog/credentials).")
			return nil
		},
	}
}
