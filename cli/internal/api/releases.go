package api

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"time"
)

// The release surface (S9-01..03, served since issues #55-#57): the
// creation, the reads, the PRD 17 transitions, and the frozen manifest.
// Every route is application-addressed for permissions (the
// applicationId query parameter or body member), so the CLI resolves
// app names and R-numbers through the caller's own listings - the same
// context-follows-the-caller rule the artifact surface established.

// releaseStates is PRD 17's exhaustive state vocabulary (the server's
// own set; the CLI mirrors it for local validation only).
var releaseStates = map[string]bool{
	"DRAFT": true, "VALIDATING": true, "READY": true, "APPROVAL_PENDING": true,
	"APPROVED": true, "DEPLOYING": true, "ACTIVE": true, "PROMOTED": true,
	"COMPLETED": true, "ROLLED_BACK": true, "FAILED": true,
}

// ValidReleaseState reports whether to is a PRD 17 state value.
func ValidReleaseState(to string) bool {
	return releaseStates[to]
}

// ReleaseStates lists the vocabulary in stable order (help texts and
// error messages).
func ReleaseStates() []string {
	states := make([]string, 0, len(releaseStates))
	for state := range releaseStates {
		states = append(states, state)
	}
	sort.Strings(states)
	return states
}

// NormalizeReleaseState mirrors the server's own normalization
// (trim + upper) so the CLI can validate before a round trip.
func NormalizeReleaseState(to string) string {
	return strings.ToUpper(strings.TrimSpace(to))
}

// ReasonRequiredState reports whether the edge into to carries a
// mandatory reason (the failure and rollback crossings, S9-02).
func ReasonRequiredState(to string) bool {
	return to == "FAILED" || to == "ROLLED_BACK"
}

// nextTowardPromoted is the single forward path PRD 17 offers from
// each pre-active state to PROMOTED (the machine's edges are
// exhaustive; there is never a choice of next step on this path).
var nextTowardPromoted = map[string]string{
	"DRAFT":            "VALIDATING",
	"VALIDATING":       "READY",
	"READY":            "APPROVAL_PENDING",
	"APPROVAL_PENDING": "APPROVED",
	"APPROVED":         "DEPLOYING",
	"DEPLOYING":        "ACTIVE",
	"ACTIVE":           "PROMOTED",
}

// NextReleaseStateTowardPromoted returns the next crossing `release
// promote` must drive from status, or "" when the release is already
// PROMOTED/COMPLETED or terminal (FAILED/ROLLED_BACK) - the caller
// decides how to report each of those.
func NextReleaseStateTowardPromoted(status string) string {
	return nextTowardPromoted[status]
}

// ReleaseTerminalState reports whether status admits no further
// crossings (the machine's three absorbing states).
func ReleaseTerminalState(status string) bool {
	return status == "FAILED" || status == "ROLLED_BACK" || status == "COMPLETED"
}

// releaseRefPattern matches the quotable id's CLI-facing forms: the
// served "R-1001" plus the looser "r1001", "R1001", and bare "1001".
var releaseRefPattern = regexp.MustCompile(`^[Rr]?-?([0-9]+)$`)

// NormalizeReleaseRef accepts a release uuid, the served R-<number>
// form (any case), or a bare number, and returns either the uuid
// unchanged or the canonical "R-<number>". The empty string means the
// reference matched neither shape.
func NormalizeReleaseRef(ref string) string {
	trimmed := strings.TrimSpace(ref)
	if LooksLikeUUID(trimmed) {
		return trimmed
	}
	if matches := releaseRefPattern.FindStringSubmatch(trimmed); matches != nil {
		return "R-" + matches[1]
	}
	return ""
}

// CreateRelease posts the creation (a designated POST: the invocation
// carries one Idempotency-Key, so an intra-invocation HTTP retry can
// never double-create; a re-RUN answers the precise 409
// RELEASE_ALREADY_EXISTS, which the CLI surfaces verbatim).
func (c *Client) CreateRelease(ctx context.Context, body CreateReleaseRequest) (*ReleaseDetail, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var detail ReleaseDetail
	headers := map[string]string{"Idempotency-Key": idempotencyKey()}
	if err := c.callJSONHeaders(ctx, http.MethodPost, pathReleases, nil, nil, body, &detail, "create release", headers); err != nil {
		return nil, err
	}
	return &detail, nil
}

