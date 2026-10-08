package api

import (
	"context"
	"net/http"
	"net/url"
	"strings"
)

// The quality-gate surface (S10-03, issue #42, FR-026): the release's
// §32 gate block, the external CI result submission (FR-022's
// "external systems may submit"), and the governed override. Routes
// are release-addressed with the application as the permission target,
// exactly the release surface's shape.

// GateResultOutcome is one recorded result of a gate's append-only
// history (newest first) - PASSED/FAILED as submitted, NO_DATA as the
// evaluation's fail-closed resolution of a metric-backed gate.
type GateResultOutcome struct {
	Outcome       string  `json:"outcome"`
	ObservedValue float64 `json:"observedValue"`
	EvidenceURL   string  `json:"evidenceUrl"`
	SubmittedBy   string  `json:"submittedBy"`
	Source        string  `json:"source"`
	RecordedAt    string  `json:"recordedAt"`
}

// ReleaseGate is one gate of the release's block: the current state
// beside the definition's expected result. Status is "" while nothing
// has been reported or instantiated yet (the honest not-reported
// state).
type ReleaseGate struct {
	GateType         string              `json:"gateType"`
	Required         bool                `json:"required"`
	Attached         bool                `json:"attached"`
	Schema           string              `json:"schema"`
	MetricBacked     bool                `json:"metricBacked"`
	Status           string              `json:"status"`
	ObservedValue    float64             `json:"observedValue"`
	Threshold        float64             `json:"threshold"`
	EvidenceURL      string              `json:"evidenceUrl"`
	Source           string              `json:"source"`
	SubmittedBy      string              `json:"submittedBy"`
	EvaluatedAt      string              `json:"evaluatedAt"`
	Overridden       bool                `json:"overridden"`
	OverriddenBy     string              `json:"overriddenBy"`
	OverriddenReason string              `json:"overriddenReason"`
	OverriddenAt     string              `json:"overriddenAt"`
	History          []GateResultOutcome `json:"history"`
}

// ReleaseGatesView is the release's whole gate block (PRD 32).
type ReleaseGatesView struct {
	ReleaseID  string        `json:"releaseId"`
	ReleaseRef string        `json:"releaseRef"`
	Status     string        `json:"status"`
	Ring       string        `json:"ring"`
	Gates      []ReleaseGate `json:"gates"`
}

// NormalizeGateType mirrors the server's canonicalization (trim +
// lowercase): definitions, submissions, and the built-in catalog
// share one vocabulary.
func NormalizeGateType(gateType string) string {
	return strings.ToLower(strings.TrimSpace(gateType))
}

// ValidGateOutcome normalizes a --status flag value to the server's
// submission vocabulary: pass/passed and fail/failed (case-blind).
// "" when the value names neither.
func ValidGateOutcome(status string) string {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "pass", "passed":
		return "pass"
	case "fail", "failed":
		return "fail"
	}
	return ""
}

// GateMetricBackedTypes lists the built-in metric-backed kinds (the
// fail-closed family whose values come from analytics, never
// submission) - the local mirror of the usage error the server answers.
func GateMetricBackedTypes() []string {
	return []string{"crash-free-threshold", "open-critical-bugs"}
}

// GateIsMetricBacked reports whether the normalized gate type is one
// of the built-in metric-backed kinds.
func GateIsMetricBacked(gateType string) bool {
	for _, metric := range GateMetricBackedTypes() {
		if metric == gateType {
			return true
		}
	}
	return false
}

// GateSchemaKind reports the expected-result shape of a built-in gate
// type: NUMERIC_MIN (a percentage threshold), COUNT_MAX (a ceiling),
// or PASS_FAIL - the local mirror for usage help.
func GateSchemaKind(gateType string) string {
	switch gateType {
	case "coverage-threshold", "crash-free-threshold":
		return "NUMERIC_MIN"
	case "open-critical-bugs":
		return "COUNT_MAX"
	}
	return "PASS_FAIL"
}

// GetReleaseGates reads the release's gate block (the dashboard's
// feed): the ring's expected set beside every recorded result.
func (c *Client) GetReleaseGates(ctx context.Context, applicationID, releaseID string) (*ReleaseGatesView, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	q := url.Values{"applicationId": {applicationID}}
	var view ReleaseGatesView
	if err := c.callJSON(ctx, http.MethodGet, pathReleaseGates, []string{releaseID}, q, nil, &view, "read release gates"); err != nil {
		return nil, err
	}
	return &view, nil
}

// SubmitGateResult records one external gate result (latest wins with
// history): pass or fail, the mandatory evidence URL, and the observed
// value a threshold/ceiling gate requires. observedValue is nil for
// the pass/fail kinds.
func (c *Client) SubmitGateResult(ctx context.Context, applicationID, releaseID, gateType, status, evidenceURL string, observedValue *float64) (*ReleaseGate, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	body := GateResultRequest{ApplicationID: applicationID, Status: status, EvidenceURL: evidenceURL}
	if observedValue != nil {
		body.ObservedValue = observedValue
	}
	var gate ReleaseGate
	err := c.callJSON(ctx, http.MethodPost, pathReleaseGateResult, []string{releaseID, gateType}, nil, body, &gate, "submit gate result")
	if err != nil {
		return nil, err
	}
	return &gate, nil
}

// OverrideGate overrides a blocking gate (FAILED or NO_DATA, exactly
// once) with the mandatory reason - the governed, audited exception.
func (c *Client) OverrideGate(ctx context.Context, applicationID, releaseID, gateType, reason string) (*ReleaseGate, error) {
	ctx, cancel := context.WithTimeout(ctx, shortTimeout)
	defer cancel()
	body := GateOverrideRequest{ApplicationID: applicationID, Reason: reason}
	var gate ReleaseGate
	err := c.callJSON(ctx, http.MethodPost, pathReleaseGateOverride, []string{releaseID, gateType}, nil, body, &gate, "override gate")
	if err != nil {
		return nil, err
	}
	return &gate, nil
}

// GateResultRequest is the submission body (the AC2 contract).
type GateResultRequest struct {
	ApplicationID string   `json:"applicationId"`
	Status        string   `json:"status"`
	EvidenceURL   string   `json:"evidenceUrl"`
	ObservedValue *float64 `json:"observedValue,omitempty"`
}

// GateOverrideRequest is the override body (the AC3 contract).
type GateOverrideRequest struct {
	ApplicationID string `json:"applicationId"`
	Reason        string `json:"reason"`
}
