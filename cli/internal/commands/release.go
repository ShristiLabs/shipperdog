package commands

import (
	"context"
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/output"
	"github.com/spf13/cobra"
)

// The release verbs (issue #60, S9-06, PRD 20 + FR-015/FR-017): the
// scripted path from a build artifact to a promoted release. Creation
// assembles the governed distribution event (targets, ring, artifacts),
// get/list read it back, transition drives PRD 17's machine edge by
// edge, and promote walks the machine to PROMOTED - stopping at the
// first refusal so gate/approval blockers (E10's policies on these
// same edges) surface with their reasons and a non-zero exit.

func newReleaseCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "release",
		Short: "Create, inspect, and drive releases",
		Long: "The release surface (PRD 17/20): create a release from a version's READY\n" +
			"artifacts targeting tenants/groups on a ring, read it back by quotable id\n" +
			"(R-1028), drive the state machine edge by edge with transition, or walk it\n" +
			"to PROMOTED with promote. JSON output everywhere for pipelines.",
	}
	cmd.AddCommand(
		newReleaseCreateCmd(),
		newReleaseListCmd(),
		newReleaseGetCmd(),
		newReleaseTransitionCmd(),
		newReleasePromoteCmd(),
		newReleaseManifestCmd())
	return cmd
}

// splitListFlag flattens repeatable comma-separated flag values.
func splitListFlag(values []string) []string {
	var out []string
	for _, value := range values {
		for _, part := range strings.Split(value, ",") {
			part = strings.TrimSpace(part)
			if part != "" {
				out = append(out, part)
			}
		}
	}
	return out
}

// requireUUIDList validates that every entry is a uuid - the API's ids
// are uuids, and a precise local message beats a server 400.
func requireUUIDList(flagName string, values []string) ([]string, error) {
	for _, value := range values {
		if !api.LooksLikeUUID(value) {
			return nil, usagef("%s accepts uuids only (got %q)", flagName, value)
		}
	}
	return values, nil
}

// parseGroupSpecs turns --groups entries into per-tenant group sets.
// Two forms: "tenantId=groupId[,groupId...]" scopes the groups to one
// target tenant; a bare "groupId[,groupId...]" applies to every
// --tenants entry (the server still validates that each group belongs
// to each target tenant - the 422 names any that do not).
func parseGroupSpecs(specs []string) (map[string][]string, []string, error) {
	scoped := make(map[string][]string)
	var bare []string
	for _, spec := range specs {
		if tenantID, groupList, found := strings.Cut(spec, "="); found {
			tenantID = strings.TrimSpace(tenantID)
			if !api.LooksLikeUUID(tenantID) {
				return nil, nil, usagef("--groups tenant prefix must be a uuid (got %q)", tenantID)
			}
			ids, err := requireUUIDList("--groups", splitListFlag([]string{groupList}))
			if err != nil {
				return nil, nil, err
			}
			scoped[tenantID] = append(scoped[tenantID], ids...)
			continue
		}
		ids, err := requireUUIDList("--groups", splitListFlag([]string{spec}))
		if err != nil {
			return nil, nil, err
		}
		bare = append(bare, ids...)
	}
	return scoped, bare, nil
}

// parseArtifactSpecs turns --artifact "platform=artifactId" entries
// into the platform-keyed override set. Platform values normalize to
// the served UPPER_SNAKE vocabulary.
func parseArtifactSpecs(specs []string) (map[string]string, error) {
	overrides := make(map[string]string)
	for _, spec := range specs {
		platform, artifactID, found := strings.Cut(spec, "=")
		if !found {
			return nil, usagef("--artifact expects platform=artifactId (got %q)", spec)
		}
		platform = strings.ToUpper(strings.TrimSpace(platform))
		if platform != "IOS" && platform != "ANDROID" {
			return nil, usagef("--artifact platform must be ios or android (got %q)", platform)
		}
		artifactID = strings.TrimSpace(artifactID)
		if !api.LooksLikeUUID(artifactID) {
			return nil, usagef("--artifact id must be a uuid (got %q)", artifactID)
		}
		overrides[platform] = artifactID
	}
	return overrides, nil
}

