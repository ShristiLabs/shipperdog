package commands

import (
	"fmt"
	"os"
	"strings"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/output"
	"github.com/spf13/cobra"
)

// The gate verbs (issue #42, S10-03, FR-026): the CI-facing result
// submission #60 deferred to this story (its comment records the
// contract), the §32 gate-block read, and the governed override.
// The gate_type argument is the open vocabulary (the FR-026 built-ins
// - unit-tests, integration-tests, sast, dependency-scan,
// mobile-security-scan, coverage-threshold, crash-free-threshold,
// open-critical-bugs - or an integration-defined kind); the metric-
// backed kinds refuse submission because analytics owns their values.

func newGateCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "gate",
		Short: "Report and inspect release quality gates",
		Long: "The quality-gate surface (FR-026, PRD 32): submit a gate result from CI with\n" +
			"evidence (latest wins, history kept), read a release's gate block (passed,\n" +
			"failed, no-data fail-closed, or not-reported per gate, with thresholds and\n" +
			"evidence links), or override a blocking gate with a mandatory audited\n" +
			"reason. Gate types are an open vocabulary: the FR-026 built-ins above or\n" +
			"your integration's own kind. A failed or data-less required gate blocks the\n" +
			"release's READY crossing until passed or overridden.",
	}
	cmd.AddCommand(
		newGateResultCmd(),
		newGateListCmd(),
		newGateOverrideCmd())
	return cmd
}

func newGateResultCmd() *cobra.Command {
	var (
		appRef     string
		status     string
		evidence   string
		value      float64
		jsonOutput bool
	)
	cmd := &cobra.Command{
		Use:   "result <release> <gate>",
		Short: "Submit a gate result from CI (pass/fail + evidence URL)",
		Long: "POST /api/v1/releases/{id}/gates/{gate}/result (FR-022/FR-026): records a\n" +
			"pass or fail with the mandatory evidence URL (the report link a human can\n" +
			"follow) and the submitting caller's identity. Idempotent per gate+release\n" +
			"as latest-wins: re-running a gate updates the current state and appends\n" +
			"the history. Threshold/ceiling gates (coverage-threshold, and any custom\n" +
			"numeric kind) also require --value, the observed number rendered beside\n" +
			"the definition's threshold. Metric-backed gates (crash-free-threshold,\n" +
			"open-critical-bugs) refuse submission: their values resolve from\n" +
			"analytics at evaluation time, fail-closed until the feeds land (S11-04).",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			gate := api.NormalizeGateType(args[1])
			if gate == "" || !gateTypeWellFormed(gate) {
				return usagef("<gate> must be lowercase kebab (letters, digits, dashes), e.g. unit-tests (got %q)", args[1])
			}
			outcome := api.ValidGateOutcome(status)
			if outcome == "" {
				return usagef("--status must be pass or fail (got %q)", status)
			}
			evidence = strings.TrimSpace(evidence)
			if evidence == "" {
				return usagef("--evidence-url is required (the report link a human can follow)")
			}
			if !strings.HasPrefix(evidence, "http://") && !strings.HasPrefix(evidence, "https://") {
				return usagef("--evidence-url must be an http:// or https:// link (got %q)", evidence)
			}
			if api.GateIsMetricBacked(gate) {
				return usagef("gate %q is metric-backed: its value resolves from analytics at evaluation time (fail-closed until S11-04); CI cannot submit it", gate)
			}
			var observed *float64
			if cmd.Flags().Changed("value") {
				observed = &value
			}
			client, err := dial()
			if err != nil {
				return err
			}
			app, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			gateView, err := client.SubmitGateResult(cmd.Context(), app.ID, detail.ID, gate, outcome, evidence, observed)
			if err != nil {
				return transitionFailure("gate result for "+detail.ReleaseRef, err)
			}
			fmt.Fprintf(os.Stderr, "%s gate %s: %s\n", detail.ReleaseRef, gate, gateView.Status)
			if jsonOutput {
				return output.JSON(cmd.OutOrStdout(), gateView)
			}
			output.Line(cmd.OutOrStdout(), renderGate(gateView))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&status, "status", "", "reported outcome: pass or fail (required)")
	cmd.Flags().StringVar(&evidence, "evidence-url", "", "evidence/report URL, http(s) (required)")
	cmd.Flags().Float64Var(&value, "value", 0, "observed value of a threshold/ceiling gate (e.g. coverage 87.5; a 0 is legitimate)")
	cmd.Flags().BoolVar(&jsonOutput, "json", false, "machine-readable output (the gate view JSON)")
	_ = cmd.MarkFlagRequired("status")
	_ = cmd.MarkFlagRequired("evidence-url")
	return cmd
}

