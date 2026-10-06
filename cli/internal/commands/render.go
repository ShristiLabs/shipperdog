package commands

import (
	"fmt"
	"strings"
	"text/tabwriter"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/output"
)

// renderArtifact is the human view of an artifact read: identity
// fields, the extraction tier, the validation block with per-stage
// outcomes in FR-006 order, provenance, and duplicate hints.
func renderArtifact(v *api.ArtifactView) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Artifact %s\n", v.ID)
	line := &b
	fmt.Fprintf(line, "  Status:     %s", v.Status)
	if v.Format != "" {
		fmt.Fprintf(line, "  (format %s)", v.Format)
	}
	fmt.Fprintln(line)
	fmt.Fprintf(&b, "  Platform:   %s    Version: %s\n", v.Platform, v.VersionID)
	fmt.Fprintf(&b, "  File:       %s (%s)\n", v.FileName, output.HumanBytes(v.Size))
	fmt.Fprintf(&b, "  SHA-256:    %s\n", v.Sha256)
	fmt.Fprintf(&b, "  Uploaded:   %s by %s\n", v.UploadedAt, v.UploadedBy)
	if v.BuildNumber != nil || v.BundleID != "" || v.VersionName != "" || v.MinimumOS != "" {
		fmt.Fprintf(&b, "  Build:      %s  Bundle: %s  Version: %s  MinOS: %s\n",
			orDash(int64String(v.BuildNumber)), orDash(v.BundleID), orDash(v.VersionName), orDash(v.MinimumOS))
	}
	if v.Extraction != nil {
		fmt.Fprintf(&b, "  Extraction: %s", v.Extraction.Status)
		if v.Extraction.ErrorCode != "" {
			fmt.Fprintf(&b, "  (%s: %s)", v.Extraction.ErrorCode, v.Extraction.ErrorMessage)
		}
		fmt.Fprintln(&b)
	}
	if v.Validation != nil {
		attempts := ""
		if v.Validation.Attempts > 0 {
			attempts = fmt.Sprintf("attempt %d, %d ms", v.Validation.Attempts, v.Validation.DurationMs)
		}
		fmt.Fprintf(&b, "  Validation: %s (%s)\n", v.Validation.Status, attempts)
		w := tabwriter.NewWriter(&b, 0, 4, 2, ' ', 0)
		for _, stage := range v.Validation.Stages {
			detail := ""
			switch stage.Status {
			case "FAILED":
				detail = stage.Code + " - " + stage.Reason
			case "SKIPPED":
				detail = ""
			default:
				if stage.Reason != "" {
					detail = stage.Reason
				}
			}
			output.Row(w, "    "+stage.Name, stage.Status, detail)
		}
		_ = w.Flush()
	}
	if v.Provenance != nil {
		parts := []string{}
		if v.Provenance.GitCommit != "" {
			parts = append(parts, "gitCommit="+v.Provenance.GitCommit)
		}
		if v.Provenance.GitBranch != "" {
			parts = append(parts, "gitBranch="+v.Provenance.GitBranch)
		}
		if v.Provenance.Pipeline != "" {
			parts = append(parts, "pipeline="+v.Provenance.Pipeline)
		}
		if v.Provenance.CiRunUrl != "" {
			parts = append(parts, "ciRunUrl="+v.Provenance.CiRunUrl)
		}
		if v.Provenance.BuildTimestamp != "" {
			parts = append(parts, "buildTimestamp="+v.Provenance.BuildTimestamp)
		}
		if len(parts) > 0 {
			fmt.Fprintf(&b, "  Provenance: %s\n", strings.Join(parts, " "))
		}
	}
	if len(v.DuplicateOf) > 0 {
		fmt.Fprintf(&b, "  Duplicates: %d identical artifact(s) elsewhere in this application", len(v.DuplicateOf))
		if v.DuplicateOfTruncated {
			b.WriteString(" (list truncated)")
		}
		b.WriteString("\n")
	}
	return b.String()
}

func orDash(value string) string {
	if value == "" {
		return "-"
	}
	return value
}

func int64String(v *int64) string {
	if v == nil {
		return ""
	}
	return fmt.Sprintf("%d", *v)
}
