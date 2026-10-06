# ADR-0004: CLI implemented in Go with a contract-tested thin client

Date: 2026-10-07
Status: Accepted (issue #25, S5-06)

## Context

Story S5-06 births the developer CLI (PRD 20): `login`, `artifact
upload`, `artifact list`, `version list` — the tool CI pipelines will
drive. The story's own technical note leaves the toolchain open:
"Java Picocli + GraalVM native-image (or Go — but monorepo consistency
favors Java/Picocli; decide in ADR)", and adds a constraint: "Reuse
generated OpenAPI client; never hand-roll endpoint URLs."

What the decision must satisfy:

- Single distributable binary per platform (the story's distribution
  criterion), cheap to produce for linux/darwin on amd64/arm64.
- Fast startup and trivial installation in CI runners (PRD 20: "This
  will be particularly useful for CI pipelines").
- A pinned, lockfile-recorded dependency set; lint and tests gated in
  CI like backend and frontend.
- Fidelity to the served API contract (the baseline spec is the
  platform's contract-first artifact, issue #8).

## Options considered

1. **Go (pinned toolchain, cobra, mostly stdlib).** One static binary
   per target with `CGO_ENABLED=0`; cross-compilation is a matrix of
   env vars, not a toolchain per platform. `net/http`, `mime/multipart`,
   `encoding/json`, `text/tabwriter` cover the entire client need;
   cobra supplies the command tree.
2. **Java Picocli + GraalVM native-image.** Monorepo language
   consistency with the backend, and the story note leaned here. But a
   native-image build is a second heavyweight toolchain in CI (per-OS
   GraalVM installs, minutes per target), reflection/MPO configuration
   for the JSON layer, and no local GraalVM exists on the development
   machine this story was built on — the criterion "leaves the tree
   verifying clean per the project profile" would depend on a toolchain
   nobody has installed. A JVM-bound (non-native) Picocli jar fails the
   single-binary criterion outright.
3. **Rust.** Equally strong static-binary story; declined on ecosystem
   fit alone: the CLI is an HTTP/JSON/table tool, and Go's stdlib
   covers it with two direct dependencies where Rust would need tokio,
   reqwest, serde, clap, and their trees for the same result.

## Decision

The CLI is **Go** (toolchain pinned to 1.26 via `cli/go.mod`; Go 1.26
is what CI and the development machine run), using **cobra v1.10.x**
(command tree, help, flags) and **golang.org/x/term** (hidden token
prompt) — the only direct dependencies, exact-pinned with the committed
`go.sum` lockfile. Lint is golangci-lint v2.10.1 (config committed at
`cli/.golangci.yml`), wired as a PR-gating workflow
(`.github/workflows/cli-ci.yml`) that also runs the unit tests and a
four-target cross-compile matrix (linux/darwin × amd64/arm64), each
target producing a version-stamped tarball (`-ldflags` injects
`git describe` output and commit into the binary; source builds answer
`dev`).

The "generated OpenAPI client" constraint is honored by structure
rather than codegen: every URL the CLI composes lives in one file
(`cli/internal/api/endpoints.go`) as path templates carrying the
spec's own placeholder names, and `endpoints_test.go` parses
`docs/openapi/baseline.yaml` and fails the build if any template stops
being a declared path. A generated Go client for five endpoints was
rejected: it drags a codegen step and a generated-object layer into CI
for a surface whose distinctive parts (streaming multipart with
progress, direct-to-storage PUT, poll-to-settle) are hand-written in
any client. The contract test gives the same protection — divergence
from the served contract is a build failure — at a fraction of the
machinery, and the spec's published artifact (backend-ci uploads the
generated spec) remains the source both sides test against.

Token storage is the issue-mandated file: `~/.shipperdog/credentials`
with 0600 permissions (the directory 0700). An OS keychain was
considered and declined: keychain access requires cgo or OS-specific
libraries, which breaks `CGO_ENABLED=0` static cross-compilation — the
distribution criterion wins, and the trade-off (file-scope protection
only) is documented in the CLI README.

## Consequences

- The monorepo gains a second language (Go) beside Java and
  TypeScript; accepted because the CLI's deployment shape (single
  static binary in CI runners) is exactly the shape Go exists for, and
  the surface is small enough for the stdlib to dominate.
- Contract drift is caught in `cli-ci` (the endpoint contract test
  runs in `go test ./...`), the same way `backend-ci` catches it with
  oasdiff.
- The credentials file's security envelope is the filesystem mode; the
  README documents `SHIPPERDOG_TOKEN` as the CI alternative that keeps
  secrets out of disks entirely.
- Homebrew/GHA-binary distribution remains optional future work
  (S12-03 owns CI packaging); this story ships the workflow-run
  tarball artifacts, which satisfies its "distributed via a build
  artifact" criterion.

## See also

- Issue #25 (S5-06), PRD 20, FR-004/FR-005, docs/cli.md,
  docs/openapi/baseline.yaml, `.github/workflows/cli-ci.yml`
