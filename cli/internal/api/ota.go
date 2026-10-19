package api

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// The OTA surface (S16-14, issue #176, ADR-0010 workstream 10): bundle
// authoring (the create grants the direct-to-storage session - D5 -
// and the finalize lands the signed manifest), the exactly-once sdc1.
// app token (D3), the release's staged rollout control (D6), and the
// dashboard funnel. Every route is application-addressed for
// permissions (the body's or read's applicationId carries the target),
// so the CLI resolves app names and R-numbers through the caller's own
// listings - the context-follows-the-caller rule of the release
// surface. The device check route is deliberately absent: it is the
// sdc1.-authenticated device surface, not a developer CLI concern.

// OtaMaxBasisPoints is the full-device fraction of the ladder's final
// stage (100% = 10000 basis points, the server's own vocabulary).
const OtaMaxBasisPoints = 10000

// otaModes is the attach vocabulary the server accepts (an AUTOMATIC
// plan consults the policy engine between stages; a CHECKPOINT
// advance is the operator's explicit approval).
var otaModes = map[string]bool{"AUTOMATIC": true, "CHECKPOINT": true}

// NormalizeOtaMode mirrors the server's normalization (trim + upper).
func NormalizeOtaMode(mode string) string {
	return strings.ToUpper(strings.TrimSpace(mode))
}

// ValidOtaMode reports whether mode is an attach vocabulary value.
func ValidOtaMode(mode string) bool {
	return otaModes[mode]
}

// OtaModes lists the vocabulary in stable order (help texts).
func OtaModes() []string {
	return []string{"AUTOMATIC", "CHECKPOINT"}
}

// ParseOtaStages turns the --stages ladder ("1000,5000,10000", each
// entry optionally "bp=note") into the attach body's stage list,
// enforcing the contract the schema states: every threshold 1..10000,
// strictly ascending, the final stage exactly 10000. One precise local
// message beats a server 400 round trip.
func ParseOtaStages(spec string) ([]OtaStageBody, error) {
	entries := strings.Split(spec, ",")
	stages := make([]OtaStageBody, 0, len(entries))
	for i, entry := range entries {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			return nil, fmt.Errorf("stage %d is empty; expected comma-separated basis points like 1000,5000,10000", i+1)
		}
		threshold, note, _ := strings.Cut(entry, "=")
		threshold = strings.TrimSpace(threshold)
		note = strings.TrimSpace(note)
		points, err := strconv.Atoi(threshold)
		if err != nil {
			return nil, fmt.Errorf("stage %d (%q): basis points must be an integer", i+1, entry)
		}
		if points < 1 || points > OtaMaxBasisPoints {
			return nil, fmt.Errorf("stage %d: basis points %d outside 1..%d", i+1, points, OtaMaxBasisPoints)
		}
		if len(stages) > 0 && points <= int(stages[len(stages)-1].BasisPoints) {
			return nil, fmt.Errorf("stage %d (%d) is not strictly above stage %d (%d); the ladder is cumulative", i+1, points, i, stages[len(stages)-1].BasisPoints)
		}
		stages = append(stages, OtaStageBody{BasisPoints: int32(points), Note: note})
	}
	if len(stages) == 0 {
		return nil, fmt.Errorf("the stage ladder is empty; expected comma-separated basis points like 1000,5000,10000")
	}
	if stages[len(stages)-1].BasisPoints != OtaMaxBasisPoints {
		return nil, fmt.Errorf("the final stage must be exactly %d (got %d); the ladder ends at the full fleet", OtaMaxBasisPoints, stages[len(stages)-1].BasisPoints)
	}
	return stages, nil
}

// CreateOtaBundle posts the authoring draft (a designated POST: one
// Idempotency-Key per invocation, so an intra-invocation retry never
// double-creates). The answer carries the bundle id, the dedicated
// versions row, and the direct-to-storage upload grant.
func (c *Client) CreateOtaBundle(ctx context.Context, body CreateOtaBundleRequest) (*OtaDraftView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var draft OtaDraftView
	headers := map[string]string{"Idempotency-Key": idempotencyKey()}
	if err := c.callJSONHeaders(ctx, http.MethodPost, pathOtaBundles, nil, nil, body, &draft, "create ota bundle", headers); err != nil {
		return nil, err
	}
	return &draft, nil
}

// FinalizeOtaBundle lands the manifest and the Ed25519 signature after
// the bytes reached storage (a designated POST; created false means
// the retry replayed the already-SIGNED row - the state is the
// answer).
func (c *Client) FinalizeOtaBundle(ctx context.Context, bundleID, versionID string) (*OtaFinalizeView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	body := map[string]string{"versionId": versionID}
	var final OtaFinalizeView
	headers := map[string]string{"Idempotency-Key": idempotencyKey()}
	if err := c.callJSONHeaders(ctx, http.MethodPost, pathOtaBundleFinalize, []string{bundleID}, nil, body, &final, "finalize ota bundle", headers); err != nil {
		return nil, err
	}
	return &final, nil
}

// GetOtaBundle reads one bundle; applicationId is the permission
// target (a foreign or unknown bundle conceals like an unknown id).
func (c *Client) GetOtaBundle(ctx context.Context, applicationID, bundleID string) (*OtaBundleView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"applicationId": {applicationID}}
	var view OtaBundleView
	if err := c.callJSON(ctx, http.MethodGet, pathOtaBundleOne, []string{bundleID}, q, nil, &view, "read ota bundle"); err != nil {
		return nil, err
	}
	return &view, nil
}

