package commands

import (
	"fmt"
	"os"
	"strings"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/output"
	"github.com/spf13/cobra"
)

// The OTA verbs (S16-14, issue #176, ADR-0010 workstream 10, PRD 20):
// the scripted path from a bundle archive to a governed staged rollout.
// create answers the draft plus its direct-to-storage grant, upload
// streams the bytes and lands the signed manifest, promote attaches
// and commences the release's basis-point rollout ladder, halt/resume
// are the audited operator actions (D10), status reads the plan and its
// timeline, funnel reads the dashboard's device funnel, and token
// issue mints the sdc1. device identifier exactly once (D3).

func newOtaCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "ota",
		Short: "Author OTA bundles and drive their staged rollouts",
		Long: "The OTA surface (ADR-0010): create a bundle draft and stream its bytes to\n" +
			"the granted storage URL, finalize into the signed manifest, attach a\n" +
			"cumulative basis-point rollout ladder to a live OTA release (promote),\n" +
			"halt/resume the rollout as audited operator actions, read the plan's\n" +
			"standing and timeline (status), the device funnel (funnel), and mint\n" +
			"the application's sdc1. device token (token issue - the value prints\n" +
			"exactly once). JSON output everywhere for pipelines.",
	}
	cmd.AddCommand(
		newOtaCreateCmd(),
		newOtaUploadCmd(),
		newOtaPromoteCmd(),
		newOtaHaltCmd(),
		newOtaResumeCmd(),
		newOtaStatusCmd(),
		newOtaFunnelCmd(),
		newOtaTokenCmd())
	return cmd
}

func newOtaCreateCmd() *cobra.Command {
	var (
		platformTag string
		fileName    string
		forced      bool
		minAppVer   string
		sizeHint    int64
		jsonOut     bool
	)
	cmd := &cobra.Command{
		Use:   "create <app> <bundleVersion>",
		Short: "Create an OTA bundle draft with its upload grant (UPLOADING)",
		Long: "POST /api/v1/ota/bundles (ADR-0010 D2/D5/D7): creates the bundle's\n" +
			"dedicated versions row (label = the semver-triple bundleVersion) and\n" +
			"grants the direct-to-storage session for the bundle's bytes. The answer\n" +
			"carries the bundle id (the pre-allocated artifact id - the bytes ARE the\n" +
			"bundle), the version id, and the upload URL with its expiry: copy the\n" +
			"uploadUrl and the ids into `ota upload` next. --forced and\n" +
			"--min-app-version are the per-bundle defaults devices compare against\n" +
			"(overridable per release target at release time). Nothing is listable\n" +
			"until finalize lands the SIGNED row.",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			tag := strings.TrimSpace(platformTag)
			if tag == "" {
				return usagef("--platform-tag is required (the open-vocabulary platform tag, 1-32 chars)")
			}
			name := strings.TrimSpace(fileName)
			if name == "" {
				return usagef("--file-name is required (the bare file name of the bundle archive)")
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			app, err := client.ResolveApplication(ctx, args[0])
			if err != nil {
				return err
			}
			var forcedPtr *bool
			if cmd.Flags().Changed("forced") {
				forcedPtr = &forced
			}
			draft, err := client.CreateOtaBundle(ctx, api.CreateOtaBundleRequest{
				ApplicationID: app.ID,
				BundleVersion: strings.TrimSpace(args[1]),
				PlatformTag:   tag,
				FileName:      name,
				Forced:        forcedPtr,
				MinAppVersion: strings.TrimSpace(minAppVer),
				SizeHint:      sizeHint,
			})
			if err != nil {
				return err
			}
			fmt.Fprintf(os.Stderr, "Bundle draft %s created for %s (status %s)\n", draft.ID, app.Name, draft.Status)
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), draft)
			}
			output.Line(cmd.OutOrStdout(), renderOtaDraft(draft))
			return nil
		},
	}
	cmd.Flags().StringVar(&platformTag, "platform-tag", "", "open-vocabulary platform tag, 1-32 chars (e.g. rn, flutter, electron) (required)")
	cmd.Flags().StringVar(&fileName, "file-name", "", "bare file name of the bundle archive (required)")
	cmd.Flags().BoolVar(&forced, "forced", false, "force-install default for devices (D7 per-bundle default)")
	cmd.Flags().StringVar(&minAppVer, "min-app-version", "", "minimum app version that may apply this bundle")
	cmd.Flags().Int64Var(&sizeHint, "size-hint", 0, "expected bundle size in bytes (optional)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the draft view JSON)")
	_ = cmd.MarkFlagRequired("platform-tag")
	_ = cmd.MarkFlagRequired("file-name")
	return cmd
}

