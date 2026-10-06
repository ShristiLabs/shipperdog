package commands

import (
	"fmt"
	"strings"

	"github.com/ShristiLabs/shipperdog/cli/internal/output"
	"github.com/spf13/cobra"
)

func newVersionCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "version",
		Short: "Inspect versions",
	}
	cmd.AddCommand(newVersionListCmd())
	return cmd
}

func newVersionListCmd() *cobra.Command {
	var (
		tag     string
		include string
		limit   int
		jsonOut bool
	)
	cmd := &cobra.Command{
		Use:   "list <app>",
		Short: "List an application's versions (tables or JSON)",
		Long: "GET /api/v1/applications/{id}/versions: newest-first by the normalized semver\n" +
			"sort key. The default window is the working set (DRAFT and ACTIVE); --include\n" +
			"archived,revoked widens it; --tag narrows to versions carrying a tag.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := dial()
			if err != nil {
				return err
			}
			ctx := cmd.Context()
			app, err := client.ResolveApplication(ctx, args[0])
			if err != nil {
				return err
			}
			versions, err := client.ListVersions(ctx, app.ID, tag, include)
			if err != nil {
				return err
			}
			if limit > 0 && len(versions) > limit {
				versions = versions[:limit]
			}
			out := cmd.OutOrStdout()
			if jsonOut {
				return output.JSON(out, versions)
			}
			if len(versions) == 0 {
				output.Line(out, "No versions found.")
				return nil
			}
			w := output.Table(out)
			output.Row(w, "NAME", "STATUS", "TAGS", "PLATFORMS", "ARTIFACTS", "CREATED", "ID")
			for _, v := range versions {
				tags := "-"
				if len(v.Tags) > 0 {
					tags = strings.Join(v.Tags, ",")
				}
				platforms := make([]string, 0, len(v.ArtifactsByPlatform))
				total := 0
				for p, slots := range v.ArtifactsByPlatform {
					platforms = append(platforms, p)
					total += len(slots)
				}
				platformsStr := "-"
				if len(platforms) > 0 {
					platformsStr = strings.Join(platforms, ",")
				}
				output.Row(w, v.VersionName, v.Status, tags, platformsStr, fmt.Sprintf("%d", total), v.CreatedAt, v.ID)
			}
			output.FlushTable(w)
			return nil
		},
	}
	cmd.Flags().StringVar(&tag, "tag", "", "narrow to versions carrying this tag")
	cmd.Flags().StringVar(&include, "include", "", "comma-separated archived and/or revoked to widen the window")
	cmd.Flags().IntVar(&limit, "limit", 0, "cap rows (0 = uncapped within pagination bounds)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "machine-readable output")
	return cmd
}