// ListReleases walks an application's release listing (newest quotable
// number first) with the server's conjunctive filters - versionId,
// status, tenantId - all optional.
func (c *Client) ListReleases(ctx context.Context, applicationID, versionID, status, tenantID string) ([]ReleaseView, error) {
	var all []ReleaseView
	cursor := ""
	for page := 0; page < maxPages; page++ {
		q := url.Values{"applicationId": {applicationID}, "limit": {"100"}}
		if versionID != "" {
			q.Set("versionId", versionID)
		}
		if status != "" {
			q.Set("status", status)
		}
		if tenantID != "" {
			q.Set("tenantId", tenantID)
		}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		ctx, cancel := context.WithTimeout(ctx, shortTimeout)
		var pageView ReleasePage
		err := c.callJSON(ctx, http.MethodGet, pathReleases, nil, q, nil, &pageView, "list releases")
		cancel()
		if err != nil {
			return nil, err
		}
		all = append(all, pageView.Items...)
		if pageView.NextCursor == "" {
			break
		}
		cursor = pageView.NextCursor
	}
	return all, nil
}

// GetRelease reads one release detail. applicationId is the
// permission target every release read requires; a release of another
// application conceals exactly like an unknown id (404
// RELEASE_NOT_FOUND).
func (c *Client) GetRelease(ctx context.Context, applicationID, releaseID string) (*ReleaseDetail, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"applicationId": {applicationID}}
	var detail ReleaseDetail
	if err := c.callJSON(ctx, http.MethodGet, pathReleaseOne, []string{releaseID}, q, nil, &detail, "read release"); err != nil {
		return nil, err
	}
	return &detail, nil
}

// TransitionRelease drives one PRD 17 crossing and returns the
// refreshed detail. The server is the authority on edges, mandatory
// reasons, and the policy seam; the CLI only mirrors its vocabulary.
func (c *Client) TransitionRelease(ctx context.Context, applicationID, releaseID, to, reason string) (*ReleaseDetail, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	body := ReleaseTransitionRequest{ApplicationID: applicationID, To: to, Reason: reason}
	var detail ReleaseDetail
	err := c.callJSON(ctx, http.MethodPost, pathReleaseTransitions, []string{releaseID}, nil, body, &detail, "transition release")
	if err != nil {
		return nil, err
	}
	return &detail, nil
}

// GetReleaseManifest reads the frozen manifest (the stored document,
// never recomputed). format "" or "json" decodes the view; format
// "yaml" returns the application/yaml rendering verbatim (the
// compliance export). A release that never crossed into APPROVED
// answers the honest 404 RELEASE_MANIFEST_NOT_FOUND.
func (c *Client) GetReleaseManifest(ctx context.Context, applicationID, releaseID, format string) (*ReleaseManifestView, []byte, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"applicationId": {applicationID}}
	if format != "" {
		q.Set("format", format)
	}
	data, _, err := c.request(ctx, http.MethodGet, pathReleaseManifest, []string{releaseID}, q, "", nil, "read release manifest", nil)
	if err != nil {
		return nil, nil, err
	}
	if format == "yaml" {
		return nil, data, nil
	}
	var view ReleaseManifestView
	if err := parseJSON(data, &view); err != nil {
		return nil, nil, err
	}
	return &view, nil, nil
}