func newOtaUploadCmd() *cobra.Command {
	var (
		bundleID  string
		versionID string
		uploadURL string
		jsonOut   bool
	)
	cmd := &cobra.Command{
		Use:   "upload <file>",
		Short: "Stream the bundle's bytes to the granted URL, then finalize and sign",
		Long: "The second half of authoring (ADR-0010 D5/D8): streams the archive\n" +
			"direct to the storage URL `ota create` granted (as-is, without the API\n" +
			"bearer header - the grant embeds its own authority), then POST\n" +
			"/api/v1/ota/bundles/{id}/finalize: the artifact port hashes the stored\n" +
			"bytes back, the manifest lands (sha256, sizeBytes, platformTag), and\n" +
			"the checksum is signed under the app's active Ed25519 key (generated\n" +
			"now if this is the app's first bundle). The answer is the SIGNED\n" +
			"bundle; a replay answers created=false with the same row.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			filePath := args[0]
			if !api.LooksLikeUUID(bundleID) {
				return usagef("--bundle must be the bundle uuid `ota create` answered (got %q)", bundleID)
			}
			if !api.LooksLikeUUID(versionID) {
				return usagef("--version must be the version uuid `ota create` answered (got %q)", versionID)
			}
			trimmedURL := strings.TrimSpace(uploadURL)
			if trimmedURL == "" {
				return usagef("--url is required (the uploadUrl of `ota create`'s grant)")
			}
			stat, err := os.Stat(filePath)
			if err != nil {
				return usagef("reading %s: %v", filePath, err)
			}
			if stat.IsDir() {
				return usagef("%s is a directory", filePath)
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			progress := output.NewProgress("Streaming bundle to storage", stat.Size())
			if err := client.PutToStorage(ctx, trimmedURL, filePath, stat.Size(), progress.Reporter); err != nil {
				return err
			}
			progress.Finish()
			final, err := client.FinalizeOtaBundle(ctx, bundleID, versionID)
			if err != nil {
				return err
			}
			if !final.Created {
				fmt.Fprintln(os.Stderr, "Finalize replayed the already-registered bundle (created=false)")
			}
			if final.Bundle != nil {
				fmt.Fprintf(os.Stderr, "Bundle %s is %s (key %s)\n", final.Bundle.ID, final.Bundle.Status, final.Bundle.KeyID)
			}
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), final)
			}
			if final.Bundle != nil {
				output.Line(cmd.OutOrStdout(), renderOtaBundle(final.Bundle))
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&bundleID, "bundle", "", "bundle uuid from `ota create` (required)")
	cmd.Flags().StringVar(&versionID, "version", "", "the draft's dedicated version uuid from `ota create` (required)")
	cmd.Flags().StringVar(&uploadURL, "url", "", "the uploadUrl of `ota create`'s grant (required)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the finalize view JSON)")
	_ = cmd.MarkFlagRequired("bundle")
	_ = cmd.MarkFlagRequired("version")
	_ = cmd.MarkFlagRequired("url")
	return cmd
}