func newReleaseCreateCmd() *cobra.Command {
	var (
		ringFlag      string
		tenantsFlags  []string
		groupsFlags   []string
		artifactFlags []string
		environment   string
		jsonOut       bool
	)
	cmd := &cobra.Command{
		Use:   "create <app> <version>",
		Short: "Create a release from a version's READY artifacts (DRAFT)",
		Long: "POST /api/v1/releases (FR-015): assembles the distribution event in one\n" +
			"governed act - the version, one READY artifact per platform, the target\n" +
			"tenants with optional tester-group narrowing, and the starting ring\n" +
			"(FR-017: engineering, qa, tenant-pilot, uat, production until the org\n" +
			"customizes). Artifacts are selected automatically (newest READY per\n" +
			"platform); --artifact platform=id overrides one platform. The release is\n" +
			"born DRAFT; drive it with transition/promote. The quotable id (R-…) is\n" +
			"printed on every later view. Validation errors mirror the API's own\n" +
			"codes - including the per-tenant 422 RELEASE_TARGETS_INVALID reason list.",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			ring := strings.ToLower(strings.TrimSpace(ringFlag))
			if ring == "" {
				return usagef("--ring is required (FR-017 system rings: engineering, qa, tenant-pilot, uat, production)")
			}
			tenants, err := requireUUIDList("--tenants", splitListFlag(tenantsFlags))
			if err != nil {
				return err
			}
			if len(tenants) == 0 {
				return usagef("at least one --tenants uuid is required (an empty tester-group set targets the tenant at large)")
			}
			seen := make(map[string]bool)
			for _, tenantID := range tenants {
				if seen[tenantID] {
					return usagef("--tenants lists %s twice; target tenants must be distinct", tenantID)
				}
				seen[tenantID] = true
			}
			scopedGroups, bareGroups, err := parseGroupSpecs(groupsFlags)
			if err != nil {
				return err
			}
			for scopedTenant := range scopedGroups {
				if !seen[scopedTenant] {
					return usagef("--groups scopes %s, but that tenant is not in --tenants; a group cannot widen the target set", scopedTenant)
				}
			}
			overrides, err := parseArtifactSpecs(artifactFlags)
			if err != nil {
				return err
			}
			if environment != "" && !api.LooksLikeUUID(environment) {
				return usagef("--environment must be a uuid (got %q)", environment)
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
			version, err := client.ResolveVersion(ctx, app.ID, args[1])
			if err != nil {
				return err
			}

			artifacts := api.SelectReleaseArtifacts(version, overrides)
			if len(artifacts) == 0 {
				return usagef("version %s has no READY artifacts to release; upload with `shipperdog artifact upload` first (server policy RELEASE_ARTIFACT_NOT_READY)", version.ID)
			}

			targets := make([]api.ReleaseTargetCommand, 0, len(tenants))
			for _, tenantID := range tenants {
				groups := append([]string{}, bareGroups...)
				groups = append(groups, scopedGroups[tenantID]...)
				targets = append(targets, api.ReleaseTargetCommand{TenantID: tenantID, TesterGroupIDs: groups})
			}

			detail, err := client.CreateRelease(ctx, api.CreateReleaseRequest{
				ApplicationID: app.ID,
				VersionID:     version.ID,
				Ring:          ring,
				EnvironmentID: environment,
				Artifacts:     artifacts,
				Targets:       targets,
			})
			if err != nil {
				return err
			}
			target := version.VersionName
			if target == "" {
				target = version.ID
			}
			fmt.Fprintf(os.Stderr, "Release %s created for %s %s (status %s)\n",
				detail.ReleaseRef, app.Name, target, detail.Status)
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), detail)
			}
			output.Line(cmd.OutOrStdout(), renderRelease(detail))
			return nil
		},
	}
	cmd.Flags().StringVar(&ringFlag, "ring", "", "starting ring code (FR-017 system rings: engineering, qa, tenant-pilot, uat, production) (required)")
	cmd.Flags().StringSliceVar(&tenantsFlags, "tenants", nil, "target tenant uuids (comma-separated, repeatable; distinct) (required)")
	cmd.Flags().StringSliceVar(&groupsFlags, "groups", nil, "tester-group uuids: tenantId=group[,group...] to scope, or bare group[,group...] applying to every tenant (optional; omit to target tenants at large)")
	cmd.Flags().StringSliceVar(&artifactFlags, "artifact", nil, "explicit artifact per platform (platform=artifactId, repeatable); default: newest READY per platform")
	cmd.Flags().StringVar(&environment, "environment", "", "target environment uuid (optional, S9-04; every target tenant must sit inside its assignments)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the release detail JSON)")
	_ = cmd.MarkFlagRequired("ring")
	_ = cmd.MarkFlagRequired("tenants")
	return cmd
}

