package commands

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/output"
	"github.com/spf13/cobra"
)

func newArtifactCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "artifact",
		Short: "Upload and inspect artifact binaries",
	}
	cmd.AddCommand(newArtifactUploadCmd(), newArtifactListCmd(), newArtifactViewCmd(), newArtifactRevalidateCmd())
	return cmd
}

// platformFor normalizes --platform to the served UPPER_SNAKE value.
func platformFor(value string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "ios":
		return "IOS", nil
	case "android":
		return "ANDROID", nil
	default:
		return "", usagef("--platform must be ios or android (got %q)", value)
	}
}

// checkExtensionCoherence rejects locally what the server would reject
// with 415 ARTIFACT_FILE_TYPE_REJECTED - one round trip saved, and the
// CI log names the rule.
func checkExtensionCoherence(fileName, platform string) error {
	ext := strings.ToLower(filepath.Ext(fileName))
	switch platform {
	case "IOS":
		if ext != ".ipa" {
			return usagef("%q is not a .ipa file; the IOS platform accepts .ipa only (server policy ARTIFACT_FILE_TYPE_REJECTED)", fileName)
		}
	case "ANDROID":
		if ext != ".apk" && ext != ".aab" {
			return usagef("%q is not a .apk/.aab file; the ANDROID platform accepts .apk and .aab only (server policy ARTIFACT_FILE_TYPE_REJECTED)", fileName)
		}
	}
	return nil
}

// resolveVersionRef turns --version (name or uuid) plus optional --app
// into the application and version the operation targets. A version
// UUID needs no application context at all (the artifact surface is
// body-addressed by version id); a version name resolves within --app,
// or across every visible application when unambiguous (the issue's
// `--version 7.12.0` ergonomics).
func resolveVersionRef(ctx context.Context, client *api.Client, appRef, versionRef string) (*api.ApplicationView, *api.VersionView, error) {
	if appRef != "" {
		app, err := client.ResolveApplication(ctx, appRef)
		if err != nil {
			return nil, nil, err
		}
		version, err := client.ResolveVersion(ctx, app.ID, versionRef)
		if err != nil {
			return nil, nil, err
		}
		return app, version, nil
	}
	if api.LooksLikeUUID(versionRef) {
		return nil, &api.VersionView{ID: versionRef}, nil
	}
	return client.ResolveVersionAnywhere(ctx, versionRef)
}