func newOtaPromoteCmd() *cobra.Command {
	var (
		appRef  string
		mode    string
		stages  string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "promote <release>",
		Short: "Attach and commence the release's staged rollout plan (born RUNNING)",
		Long: "POST /api/v1/ota/rollouts (ADR-0010 D6): attaches the cumulative\n" +
			"basis-point ladder to a live (ACTIVE or PROMOTED) OTA release and\n" +
			"COMMENCES it - stage 1 reached, the plan RUNNING, a COMMENCED timeline\n" +
			"row. One plan per release, ever. --stages is the strictly ascending\n" +
			"comma-separated ladder whose final stage is 10000 (the full fleet);\n" +
			"each entry may carry an operator note as bp=note. --mode picks the\n" +
			"advancement discipline: AUTOMATIC consults the policy engine between\n" +
			"stages, CHECKPOINT makes every advance an operator's explicit\n" +
			"approval (advance/pause live in the console; halt/resume ride this\n" +
			"CLI). Devices resolve membership deterministically on the fly - the\n" +
			"bucket never retreats. Requires app:releases:manage.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			normalizedMode := api.NormalizeOtaMode(mode)
			if !api.ValidOtaMode(normalizedMode) {
				return usagef("--mode must be one of %s (got %q)", strings.Join(api.OtaModes(), ", "), mode)
			}
			ladder, err := api.ParseOtaStages(stages)
			if err != nil {
				return usagef("%s", err.Error())
			}
			client, err := dial()
			if err != nil {
				return err
			}
			app, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			view, err := client.AttachOtaRollout(cmd.Context(), api.AttachOtaRolloutRequest{
				ApplicationID: app.ID,
				ReleaseID:     detail.ID,
				Mode:          normalizedMode,
				Stages:        ladder,
			})
			if err != nil {
				return transitionFailure("attach rollout for "+detail.ReleaseRef, err)
			}
			fmt.Fprintf(os.Stderr, "%s rollout commenced (%s, %d stages)\n", detail.ReleaseRef, view.Mode, len(view.Stages))
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderOtaRollout(view))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&mode, "mode", "", "advancement discipline: AUTOMATIC or CHECKPOINT (required)")
	cmd.Flags().StringVar(&stages, "stages", "", "cumulative basis-point ladder, strictly ascending, final 10000 (e.g. 1000,5000,10000; notes as bp=note) (required)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the rollout view JSON)")
	_ = cmd.MarkFlagRequired("mode")
	_ = cmd.MarkFlagRequired("stages")
	return cmd
}

func newOtaHaltCmd() *cobra.Command {
	var (
		appRef  string
		reason  string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "halt <release>",
		Short: "Halt the rollout (the manual, audit-trailed stop; reason mandatory)",
		Long: "POST /api/v1/ota/rollouts/{releaseId}/halt (ADR-0010 D10): the manual,\n" +
			"audit-trailed stop - the reason is mandatory, the plan stands HALTED,\n" +
			"and the device check answers no-update while it does. A paused plan is\n" +
			"still stoppable; nothing halts on its own, ever. Resume with\n" +
			"`ota resume`. Requires app:releases:manage.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			trimmedReason := strings.TrimSpace(reason)
			if trimmedReason == "" {
				return usagef("--reason is mandatory (the halt explains itself)")
			}
			client, err := dial()
			if err != nil {
				return err
			}
			app, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			view, err := client.HaltOtaRollout(cmd.Context(), app.ID, detail.ID, trimmedReason)
			if err != nil {
				return transitionFailure("halt rollout of "+detail.ReleaseRef, err)
			}
			fmt.Fprintf(os.Stderr, "%s rollout HALTED\n", detail.ReleaseRef)
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderOtaRollout(view))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&reason, "reason", "", "why the rollout stops (mandatory; lands on the audit trail) (required)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the rollout view JSON)")
	_ = cmd.MarkFlagRequired("reason")
	return cmd
}