func newReleaseListCmd() *cobra.Command {
	var (
		versionRef string
		status     string
		tenantID   string
		limit      int
		jsonOut    bool
	)
	cmd := &cobra.Command{
		Use:   "list [<app>]",
		Short: "List releases (tables or JSON), newest first per application",
		Long: "GET /api/v1/releases?applicationId=…: newest (quotable number) first with\n" +
			"the server's conjunctive filters - --version (name or uuid, resolved to the\n" +
			"server's versionId filter), --status, --tenant. Without an <app> argument\n" +
			"the walk covers every application visible to the caller.",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if status != "" && !api.ValidReleaseState(api.NormalizeReleaseState(status)) {
				return usagef("--status must be one of %s (got %q)", strings.Join(api.ReleaseStates(), ", "), status)
			}
			if tenantID != "" && !api.LooksLikeUUID(tenantID) {
				return usagef("--tenant must be a uuid (got %q)", tenantID)
			}
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
					return usagef("listing every application's releases needs an organization context; pass an <app> argument")
				}
				listed, err := client.ListApplications(ctx, me.OrgID)
				if err != nil {
					return err
				}
				for i := range listed {
					apps = append(apps, &listed[i])
				}
			}

			type row struct {
				Application string `json:"application"`
				ReleaseRef  string `json:"releaseRef"`
				Status      string `json:"status"`
				Ring        string `json:"ring"`
				Environment string `json:"environment"`
				Targets     int    `json:"targetCount"`
				Version     string `json:"versionId"`
				CreatedAt   string `json:"createdAt"`
				ID          string `json:"id"`
			}
			var rows []row
			for _, app := range apps {
				versionID := ""
				if versionRef != "" {
					version, err := client.ResolveVersion(ctx, app.ID, versionRef)
					if err != nil {
						if isNotVisibleInApp(err) {
							continue
						}
						return err
					}
					versionID = version.ID
				}
				releases, err := client.ListReleases(ctx, app.ID, versionID, api.NormalizeReleaseState(status), tenantID)
				if err != nil {
					return err
				}
				for _, release := range releases {
					rows = append(rows, row{
						Application: app.Name,
						ReleaseRef:  release.ReleaseRef,
						Status:      release.Status,
						Ring:        release.Ring,
						Environment: release.Environment,
						Targets:     release.TargetCount,
						Version:     release.VersionID,
						CreatedAt:   release.CreatedAt,
						ID:          release.ID,
					})
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
				output.Line(out, "No releases found.")
				return nil
			}
			w := output.Table(out)
			output.Row(w, "APPLICATION", "REF", "STATUS", "RING", "ENVIRONMENT", "TARGETS", "CREATED", "ID")
			for _, r := range rows {
				output.Row(w, r.Application, r.ReleaseRef, r.Status, r.Ring, orDash(r.Environment),
					fmt.Sprintf("%d", r.Targets), r.CreatedAt, r.ID)
			}
			output.FlushTable(w)
			return nil
		},
	}
	cmd.Flags().StringVar(&versionRef, "version", "", "narrow to one version (name or uuid)")
	cmd.Flags().StringVar(&status, "status", "", "narrow to one PRD 17 status")
	cmd.Flags().StringVar(&tenantID, "tenant", "", "narrow to releases targeting this tenant uuid")
	cmd.Flags().IntVar(&limit, "limit", 0, "cap rows (0 = uncapped within pagination bounds)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output")
	return cmd
}

// isNotVisibleInApp reports the version-resolution misses that merely
// mean "no such version here" while walking applications.
func isNotVisibleInApp(err error) bool {
	var resolution *api.ResolutionError
	if errors.As(err, &resolution) {
		return true
	}
	var apiErr *api.APIError
	return errors.As(err, &apiErr) && !apiErr.Network && apiErr.Problem.Status == 404
}

// resolveReleaseFor is the shared <ref> [--app] resolution of the
// release-addressed commands (quotable id, bare number, or uuid).
func resolveReleaseFor(ctx context.Context, client *api.Client, appRef, releaseRef string) (*api.ApplicationView, *api.ReleaseDetail, error) {
	return client.ResolveRelease(ctx, appRef, releaseRef)
}