func newArtifactUploadCmd() *cobra.Command {
	var (
		versionRef     string
		appRef         string
		platformFlag   string
		buildNumber    int64
		gitCommit      string
		gitBranch      string
		pipeline       string
		ciRunURL       string
		buildTimestamp string
		wait           bool
		noWait         bool
		timeout        time.Duration
		multipartLimit int64
		jsonOut        bool
	)
	cmd := &cobra.Command{
		Use:   "upload <file>",
		Short: "Upload an artifact binary (multipart, or resumable session above the threshold)",
		Long: "Uploads a .ipa/.apk/.aab binary to a version. Provenance flags map to the frozen\n" +
			"S5-04 fields (gitCommit, gitBranch, pipeline, ciRunUrl, buildTimestamp); all are\n" +
			"optional and never fabricated. Files larger than the resumable threshold (default\n" +
			"100 MiB, mirroring the server rule) stream direct to storage via an upload session.\n" +
			"By default the command then polls the artifact read until validation settles\n" +
			"(READY exits 0; FAILED exits 4 with the failing stage).",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			filePath := args[0]
			platform, err := platformFor(platformFlag)
			if err != nil {
				return err
			}
			if err := checkExtensionCoherence(filepath.Base(filePath), platform); err != nil {
				return err
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
			app, version, err := resolveVersionRef(ctx, client, appRef, versionRef)
			if err != nil {
				return err
			}

			var buildPtr *int64
			if cmd.Flags().Changed("build-number") {
				buildPtr = &buildNumber
			}
			opts := api.UploadOptions{
				VersionID:      version.ID,
				Platform:       platform,
				BuildNumber:    buildPtr,
				GitCommit:      gitCommit,
				GitBranch:      gitBranch,
				Pipeline:       pipeline,
				CiRunUrl:       ciRunURL,
				BuildTimestamp: buildTimestamp,
			}

			var view *api.ArtifactView
			if stat.Size() > multipartLimit {
				view, err = uploadViaSession(ctx, client, opts, filePath, stat.Size())
			} else {
				view, err = uploadViaMultipart(ctx, client, opts, filePath, stat.Size())
			}
			if err != nil {
				return err
			}
			target := version.VersionName
			if target == "" {
				target = version.ID
			}
			if app != nil {
				target = app.Name + " " + target
			}
			fmt.Fprintf(os.Stderr, "Uploaded %s as artifact %s to %s (status %s)\n",
				filepath.Base(filePath), view.ID, target, view.Status)

			if !wait || noWait {
				if jsonOut {
					return output.JSON(cmd.OutOrStdout(), view)
				}
				output.Line(cmd.OutOrStdout(), renderArtifact(view))
				return nil
			}
			return settleAndRender(ctx, client, view, timeout, jsonOut, cmd.OutOrStdout())
		},
	}
	cmd.Flags().StringVar(&versionRef, "version", "", "target version (name like 7.12.0, or uuid; names need --app unless unambiguous) (required)")
	cmd.Flags().StringVar(&appRef, "app", "", "target application (name or uuid)")
	cmd.Flags().StringVar(&platformFlag, "platform", "", "ios or android (required)")
	cmd.Flags().Int64Var(&buildNumber, "build-number", 0, "CI-pinned build number")
	cmd.Flags().StringVar(&gitCommit, "git-commit", "", "git commit (hex SHA-1/SHA-256)")
	cmd.Flags().StringVar(&gitBranch, "git-branch", "", "git branch (1-255 chars)")
	cmd.Flags().StringVar(&pipeline, "pipeline", "", "pipeline name (1-255 chars)")
	cmd.Flags().StringVar(&ciRunURL, "ci-run-url", "", "CI run URL (absolute http/https)")
	cmd.Flags().StringVar(&buildTimestamp, "build-timestamp", "", "build timestamp (ISO-8601 instant)")
	cmd.Flags().BoolVar(&wait, "wait", true, "poll until validation settles (READY/FAILED)")
	cmd.Flags().Lookup("wait").NoOptDefVal = "true"
	cmd.Flags().BoolVar(&noWait, "no-wait", false, "skip the settle poll; return right after registration")
	cmd.Flags().DurationVar(&timeout, "timeout", 2*time.Minute, "max wait for validation to settle")
	cmd.Flags().Int64Var(&multipartLimit, "multipart-limit", api.DefaultMultipartLimit, "bytes above which the resumable session path is used")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the artifact view JSON)")
	_ = cmd.MarkFlagRequired("version")
	_ = cmd.MarkFlagRequired("platform")
	return cmd
}

// uploadViaMultipart streams at/below the threshold.
func uploadViaMultipart(ctx context.Context, client *api.Client, opts api.UploadOptions, filePath string, size int64) (*api.ArtifactView, error) {
	progress := output.NewProgress("Uploading (multipart)", size)
	view, err := client.UploadMultipart(ctx, opts, filePath, progress.Reporter)
	if err == nil {
		progress.Finish()
	}
	return view, err
}

// uploadViaSession runs create-session, direct PUT, finalize.
func uploadViaSession(ctx context.Context, client *api.Client, opts api.UploadOptions, filePath string, size int64) (*api.ArtifactView, error) {
	fmt.Fprintln(os.Stderr, "Above the multipart threshold: using a resumable upload session")
	session, err := client.CreateUploadSession(ctx, api.CreateSessionRequest{
		VersionID:      opts.VersionID,
		Platform:       opts.Platform,
		FileName:       filepath.Base(filePath),
		BuildNumber:    opts.BuildNumber,
		GitCommit:      opts.GitCommit,
		GitBranch:      opts.GitBranch,
		Pipeline:       opts.Pipeline,
		CiRunUrl:       opts.CiRunUrl,
		BuildTimestamp: opts.BuildTimestamp,
		SizeHint:       size,
	})
	if err != nil {
		return nil, err
	}
	progress := output.NewProgress("Streaming to storage", size)
	if err := client.PutToStorage(ctx, session.UploadURL, filePath, size, progress.Reporter); err != nil {
		return nil, err
	}
	progress.Finish()
	final, err := client.Finalize(ctx, session.ArtifactID, opts.VersionID)
	if err != nil {
		return nil, err
	}
	if !final.Created {
		fmt.Fprintln(os.Stderr, "Finalize replayed the already-registered artifact (created=false)")
	}
	return final.Artifact, nil
}

