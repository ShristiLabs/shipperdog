// shipperdog is the developer-side CLI (PRD 20, story S5-06, issue
// #25): login, artifact upload/list/view/revalidate, version list.
package main

import (
	"context"
	"os"
	"os/signal"

	"github.com/ShristiLabs/shipperdog/cli/internal/commands"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	err := commands.NewRoot().ExecuteContext(ctx)
	os.Exit(commands.ExitCode(err))
}