func newOtaResumeCmd() *cobra.Command {
	var (
		appRef  string
		reason  string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "resume <release>",
		Short: "Resume a HALTED or PAUSED rollout (serving what it had reached)",
		Long: "POST /api/v1/ota/rollouts/{releaseId}/resume: from HALTED or PAUSED\n" +
			"back to RUNNING - serving exactly what the plan had reached, because\n" +
			"the ladder never retreats and resuming widens nothing by itself.\n" +
			"Requires app:releases:manage.",
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
			view, err := client.ResumeOtaRollout(cmd.Context(), app.ID, detail.ID, strings.TrimSpace(reason))
			if err != nil {
				return transitionFailure("resume rollout of "+detail.ReleaseRef, err)
			}
			fmt.Fprintf(os.Stderr, "%s rollout %s\n", detail.ReleaseRef, view.Status)
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderOtaRollout(view))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&reason, "reason", "", "resume note (optional; lands on the audit trail)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the rollout view JSON)")
	return cmd
}

func newOtaStatusCmd() *cobra.Command {
	var (
		appRef  string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "status <release>",
		Short: "Read the rollout plan: standing, ladder, and append-only timeline",
		Long: "GET /api/v1/ota/rollouts/{releaseId}: the plan's mode and standing\n" +
			"(RUNNING, HALTED, PAUSED, COMPLETE), every stage of the ladder with its\n" +
			"cumulative basis-point threshold and reach moment, and the append-only\n" +
			"operator timeline (commenced/halted/resumed/advanced). Requires\n" +
			"app:releases:read.",
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
			view, err := client.GetOtaRollout(cmd.Context(), app.ID, detail.ID)
			if err != nil {
				return err
			}
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderOtaRollout(view))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the rollout view JSON)")
	return cmd
}

func newOtaFunnelCmd() *cobra.Command {
	var (
		appRef  string
		ring    string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "funnel <bundle>",
		Short: "Read the bundle's device funnel (checks, seen, downloads, applied)",
		Long: "GET /api/v1/tenants/{tenantId}/dashboard/ota/bundles/{bundleId}/funnel\n" +
			"(ADR-0010 D9): the dashboard funnel of one bundle - the server-counted\n" +
			"checks beside the device-reported lifecycle (seen, download, applied,\n" +
			"failed, rolled back), the failure-reason breakdown, and the adoption\n" +
			"curve over time. --app names the owning application (the dashboard\n" +
			"route is tenant-addressed; the application carries the tenant and the\n" +
			"permission target). <bundle> is the bundle uuid or its bundleVersion\n" +
			"label. --ring narrows to one ring's devices.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if appRef == "" {
				return usagef("--app is required (the funnel route is tenant-addressed; the application carries the tenant)")
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			app, err := client.ResolveApplication(ctx, appRef)
			if err != nil {
				return err
			}
			if app.TenantID == "" {
				return &FailureError{msg: fmt.Sprintf("application %s carries no tenant context for the dashboard funnel", app.ID)}
			}
			bundle, err := client.ResolveOtaBundle(ctx, app, args[0])
			if err != nil {
				return err
			}
			view, err := client.GetOtaFunnel(ctx, app.TenantID, bundle.ID, strings.TrimSpace(ring))
			if err != nil {
				return err
			}
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderOtaFunnel(view, ring))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid) (required)")
	cmd.Flags().StringVar(&ring, "ring", "", "narrow the funnel to one ring's devices")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the funnel view JSON)")
	_ = cmd.MarkFlagRequired("app")
	return cmd
}

func newOtaTokenCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "token",
		Short: "Mint and manage the application's sdc1. device tokens",
		Long: "The sdc1. app-token surface (ADR-0010 D3): the identifier the shipped\n" +
			"binary presents at /api/v1/ota/check. The value prints EXACTLY ONCE -\n" +
			"the registry stores only its SHA-256 - so capture it in the issue\n" +
			"moment.",
	}
	cmd.AddCommand(newOtaTokenIssueCmd())
	return cmd
}