// settleAndRender polls the read until the validation tier settles and
// renders the outcome. A settled-FAILED artifact exits 4 (the
// validation-failure class); a settle timeout exits 1 with the current
// state rendered.
func settleAndRender(ctx context.Context, client *api.Client, view *api.ArtifactView, timeout time.Duration, jsonOut bool, out io.Writer) error {
	lastNote := ""
	deadline := time.Now().Add(timeout)
	settled, err := client.WaitForSettle(ctx, view.ID, view.VersionID, time.Second, deadline, func(current *api.ArtifactView) {
		if current.Status != lastNote {
			lastNote = current.Status
			fmt.Fprintf(os.Stderr, "  status: %s\n", current.Status)
		}
	})
	if err != nil {
		return err
	}
	if settled == nil || !api.Settled(settled) {
		status := "UNKNOWN"
		if settled != nil {
			status = settled.Status
		}
		fmt.Fprintf(os.Stderr, "Timed out waiting for validation to settle (artifact %s still %s)\n", view.ID, status)
		fmt.Fprintln(os.Stderr, "Check later with: shipperdog artifact view "+view.ID+" --version "+view.VersionID)
		if settled != nil && jsonOut {
			return output.JSON(out, settled)
		}
		if settled != nil {
			output.Line(out, renderArtifact(settled))
		}
		return fmt.Errorf("validation did not settle within --timeout")
	}
	if jsonOut {
		if err := output.JSON(out, settled); err != nil {
			return err
		}
	} else {
		output.Line(out, renderArtifact(settled))
	}
	if settled.Status == "FAILED" {
		return &FailureError{msg: "artifact settled FAILED (see validation block above)"}
	}
	return nil
}

func newArtifactViewCmd() *cobra.Command {
	var (
		versionRef string
		appRef     string
		wait       bool
		timeout    time.Duration
		jsonOut    bool
	)
	cmd := &cobra.Command{
		Use:   "view <artifactId>",
		Short: "Read an artifact with its extraction and validation blocks",
		Long: "GET /api/v1/artifacts/{id}: the artifact read where the extraction outcome and\n" +
			"the validation block (per-stage status/code/reason in FR-006 order, attempts,\n" +
			"duration, format verdict) settle. With --wait, polls until settled; a settled\n" +
			"FAILED artifact exits 4 so scripts can branch.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !api.LooksLikeUUID(args[0]) {
				return usagef("artifactId must be a uuid (got %q)", args[0])
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			_, version, err := resolveVersionRef(ctx, client, appRef, versionRef)
			if err != nil {
				return err
			}
			view, err := client.GetArtifact(ctx, args[0], version.ID)
			if err != nil {
				return err
			}
			if wait && !api.Settled(view) {
				return settleAndRender(ctx, client, view, timeout, jsonOut, cmd.OutOrStdout())
			}
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), view)
			}
			output.Line(cmd.OutOrStdout(), renderArtifact(view))
			if view.Status == "FAILED" {
				return &FailureError{msg: "artifact is FAILED"}
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&versionRef, "version", "", "owning version (name or uuid) (required)")
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; needed when --version is a name)")
	cmd.Flags().BoolVar(&wait, "wait", false, "poll until validation settles")
	cmd.Flags().Lookup("wait").NoOptDefVal = "true"
	cmd.Flags().DurationVar(&timeout, "timeout", 2*time.Minute, "max wait with --wait")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output")
	_ = cmd.MarkFlagRequired("version")
	return cmd
}

func newArtifactRevalidateCmd() *cobra.Command {
	var (
		versionRef string
		appRef     string
		wait       bool
		noWait     bool
		timeout    time.Duration
		jsonOut    bool
	)
	cmd := &cobra.Command{
		Use:   "revalidate <artifactId>",
		Short: "Re-run validation on a FAILED or stalled artifact (202 async; polls to settle)",
		Long: "POST /api/v1/artifacts/{id}/revalidate: a FAILED artifact re-enters through\n" +
			"FAILED -> VALIDATING, a blocked validation re-drives, a blocked extraction\n" +
			"re-arms. 409 ARTIFACT_NOT_VALIDATABLE answers for anything the state machine\n" +
			"cannot move back (READY/DISTRIBUTED/ARCHIVED). With --wait (default), polls\n" +
			"the read until settled.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !api.LooksLikeUUID(args[0]) {
				return usagef("artifactId must be a uuid (got %q)", args[0])
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			_, version, err := resolveVersionRef(ctx, client, appRef, versionRef)
			if err != nil {
				return err
			}
			view, err := client.Revalidate(ctx, args[0], version.ID)
			if err != nil {
				return err
			}
			fmt.Fprintf(os.Stderr, "Revalidation scheduled (status %s)\n", view.Status)
			if !wait || noWait {
				if jsonOut {
					return output.JSON(cmd.OutOrStdout(), view)
				}
				output.Line(cmd.OutOrStdout(), renderArtifact(view))
				return nil
			}
			return settleAndRender(ctx, client, view, timeout, jsonOut, cmd.OutOrStdout())
		},
	}
	cmd.Flags().StringVar(&versionRef, "version", "", "owning version (name or uuid) (required)")
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; needed when --version is a name)")
	cmd.Flags().BoolVar(&wait, "wait", true, "poll until validation settles")
	cmd.Flags().Lookup("wait").NoOptDefVal = "true"
	cmd.Flags().BoolVar(&noWait, "no-wait", false, "skip the settle poll; return right after the 202")
	cmd.Flags().DurationVar(&timeout, "timeout", 2*time.Minute, "max wait with --wait")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output")
	_ = cmd.MarkFlagRequired("version")
	return cmd
}