// ResolveRelease turns a release reference (uuid, R-number, or bare
// number) plus an optional --app into the application and the release
// detail. Every release route is application-addressed, so without
// --app the CLI walks the caller's visible applications looking for
// exactly one match - visibility follows the caller's context.
func (c *Client) ResolveRelease(ctx context.Context, appRef, releaseRef string) (*ApplicationView, *ReleaseDetail, error) {
	ref := NormalizeReleaseRef(releaseRef)
	if ref == "" {
		return nil, nil, &ResolutionError{Kind: "release", Ref: releaseRef,
			Reason: "expected a release uuid or quotable id like R-1028"}
	}
	if appRef != "" {
		app, err := c.ResolveApplication(ctx, appRef)
		if err != nil {
			return nil, nil, err
		}
		detail, err := c.releaseInApp(ctx, app, ref)
		if err != nil {
			return nil, nil, err
		}
		if detail == nil {
			return nil, nil, &ResolutionError{Kind: "release", Ref: ref,
				Reason: "not found in this application"}
		}
		return app, detail, nil
	}

	orgID, err := c.requireOrgID(ctx)
	if err != nil {
		return nil, nil, err
	}
	apps, err := c.ListApplications(ctx, orgID)
	if err != nil {
		return nil, nil, err
	}
	var foundApps []string
	var found *ReleaseDetail
	var foundApp *ApplicationView
	for i := range apps {
		detail, err := c.releaseInApp(ctx, &apps[i], ref)
		if err != nil {
			return nil, nil, err
		}
		if detail != nil {
			foundApps = append(foundApps, apps[i].Name+" ("+apps[i].ID+")")
			found = detail
			foundApp = &apps[i]
		}
	}
	switch len(foundApps) {
	case 1:
		return foundApp, found, nil
	case 0:
		return nil, nil, &ResolutionError{Kind: "release", Ref: ref}
	default:
		return nil, nil, &ResolutionError{Kind: "release", Ref: ref, Candidates: foundApps}
	}
}

// releaseInApp finds one release within one application: a uuid reads
// directly (a miss conceals as the standard 404), an R-number walks
// the listing (no quotable-id filter exists server-side). nil means
// not present in this application.
func (c *Client) releaseInApp(ctx context.Context, app *ApplicationView, ref string) (*ReleaseDetail, error) {
	if LooksLikeUUID(ref) {
		detail, err := c.GetRelease(ctx, app.ID, ref)
		if err != nil {
			if isConcealedReleaseMiss(err) {
				return nil, nil
			}
			return nil, err
		}
		return detail, nil
	}
	releases, err := c.ListReleases(ctx, app.ID, "", "", "")
	if err != nil {
		return nil, err
	}
	for i := range releases {
		if releases[i].ReleaseRef == ref {
			return c.GetRelease(ctx, app.ID, releases[i].ID)
		}
	}
	return nil, nil
}

// isConcealedReleaseMiss reports the 404 every release read answers
// for an unknown OR foreign id (byte-identical by design).
func isConcealedReleaseMiss(err error) bool {
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		return false
	}
	return !apiErr.Network && apiErr.Problem.Status == http.StatusNotFound
}

// SelectReleaseArtifacts picks one READY artifact per platform from the
// version's slots - the newest by upload moment, ties broken by id so
// the choice is deterministic. Overrides (platform -> explicit
// artifact id, already platform-keyed) replace the automatic pick for
// their platform only.
func SelectReleaseArtifacts(version *VersionView, overrides map[string]string) []ReleaseArtifactSlot {
	platforms := make([]string, 0, len(version.ArtifactsByPlatform))
	for platform := range version.ArtifactsByPlatform {
		platforms = append(platforms, platform)
	}
	sort.Strings(platforms)
	var slots []ReleaseArtifactSlot
	for _, platform := range platforms {
		if id, ok := overrides[platform]; ok {
			slots = append(slots, ReleaseArtifactSlot{Platform: platform, ArtifactID: id})
			continue
		}
		var best *VersionArtifactSlot
		for i := range version.ArtifactsByPlatform[platform] {
			slot := &version.ArtifactsByPlatform[platform][i]
			if slot.Status != "READY" {
				continue
			}
			if best == nil || newerArtifactSlot(slot, best) {
				best = slot
			}
		}
		if best != nil {
			slots = append(slots, ReleaseArtifactSlot{Platform: platform, ArtifactID: best.ID})
		}
	}
	return slots
}

// newerArtifactSlot orders two slots by upload moment (ISO-8601
// instants; fall back to the raw string when one does not parse),
// breaking ties by id so the pick never flickers.
func newerArtifactSlot(a, b *VersionArtifactSlot) bool {
	at, aErr := time.Parse(time.RFC3339Nano, a.UploadedAt)
	bt, bErr := time.Parse(time.RFC3339Nano, b.UploadedAt)
	if aErr == nil && bErr == nil && !at.Equal(bt) {
		return at.After(bt)
	}
	if a.UploadedAt != b.UploadedAt {
		return a.UploadedAt > b.UploadedAt
	}
	return a.ID > b.ID
}
