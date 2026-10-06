package commands

import (
	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/buildinfo"
	"github.com/ShristiLabs/shipperdog/cli/internal/config"
	"github.com/spf13/cobra"
)

// apiBaseFlag is the persistent --api override (flag > env > config >
// default); commands read it at run time, not at parse time.
var apiBaseFlag string

// NewRoot builds the command tree.
func NewRoot() *cobra.Command {
	root := &cobra.Command{
		Use:   "shipperdog",
		Short: "ShipperDog developer CLI - upload and inspect artifacts from the terminal",
		Long: "The ShipperDog developer-side companion (PRD 20): script uploads and\n" +
			"inspections without curl and JSON. Credentials live in ~/.shipperdog/credentials\n" +
			"(0600) after `shipperdog login`; SHIPPERDOG_API overrides the endpoint.",
		SilenceUsage:  true,
		SilenceErrors: true,
		Version:       buildinfo.Version,
	}
	root.SetVersionTemplate(buildinfo.String() + "\n")
	root.PersistentFlags().StringVar(&apiBaseFlag, "api", "", "API base URL (env "+config.EnvAPIBase+", default "+config.DefaultAPIBase+")")

	root.SetFlagErrorFunc(func(_ *cobra.Command, err error) error {
		return &UsageError{msg: err.Error()}
	})

	root.AddCommand(newLoginCmd(), newLogoutCmd(), newArtifactCmd(), newVersionCmd())
	return root
}

// dial builds a client with resolved base URL and stored credentials.
func dial() (*api.Client, error) {
	base := config.ResolveAPIBase(apiBaseFlag)
	token, err := config.ResolveToken("")
	if err != nil {
		return nil, err
	}
	return api.New(base, token), nil
}
