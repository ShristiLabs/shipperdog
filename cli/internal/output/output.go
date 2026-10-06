// Package output renders CLI results: human tables to stdout, JSON
// mode for scripting, upload progress to stderr (stdout stays clean
// data in both modes).
package output

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"golang.org/x/term"
)

// JSON prints value as indented JSON on the given writer (the --json
// mode); commands pass their stdout writer so tests can capture.
func JSON(w io.Writer, value any) error {
	encoded, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return fmt.Errorf("encoding JSON output: %w", err)
	}
	_, err = fmt.Fprintln(w, string(encoded))
	return err
}

// Line is a best-effort human-output write: a broken stdout pipe (the
// classic `shipperdog ... | head` exit) is not an error worth failing a
// CI pipeline over, so the write error is deliberately dropped.
func Line(w io.Writer, text string) {
	_, _ = fmt.Fprintln(w, text)
}

// Printf is Line's formatted form, same best-effort contract.
func Printf(w io.Writer, format string, args ...any) {
	_, _ = fmt.Fprintf(w, format, args...)
}

// Table opens a tabwriter on the given writer; call Row(...) then
// FlushTable.
func Table(out io.Writer) *tabwriter.Writer {
	return tabwriter.NewWriter(out, 0, 4, 3, ' ', 0)
}

// FlushTable finishes a table.
func FlushTable(w *tabwriter.Writer) {
	_ = w.Flush()
}

// Row writes one tab-separated line (callers pass the *tabwriter.Writer
// returned by Table).
func Row(w io.Writer, cells ...string) {
	_, _ = fmt.Fprintln(w, strings.Join(cells, "\t"))
}

// HumanBytes renders sizes CI-log-friendly.
func HumanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	value := float64(n)
	units := []string{"KiB", "MiB", "GiB"}
	for _, u := range units {
		value /= unit
		if value < unit {
			return fmt.Sprintf("%.1f %s", value, u)
		}
	}
	return fmt.Sprintf("%.1f TiB", value/1024)
}

// Progress reports upload progress to stderr. On a TTY it rewrites one
// line; piped (CI logs) it prints each 10% milestone on its own line.
type Progress struct {
	total    int64
	start    time.Time
	lastPct  int
	interval time.Duration
	lastEmit time.Time
}

// NewProgress builds a reporter; call Finish after the stream ends.
func NewProgress(label string, total int64) *Progress {
	fmt.Fprintf(os.Stderr, "%s: %s\n", label, HumanBytes(total))
	return &Progress{total: total, start: time.Now(), interval: 300 * time.Millisecond}
}

// Reporter is the callback signature the API client reports through.
func (p *Progress) Reporter(sent, total int64) {
	pct := 0
	if total > 0 {
		pct = int(sent * 100 / total)
	}
	isTTY := term.IsTerminal(int(os.Stderr.Fd()))
	if isTTY {
		now := time.Now()
		if now.Sub(p.lastEmit) < p.interval && pct < 100 {
			return
		}
		p.lastEmit = now
		fmt.Fprintf(os.Stderr, "\r  %3d%%  %s / %s", pct, HumanBytes(sent), HumanBytes(total))
		return
	}
	if pct/10 > p.lastPct/10 {
		fmt.Fprintf(os.Stderr, "  %3d%%  %s / %s\n", pct, HumanBytes(sent), HumanBytes(total))
	}
	p.lastPct = pct
}

// Finish closes the progress line with the transfer rate.
func (p *Progress) Finish() {
	elapsed := time.Since(p.start)
	rate := ""
	if elapsed > 0 {
		rate = fmt.Sprintf(" in %s (%s/s)", elapsed.Round(time.Millisecond), HumanBytes(p.total/int64(elapsed.Seconds()+1)))
	}
	fmt.Fprintf(os.Stderr, "\r  100%%  %s%s\n", HumanBytes(p.total), rate)
}