func newArtifactListCmd() *cobra.Command {
	var (
		versionRef string
		include    string
		limit      int
		jsonOut    bool
	)
	cmd := &cobra.Command{
		Use:   "list [<app>]",
		Short: "List artifacts (tables or JSON), scoped to the caller's context",
		Long: "Walks the visible applications and their versions and renders one row per\n" +
			"artifact slot (the listing the API serves carries artifact status, format\n" +
			"verdict, and validation summary). An optional <app> argument (name or uuid)\n" +
			"narrows the walk; --version narrows to one version. Server-side visibility\n" +
			"decides what the caller sees - the CLI adds no client-side filtering.",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()

			var apps []*api.ApplicationView
			if len(args) == 1 {
				app, err := client.ResolveApplication(ctx, args[0])
				if err != nil {
					return err
				}
				apps = append(apps, app)
			} else {
				me, err := client.Me(ctx)
				if err != nil {
					return err
				}
				if me.OrgID == "" {
					return usagef("listing every application needs an organization context; pass an <app> argument")
				}
				listed, err := client.ListApplications(ctx, me.OrgID)
				if err != nil {
					return err
				}
				for i := range listed {
					apps = append(apps, &listed[i])
				}
			}

			includeFilter := include
			if includeFilter == "" {
				includeFilter = "archived,revoked"
			}
			type row struct {
				Application string `json:"application"`
				Version     string `json:"version"`
				Platform    string `json:"platform"`
				Status      string `json:"status"`
				Format      string `json:"format"`
				Build       string `json:"build"`
				Size        string `json:"size"`
				UploadedAt  string `json:"uploadedAt"`
				ID          string `json:"id"`
			}
			var rows []row
			for _, app := range apps {
				versions, err := client.ListVersions(ctx, app.ID, "", includeFilter)
				if err != nil {
					return err
				}
				for i := range versions {
					v := versions[i]
					if versionRef != "" && versionRef != v.VersionName && versionRef != v.ID {
						continue
					}
					platforms := make([]string, 0, len(v.ArtifactsByPlatform))
					for p := range v.ArtifactsByPlatform {
						platforms = append(platforms, p)
					}
					sort.Strings(platforms)
					for _, p := range platforms {
						for _, slot := range v.ArtifactsByPlatform[p] {
							build := ""
							if slot.BuildNumber != nil {
								build = fmt.Sprintf("%d", *slot.BuildNumber)
							}
							rows = append(rows, row{
								Application: app.Name,
								Version:     v.VersionName,
								Platform:    slot.Platform,
								Status:      slot.Status,
								Format:      slot.Format,
								Build:       build,
								Size:        output.HumanBytes(slot.Size),
								UploadedAt:  slot.UploadedAt,
								ID:          slot.ID,
							})
						}
					}
				}
			}
			if limit > 0 && len(rows) > limit {
				rows = rows[:limit]
			}
			out := cmd.OutOrStdout()
			if jsonOut {
				return output.JSON(out, rows)
			}
			if len(rows) == 0 {
				output.Line(out, "No artifacts found.")
				return nil
			}
			w := output.Table(out)
			output.Row(w, "APPLICATION", "VERSION", "PLATFORM", "STATUS", "FORMAT", "BUILD", "SIZE", "UPLOADED", "ID")
			for _, r := range rows {
				output.Row(w, r.Application, r.Version, r.Platform, r.Status, r.Format, r.Build, r.Size, r.UploadedAt, r.ID)
			}
			output.FlushTable(w)
			return nil
		},
	}
	cmd.Flags().StringVar(&versionRef, "version", "", "narrow to one version (name or uuid)")
	cmd.Flags().StringVar(&include, "include", "", "version window to include (default all: archived,revoked)")
	cmd.Flags().IntVar(&limit, "limit", 0, "cap rows (0 = uncapped within pagination bounds)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output")
	return cmd
}