func newReleaseGetCmd() *cobra.Command {
	var (
		appRef  string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "get <release>",
		Short: "Read a release (status, artifacts, targets, ring)",
		Long: "GET /api/v1/releases/{id}: the full creation record - status, per-platform\n" +
			"artifact selection, the complete target set, ring and environment. Gates\n" +
			"render their honest not-yet-reported state until the S10-03 gate subsystem\n" +
			"lands; approvals are live since S10-02 (issue #41) - the transition\n" +
			"refusal names pending approvals, and the console renders the rounds.\n" +
			"<release> is the quotable id (R-1028, r1028, 1028) or a\n" +
			"uuid; --app (name or uuid) skips the cross-application lookup. --json emits\n" +
			"the release detail verbatim for pipeline decisions.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := dial()
			if err != nil {
				return err
			}
			_, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), detail)
			}
			output.Line(cmd.OutOrStdout(), renderRelease(detail))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the release detail JSON)")
	return cmd
}

func newReleaseTransitionCmd() *cobra.Command {
	var (
		appRef  string
		reason  string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "transition <release> <to>",
		Short: "Drive one PRD 17 state-machine crossing",
		Long: "POST /api/v1/releases/{id}/transitions (PRD 17): the machine's edges only -\n" +
			"DRAFT->VALIDATING->READY->APPROVAL_PENDING->APPROVED->DEPLOYING->ACTIVE,\n" +
			"ACTIVE->PROMOTED->COMPLETED, the failure edges to FAILED, and\n" +
			"ACTIVE->ROLLED_BACK. --reason is mandatory entering FAILED or ROLLED_BACK\n" +
			"(the release explains itself). Illegal crossings answer 409 with the\n" +
			"current and attempted states; policy refusals (E10) answer 422 with the\n" +
			"contributor's reason - both render their full reason list.",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			to := api.NormalizeReleaseState(args[1])
			if !api.ValidReleaseState(to) {
				return usagef("<to> must be one of %s (got %q)", strings.Join(api.ReleaseStates(), ", "), args[1])
			}
			trimmedReason := strings.TrimSpace(reason)
			if api.ReasonRequiredState(to) && trimmedReason == "" {
				return usagef("--reason is mandatory when entering %s (the release explains itself)", to)
			}
			client, err := dial()
			if err != nil {
				return err
			}
			app, detail, err := resolveReleaseFor(cmd.Context(), client, appRef, args[0])
			if err != nil {
				return err
			}
			detail, err = client.TransitionRelease(cmd.Context(), app.ID, detail.ID, to, trimmedReason)
			if err != nil {
				return transitionFailure("transition to "+to, err)
			}
			fmt.Fprintf(os.Stderr, "%s crossed into %s\n", detail.ReleaseRef, detail.Status)
			if jsonOut {
				return output.JSON(cmd.OutOrStdout(), detail)
			}
			output.Line(cmd.OutOrStdout(), renderRelease(detail))
			return nil
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&reason, "reason", "", "crossing reason (mandatory entering FAILED or ROLLED_BACK)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the refreshed detail)")
	return cmd
}