func newOtaTokenIssueCmd() *cobra.Command {
	var (
		name    string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "issue <app>",
		Short: "Issue a sdc1. app token (the value prints exactly once)",
		Long: "POST /api/v1/ota/app-tokens (ADR-0010 D3): mints the application's\n" +
			"sdc1. device identifier - the value the shipped binary embeds for\n" +
			"/api/v1/ota/check. The plain output is the token VALUE ALONE on stdout\n" +
			"(capture it: TOKEN=$(shipperdog ota token issue App --name prod); the\n" +
			"registry stores only its SHA-256 and the value never leaves the server\n" +
			"again). --name is the human label the registry distinguishes tokens by\n" +
			"(1-200 chars). Revocation lives in the console.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			label := strings.TrimSpace(name)
			if label == "" {
				return usagef("--name is required (the human label the registry distinguishes tokens by, 1-200 chars)")
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			app, err := client.ResolveApplication(ctx, args[0])
			if err != nil {
				return err
			}
			issued, err := client.IssueOtaAppToken(ctx, app.ID, label)
			if err != nil {
				return err
			}
			if issued.Token != nil {
				fmt.Fprintf(os.Stderr, "App token %s (%s) issued for %s - the value prints once:\n", issued.Token.ID, issued.Token.Name, app.Name)
			}
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), issued)
			}
			// The value alone on stdout: scripts capture it directly, and it
			// never appears anywhere else (registry rows carry no value).
			output.Line(cmd.OutOrStdout(), issued.Value)
			return nil
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "human label for the registry (1-200 chars) (required)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the issued view JSON - carries the value once)")
	_ = cmd.MarkFlagRequired("name")
	return cmd
}

// renderOtaDraft is the human view of the create answer: the draft's
// identity beside the upload grant the next step consumes.
func renderOtaDraft(d *api.OtaDraftView) string {
	var b strings.Builder
	fmt.Fprintf(&b, "OTA bundle %s (%s)\n", d.ID, d.Status)
	fmt.Fprintf(&b, "  Bundle version: %s    Platform tag: %s\n", d.BundleVersion, d.PlatformTag)
	fmt.Fprintf(&b, "  Application:    %s    Version row: %s\n", d.ApplicationID, d.VersionID)
	if d.Forced {
		fmt.Fprintf(&b, "  Forced:         true\n")
	}
	if d.MinAppVersion != "" {
		fmt.Fprintf(&b, "  Min app version: %s\n", d.MinAppVersion)
	}
	if d.Upload != nil {
		fmt.Fprintf(&b, "  Upload grant (expires %s):\n", d.Upload.ExpiresAt)
		fmt.Fprintf(&b, "    uploadUrl: %s\n", d.Upload.UploadURL)
		fmt.Fprintf(&b, "  Next: shipperdog ota upload <file> --bundle %s --version %s --url <uploadUrl>\n", d.ID, d.VersionID)
	}
	return b.String()
}

// renderOtaBundle is the human view of the SIGNED bundle: the landed
// manifest facts beside the signature the SDK verifies.
func renderOtaBundle(v *api.OtaBundleView) string {
	var b strings.Builder
	fmt.Fprintf(&b, "OTA bundle %s (%s)\n", v.ID, v.Status)
	fmt.Fprintf(&b, "  Bundle version: %s    Platform tag: %s\n", v.BundleVersion, v.PlatformTag)
	fmt.Fprintf(&b, "  Size:           %s    SHA-256: %s\n", output.HumanBytes(v.SizeBytes), v.Sha256)
	fmt.Fprintf(&b, "  Signature key:  %s\n", v.KeyID)
	if v.Signature != "" {
		fmt.Fprintf(&b, "  Signature:      %s\n", v.Signature)
	}
	if v.Forced {
		fmt.Fprintf(&b, "  Forced:         true\n")
	}
	if v.MinAppVersion != "" {
		fmt.Fprintf(&b, "  Min app version: %s\n", v.MinAppVersion)
	}
	if v.ReleaseID != "" {
		fmt.Fprintf(&b, "  Release:        %s\n", v.ReleaseID)
	}
	fmt.Fprintf(&b, "  Created:        %s by %s\n", v.CreatedAt, v.CreatedBy)
	return b.String()
}

