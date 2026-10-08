package commands

import (
	"bytes"
	"encoding/json"
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

// renderRelease is the human view of a release read: identity, the
// governed facts (ring, environment, artifacts, targets), and the
// honest E10-pending state of gates and approvals (S10-01/S10-03).
func renderRelease(d *api.ReleaseDetail) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Release %s (%s)\n", d.ReleaseRef, d.Status)
	fmt.Fprintf(&b, "  ID:          %s\n", d.ID)
	fmt.Fprintf(&b, "  Application: %s    Version: %s\n", d.ApplicationID, d.VersionID)
	if d.RingName != "" {
		fmt.Fprintf(&b, "  Ring:        %s (%s)\n", d.Ring, d.RingName)
	} else {
		fmt.Fprintf(&b, "  Ring:        %s\n", d.Ring)
	}
	if d.Environment != "" {
		fmt.Fprintf(&b, "  Environment: %s (%s)\n", d.Environment, orDash(d.EnvironmentName))
	} else {
		fmt.Fprintf(&b, "  Environment: -\n")
	}
	if len(d.Artifacts) > 0 {
		fmt.Fprintf(&b, "  Artifacts:   %s %s\n", d.Artifacts[0].Platform, d.Artifacts[0].ArtifactID)
		for _, slot := range d.Artifacts[1:] {
			fmt.Fprintf(&b, "               %s %s\n", slot.Platform, slot.ArtifactID)
		}
	}
	fmt.Fprintf(&b, "  Targets:     %d tenant(s)\n", len(d.Targets))
	for _, target := range d.Targets {
		groups := "tenant at large"
		if len(target.TesterGroupIDs) > 0 {
			groups = "groups " + strings.Join(target.TesterGroupIDs, ", ")
		}
		fmt.Fprintf(&b, "    %s: ring %s, %s\n", target.TenantID, orDash(target.Ring), groups)
	}
	fmt.Fprintf(&b, "  Gates:       run 'shipperdog gate list %s' (or see this get's gate block)\n", d.ReleaseRef)
	fmt.Fprintf(&b, "  Created:     %s by %s\n", d.CreatedAt, d.CreatedBy)
	fmt.Fprintf(&b, "  Updated:     %s\n", d.UpdatedAt)
	return b.String()
}

// renderReleaseManifest is the human view of the frozen manifest: the
// freeze facts and digest beside the stored PRD 16 document verbatim.
func renderReleaseManifest(v *api.ReleaseManifestView) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Manifest of %s (%s / %s)\n", v.ReleaseRef, v.ApplicationID, v.VersionID)
	fmt.Fprintf(&b, "  Release:  %s\n", v.ReleaseID)
	fmt.Fprintf(&b, "  SHA-256:  %s\n", v.Sha256)
	fmt.Fprintf(&b, "  Frozen:   %s by %s\n", v.FrozenAt, v.FrozenBy)
	if len(v.Manifest) > 0 {
		var doc bytes.Buffer
		if err := json.Indent(&doc, v.Manifest, "  ", "  "); err == nil {
			fmt.Fprintf(&b, "  Document:\n  %s\n", doc.String())
		} else {
			fmt.Fprintf(&b, "  Document: %s\n", string(v.Manifest))
		}
	}
	return b.String()
}