func newReleasePromoteCmd() *cobra.Command {
	var (
		appRef  string
		ring    string
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "promote <release>",
		Short: "Walk the state machine to PROMOTED, stopping at the first blocker",
		Long: "The CI showcase (PRD 20/32): drives the release forward through PRD 17's\n" +
			"edges - VALIDATING, READY, APPROVAL_PENDING, APPROVED (the manifest freeze),\n" +
			"DEPLOYING, ACTIVE - to PROMOTED, one crossing at a time. Any refusal stops\n" +
			"the walk: the blocker's code, detail, and full reason list print (the 409\n" +
			"names the current and attempted states; E10's approval and gate policies\n" +
			"will refuse edges through the same reason list) and the command exits\n" +
			"non-zero. --ring asserts the release's starting ring (M2 releases keep\n" +
			"the ring fixed at creation; ring-to-ring promotion arrives with S10-05).\n" +
			"A release already PROMOTED or COMPLETED is a success no-op; FAILED and\n" +
			"ROLLED_BACK are terminal and refuse.",
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
			if ring != "" {
				normalized := strings.ToLower(strings.TrimSpace(ring))
				if normalized != detail.Ring {
					return usagef("release %s starts on ring %q, not %q; M2 releases keep their creation ring (ring-to-ring promotion arrives with S10-05)", detail.ReleaseRef, detail.Ring, normalized)
				}
				fmt.Fprintf(os.Stderr, "Ring confirmed: %s\n", detail.Ring)
			}
			for {
				switch {
				case detail.Status == "PROMOTED" || detail.Status == "COMPLETED":
					fmt.Fprintf(os.Stderr, "%s is %s; nothing to promote\n", detail.ReleaseRef, detail.Status)
					if jsonOut {
						return output.JSON(cmd.OutOrStdout(), detail)
					}
					output.Line(cmd.OutOrStdout(), renderRelease(detail))
					return nil
				case api.ReleaseTerminalState(detail.Status):
					return &FailureError{msg: fmt.Sprintf("release %s is %s (terminal); a failed or pulled release never re-enters the machine - create a new release of a corrected version", detail.ReleaseRef, detail.Status)}
				}
				next := api.NextReleaseStateTowardPromoted(detail.Status)
				if next == "" {
					return fmt.Errorf("no forward path from status %s (unexpected state)", detail.Status)
				}
				from := detail.Status
				fmt.Fprintf(os.Stderr, "  %s -> %s\n", from, next)
				detail, err = client.TransitionRelease(cmd.Context(), app.ID, detail.ID, next, "")
				if err != nil {
					return transitionFailure("promote past "+from, err)
				}
			}
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().StringVar(&ring, "ring", "", "assert the release's starting ring code (the PRD 20 example's flag; mismatch refuses)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the final detail JSON)")
	return cmd
}

// transitionFailure converts a refused crossing into the CI-friendly
// validation failure (exit 4): the problem's code and detail plus every
// entry of the errors member as a listed reason - the shape the 422
// target/policy families and the 409 current/attempted family share.
func transitionFailure(what string, err error) error {
	var apiErr *api.APIError
	if errors.As(err, &apiErr) && !apiErr.Network && apiErr.Problem.Status >= 400 && apiErr.Problem.Status < 500 {
		var b strings.Builder
		fmt.Fprintf(&b, "%s refused: %s", what, apiErr.Problem.Error())
		if len(apiErr.Problem.Errors) > 0 {
			b.WriteString("\nreasons:")
			for _, key := range sortedKeys(apiErr.Problem.Errors) {
				fmt.Fprintf(&b, "\n  %s: %s", key, apiErr.Problem.Errors[key])
			}
		}
		return &FailureError{msg: b.String()}
	}
	return err
}

// sortedKeys renders a problem's errors member deterministically.
func sortedKeys(m map[string]string) []string {
	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func newReleaseManifestCmd() *cobra.Command {
	var (
		appRef  string
		yamlOut bool
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "manifest <release>",
		Short: "Read the frozen release manifest (JSON, or YAML with --yaml)",
		Long: "GET /api/v1/releases/{id}/manifest (PRD 16): the immutable record of what\n" +
			"the release ships, frozen at the APPROVED crossing and never recomputed -\n" +
			"artifact pins by content (sha256, build, size, filename), targets, ring,\n" +
			"environment configuration snapshot, and the canonical sha256 digest.\n" +
			"--yaml emits the compliance export (application/yaml) verbatim. A release\n" +
			"that has not crossed into APPROVED answers the honest 404\n" +
			"RELEASE_MANIFEST_NOT_FOUND.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if yamlOut && jsonOut {
				return usagef("--yaml and --json are mutually exclusive")
			}
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			app, detail, err := resolveReleaseFor(ctx, client, appRef, args[0])
			if err != nil {
				return err
			}
			format := ""
			if yamlOut {
				format = "yaml"
			}
			view, raw, err := client.GetReleaseManifest(ctx, app.ID, detail.ID, format)
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			switch {
			case yamlOut:
				output.Line(out, strings.TrimRight(string(raw), "\n"))
				return nil
			case jsonOut:
				return output.JSON(out, view)
			default:
				output.Line(out, renderReleaseManifest(view))
				return nil
			}
		},
	}
	cmd.Flags().StringVar(&appRef, "app", "", "owning application (name or uuid; skips the lookup walk)")
	cmd.Flags().BoolVar(&yamlOut, "yaml", false, "emit the manifest as YAML (the compliance export)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output (the manifest view JSON)")
	return cmd
}