// renderOtaRollout is the human view of the plan: standing, the ladder
// with each stage's reach state, and the operator timeline.
func renderOtaRollout(v *api.OtaRolloutView) string {
	var b strings.Builder
	fmt.Fprintf(&b, "OTA rollout of release %s: %s (%s)\n", v.ReleaseID, v.Status, v.Mode)
	fmt.Fprintf(&b, "  Application: %s    Stage: %d/%d\n", v.ApplicationID, v.CurrentStage, len(v.Stages))
	if len(v.Stages) > 0 {
		fmt.Fprintf(&b, "  Ladder (cumulative basis points):\n")
		for _, stage := range v.Stages {
			reached := orDash(stage.ReachedAt)
			note := ""
			if stage.Note != "" {
				note = " - " + stage.Note
			}
			fmt.Fprintf(&b, "    %d. %5d  %-8s reached %s%s\n", stage.Position, stage.BasisPoints, stage.Status, reached, note)
		}
	}
	if len(v.Timeline) > 0 {
		fmt.Fprintf(&b, "  Timeline:\n")
		for _, event := range v.Timeline {
			fmt.Fprintf(&b, "    %s %s at %s by %s\n", event.Action, event.Detail, event.OccurredAt, event.ActorID)
		}
	}
	return b.String()
}

// renderOtaFunnel is the human view of the dashboard funnel: the
// stage counters with their conversion shares, the failure-reason
// breakdown, and the adoption curve.
func renderOtaFunnel(v *api.OtaFunnelView, ring string) string {
	var b strings.Builder
	scope := v.BundleVersion
	if scope == "" {
		scope = v.BundleID
	}
	if v.ReleaseRef != "" {
		scope = scope + " (" + v.ReleaseRef + ")"
	}
	if strings.TrimSpace(ring) != "" {
		scope = scope + " [ring " + strings.TrimSpace(ring) + "]"
	}
	fmt.Fprintf(&b, "OTA funnel of %s\n", scope)
	if v.Stages == nil {
		fmt.Fprintf(&b, "  No funnel data yet.\n")
		return b.String()
	}
	s := v.Stages
	fmt.Fprintf(&b, "  checks:       %8d\n", s.Checks)
	fmt.Fprintf(&b, "  seen:         %8d (%s of checks)\n", s.Seen, shareOf(s.Seen, s.Checks))
	fmt.Fprintf(&b, "  downloads:    %8d (%s of seen)\n", s.Downloads, shareOf(s.Downloads, s.Seen))
	fmt.Fprintf(&b, "  applied:      %8d (%s of downloads)\n", s.Applied, shareOf(s.Applied, s.Downloads))
	fmt.Fprintf(&b, "  failed:       %8d\n", s.Failed)
	fmt.Fprintf(&b, "  rolled back:  %8d\n", s.RolledBack)
	if len(v.Failures) > 0 {
		fmt.Fprintf(&b, "  Failure reasons:\n")
		for _, failure := range v.Failures {
			fmt.Fprintf(&b, "    %-24s %8d\n", failure.Reason, failure.Count)
		}
	}
	if len(v.Adoption) > 0 {
		fmt.Fprintf(&b, "  Adoption (seen -> applied per day):\n")
		for _, point := range v.Adoption {
			fmt.Fprintf(&b, "    %-12s %8d seen %8d applied %6.1f%%\n", point.Day, point.SeenDevices, point.AppliedDevices, point.AdoptionRate*100)
		}
	}
	return b.String()
}

// shareOf renders the conversion share of part over whole ("42.9%"),
// or "-" when the denominator is zero.
func shareOf(part, whole int64) string {
	if whole <= 0 {
		return "-"
	}
	return fmt.Sprintf("%.1f%%", float64(part)/float64(whole)*100)
}