func newGateListCmd() *cobra.Command {
	var (
		appRef     string
		jsonOutput bool
	)
	cmd := &cobra.Command{
		Use:   "list <release>",
		Short: "Read a release's gate block (states, thresholds, evidence)",
		Long: "GET /api/v1/releases/{id}/gates (PRD 32): the release's quality-gate\n" +
			"block - every gate the release's ring expects (required first-class,\n" +
			"advisory marked) beside any reported results: PASSED with evidence,\n" +
			"FAILED (blocking), NO_DATA (metric-backed, fail-closed: analytics has\n" +
			"no data yet - distinct from a failure), or not reported yet. Numeric\n" +
			"gates render the observed value beside the definition's threshold\n" +
			"(coverage 87.5, threshold 80); overridden gates render their record.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := dial()
			if err != nil {
				return err
			}
			app, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			view, err := client.GetReleaseGates(cmd.Context(), app.ID, detail.ID)
			if err != nil {
				return err
			}
			if jsonOutput {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderReleaseGates(view))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().BoolVar(&jsonOutput, "json", false, "machine-readable output (the gates view JSON)")
	return cmd
}

func newGateOverrideCmd() *cobra.Command {
	var (
		appRef     string
		reason     string
		jsonOutput bool
	)
	cmd := &cobra.Command{
		Use:   "override <release> <gate>",
		Short: "Override a blocking gate with a mandatory audited reason",
		Long: "POST /api/v1/releases/{id}/gates/{gate}/override (FR-026): the governed\n" +
			"exception - a FAILED or NO_DATA gate stops blocking, exactly once, with\n" +
			"actor, mandatory reason, and moment recorded on the release and\n" +
			"surfaced on the dashboard. The gate keeps its recorded state; only the\n" +
			"override governs whether it still blocks. Requires the\n" +
			"app:releases:gate-override authority (release managers and org admins).",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			gate := api.NormalizeGateType(args[1])
			if gate == "" {
				return usagef("<gate> is required (got %q)", args[1])
			}
			trimmed := strings.TrimSpace(reason)
			if trimmed == "" {
				return usagef("--reason is mandatory (the override explains itself; it is audited)")
			}
			client, err := dial()
			if err != nil {
				return err
			}
			app, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			gateView, err := client.OverrideGate(cmd.Context(), app.ID, detail.ID, gate, trimmed)
			if err != nil {
				return transitionFailure("override of "+gate, err)
			}
			fmt.Fprintf(os.Stderr, "%s gate %s overridden (%s)\n", detail.ReleaseRef, gate, gateView.Status)
			if jsonOutput {
				return output.JSON(cmd.OutOrStdout(), gateView)
			}
			output.Line(cmd.OutOrStdout(), renderGate(gateView))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&reason, "reason", "", "the mandatory, audited override reason (required)")
	cmd.Flags().BoolVar(&jsonOutput, "json", false, "machine-readable output (the gate view JSON)")
	_ = cmd.MarkFlagRequired("reason")
	return cmd
}

// gateTypeWellFormed mirrors the server's canonical shape check.
func gateTypeWellFormed(gate string) bool {
	if len(gate) == 0 || len(gate) > 64 {
		return false
	}
	if gate[0] < 'a' || gate[0] > 'z' {
		return false
	}
	for _, r := range gate {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= '0' && r <= '9':
		case r == '-':
		default:
			return false
		}
	}
	return true
}

// renderGate is the human view of one gate after a submission or an
// override: the state, the evidence, and the override record.
func renderGate(g *api.ReleaseGate) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Gate %s: %s\n", g.GateType, gateStatusLabel(g))
	if g.EvidenceURL != "" {
		fmt.Fprintf(&b, "  Evidence: %s\n", g.EvidenceURL)
	}
	if g.ObservedValue != 0 || g.Threshold != 0 {
		fmt.Fprintf(&b, "  Value:     %v (%s %v)\n", g.ObservedValue, gateThresholdWord(g.GateType), g.Threshold)
	}
	if g.Overridden {
		fmt.Fprintf(&b, "  Overridden: by %s - %q (%s)\n", orDash(g.OverriddenBy), g.OverriddenReason, g.OverriddenAt)
	}
	return b.String()
}

// renderReleaseGates is the human view of the release's §32 gate
// block: the expected set with per-gate states, in the server's
// deterministic type order.
func renderReleaseGates(v *api.ReleaseGatesView) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Quality gates of %s (ring %s, status %s)\n", v.ReleaseRef, v.Ring, v.Status)
	if len(v.Gates) == 0 {
		fmt.Fprintf(&b, "  No gates required for this ring and none reported.\n")
		return b.String()
	}
	for i := range v.Gates {
		g := &v.Gates[i]
		kind := ""
		if !g.Required {
			kind = " (advisory)"
		}
		fmt.Fprintf(&b, "  %s%s: %s\n", g.GateType, kind, gateStatusLabel(g))
		if g.EvidenceURL != "" {
			fmt.Fprintf(&b, "    evidence: %s\n", g.EvidenceURL)
		}
		if g.Schema == "NUMERIC_MIN" || g.Schema == "COUNT_MAX" {
			fmt.Fprintf(&b, "    value: %v (%s %v)\n", g.ObservedValue, gateThresholdWord(g.GateType), g.Threshold)
		}
		if g.Source == "METRIC" {
			fmt.Fprintf(&b, "    source: analytics (fail-closed while no data)\n")
		}
		if g.Overridden {
			fmt.Fprintf(&b, "    overridden by %s - %q\n", orDash(g.OverriddenBy), g.OverriddenReason)
		}
	}
	return b.String()
}

// gateStatusLabel renders the state vocabulary, PENDING/empty both
// mapping to the honest not-reported phrase.
func gateStatusLabel(g *api.ReleaseGate) string {
	switch g.Status {
	case "PASSED":
		return "PASSED"
	case "FAILED":
		return "FAILED (blocking until passed or overridden)"
	case "NO_DATA":
		return "NO_DATA (fail-closed: no analytics data yet)"
	case "":
		return "not reported yet"
	}
	return g.Status
}

// gateThresholdWord names the expected result beside an observed value.
func gateThresholdWord(gateType string) string {
	if api.GateSchemaKind(gateType) == "COUNT_MAX" {
		return "max"
	}
	return "threshold"
}