// ListOtaBundles walks the application's bundle listing (newest
// first) with the optional V55 status filter (UPLOADING, VALIDATING,
// SIGNED, RELEASED, REVOKED).
func (c *Client) ListOtaBundles(ctx context.Context, applicationID, status string) ([]OtaBundleView, error) {
	var all []OtaBundleView
	cursor := ""
	for page := 0; page < maxPages; page++ {
		q := url.Values{"applicationId": {applicationID}, "limit": {"100"}}
		if status != "" {
			q.Set("status", status)
		}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		ctx, cancel := context.WithTimeout(ctx, shortTimeout)
		var pageView OtaBundlePage
		err := c.callJSON(ctx, http.MethodGet, pathOtaBundles, nil, q, nil, &pageView, "list ota bundles")
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

// IssueOtaAppToken mints the application's sdc1. device identifier (a
// designated POST). The value rides the answer exactly once; the
// registry stores only its SHA-256.
func (c *Client) IssueOtaAppToken(ctx context.Context, applicationID, name string) (*OtaIssuedView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	body := IssueOtaAppTokenRequest{ApplicationID: applicationID, Name: name}
	var issued OtaIssuedView
	headers := map[string]string{"Idempotency-Key": idempotencyKey()}
	if err := c.callJSONHeaders(ctx, http.MethodPost, pathOtaAppTokens, nil, nil, body, &issued, "issue ota app token", headers); err != nil {
		return nil, err
	}
	return &issued, nil
}

// AttachOtaRollout attaches the release's staged rollout plan - and
// thereby COMMENCES it (a designated POST; one plan per release,
// ever). The plan is born RUNNING on a live OTA release.
func (c *Client) AttachOtaRollout(ctx context.Context, body AttachOtaRolloutRequest) (*OtaRolloutView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	var view OtaRolloutView
	headers := map[string]string{"Idempotency-Key": idempotencyKey()}
	if err := c.callJSONHeaders(ctx, http.MethodPost, pathOtaRollouts, nil, nil, body, &view, "attach ota rollout", headers); err != nil {
		return nil, err
	}
	return &view, nil
}

// HaltOtaRollout is the manual, audit-trailed stop (D10): the reason
// is mandatory, and the check resolution answers no-update while the
// plan stands HALTED.
func (c *Client) HaltOtaRollout(ctx context.Context, applicationID, releaseID, reason string) (*OtaRolloutView, error) {
	return c.controlOtaRollout(ctx, pathOtaRolloutHalt, applicationID, releaseID, reason, "halt ota rollout")
}

// ResumeOtaRollout returns a HALTED or PAUSED plan to RUNNING -
// serving what the plan had reached (resuming widens nothing by
// itself).
func (c *Client) ResumeOtaRollout(ctx context.Context, applicationID, releaseID, reason string) (*OtaRolloutView, error) {
	return c.controlOtaRollout(ctx, pathOtaRolloutResume, applicationID, releaseID, reason, "resume ota rollout")
}

// controlOtaRollout posts one operator control action (a designated
// POST; concurrent control on one plan answers the optimistic-lock
// 409, never a lost update).
func (c *Client) controlOtaRollout(ctx context.Context, pathTemplate, applicationID, releaseID, reason, operation string) (*OtaRolloutView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	body := OtaControlRequest{ApplicationID: applicationID, Reason: reason}
	var view OtaRolloutView
	headers := map[string]string{"Idempotency-Key": idempotencyKey()}
	if err := c.callJSONHeaders(ctx, http.MethodPost, pathTemplate, []string{releaseID}, nil, body, &view, operation, headers); err != nil {
		return nil, err
	}
	return &view, nil
}

// GetOtaRollout reads the rollout's status view: the plan's mode and
// standing, the full ladder, and the append-only timeline.
func (c *Client) GetOtaRollout(ctx context.Context, applicationID, releaseID string) (*OtaRolloutView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"applicationId": {applicationID}}
	var view OtaRolloutView
	if err := c.callJSON(ctx, http.MethodGet, pathOtaRolloutOne, []string{releaseID}, q, nil, &view, "read ota rollout"); err != nil {
		return nil, err
	}
	return &view, nil
}

// GetOtaFunnel reads the dashboard funnel of one bundle - the
// tenant-addressed dashboard route, so the caller passes the owning
// application's tenant id; ring optionally narrows to one ring's
// devices.
func (c *Client) GetOtaFunnel(ctx context.Context, tenantID, bundleID, ring string) (*OtaFunnelView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{}
	if ring != "" {
		q.Set("ring", ring)
	}
	var view OtaFunnelView
	if err := c.callJSON(ctx, http.MethodGet, pathOtaFunnel, []string{tenantID, bundleID}, q, nil, &view, "read ota funnel"); err != nil {
		return nil, err
	}
	return &view, nil
}

// ResolveOtaBundle turns a bundle reference (uuid or bundleVersion
// label) plus the owning application into the bundle row. A uuid reads
// directly (a miss conceals as the standard 404); a label walks the
// listing - nothing is listable before finalize lands the SIGNED row.
func (c *Client) ResolveOtaBundle(ctx context.Context, app *ApplicationView, ref string) (*OtaBundleView, error) {
	trimmed := strings.TrimSpace(ref)
	if LooksLikeUUID(trimmed) {
		view, err := c.GetOtaBundle(ctx, app.ID, trimmed)
		if err != nil {
			if isConcealedReleaseMiss(err) {
				return nil, &ResolutionError{Kind: "bundle", Ref: trimmed, Reason: "not found in this application"}
			}
			return nil, err
		}
		return view, nil
	}
	bundles, err := c.ListOtaBundles(ctx, app.ID, "")
	if err != nil {
		return nil, err
	}
	for i := range bundles {
		if bundles[i].BundleVersion == trimmed {
			return &bundles[i], nil
		}
	}
	return nil, &ResolutionError{Kind: "bundle", Ref: trimmed, Reason: "not found in this application (only SIGNED-and-later bundles are listable)"}
}
