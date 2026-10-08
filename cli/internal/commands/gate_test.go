package commands

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/exitcode"
)

// The S10-03 gate-command suite (issue #42): the CI submission
// contract #60 deferred to this story (its comment records the shape:
// status, evidence URL, the observed value of a threshold gate), the
// §32 block read with all four per-gate states, and the governed
// override - beside the local usage mirrors (outcome vocabulary,
// metric-backed refusal, evidence shape).

// gateWorld is the fake gate surface: the release world plus one
// walked release with its gate block.
func gateWorld(t *testing.T) *fakeAPI {
	t.Helper()
	f := releaseWorld(t)
	f.handleJSON("/api/v1/releases", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID,
			"status": "VALIDATING", "ring": "qa", "targetCount": 1,
		}}, "next_cursor": ""}
	})
	f.handleJSON("/api/v1/releases/"+relUUID, func() any {
		detail := detailJSON()
		detail["status"] = "VALIDATING"
		detail["ring"] = "qa"
		return detail
	})
	return f
}

func gatesView() map[string]any {
	return map[string]any{
		"releaseId": relUUID, "releaseRef": "R-1001", "status": "VALIDATING", "ring": "qa",
		"gates": []map[string]any{
			{
				"gateType": "unit-tests", "required": true, "attached": true, "schema": "PASS_FAIL",
				"metricBacked": false, "status": "PASSED", "observedValue": nil, "threshold": nil,
				"evidenceUrl": "https://ci.internal/run/9/tests", "source": "SUBMITTED", "submittedBy": "u-1",
				"evaluatedAt": "2026-10-08T10:00:00Z", "overridden": false, "overriddenBy": nil,
				"overriddenReason": nil, "overriddenAt": nil,
				"history": []map[string]any{},
			},
			{
				"gateType": "coverage-threshold", "required": true, "attached": true, "schema": "NUMERIC_MIN",
				"metricBacked": false, "status": "FAILED", "observedValue": 72.5, "threshold": 80,
				"evidenceUrl": "https://ci.internal/run/9/coverage", "source": "SUBMITTED", "submittedBy": "u-1",
				"evaluatedAt": "2026-10-08T10:00:00Z", "overridden": false, "overriddenBy": nil,
				"overriddenReason": nil, "overriddenAt": nil,
				"history": []map[string]any{},
			},
			{
				"gateType": "crash-free-threshold", "required": true, "attached": true, "schema": "NUMERIC_MIN",
				"metricBacked": true, "status": "NO_DATA", "observedValue": nil, "threshold": 99.5,
				"evidenceUrl": nil, "source": "METRIC", "submittedBy": nil,
				"evaluatedAt": "2026-10-08T10:00:00Z", "overridden": true, "overriddenBy": "u-1",
				"overriddenReason": "telemetry lag accepted for the pilot", "overriddenAt": "2026-10-08T11:00:00Z",
				"history": []map[string]any{},
			},
			{
				"gateType": "sast", "required": false, "attached": true, "schema": "PASS_FAIL",
				"metricBacked": false, "status": "", "observedValue": nil, "threshold": nil,
				"evidenceUrl": nil, "source": nil, "submittedBy": nil,
				"evaluatedAt": nil, "overridden": false, "overriddenBy": nil,
				"overriddenReason": nil, "overriddenAt": nil,
				"history": []map[string]any{},
			},
		},
	}
}

func TestGateResultSubmitsEvidenceValueAndOutcome(t *testing.T) {
	f := gateWorld(t)
	var posted int32
	f.HandleFunc("/api/v1/releases/"+relUUID+"/gates/unit-tests/result", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s", r.Method)
		}
		atomic.AddInt32(&posted, 1)
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode body: %v", err)
		}
		if body["applicationId"] != appUUID || body["status"] != "pass" ||
			body["evidenceUrl"] != "https://ci.internal/run/9/tests" {
			t.Errorf("body = %v", body)
		}
		if _, has := body["observedValue"]; has {
			t.Errorf("pass/fail gate must not carry observedValue: %v", body)
		}
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"gateType": "unit-tests", "required": true, "attached": true, "schema": "PASS_FAIL",
			"status": "PASSED", "evidenceUrl": "https://ci.internal/run/9/tests", "source": "SUBMITTED",
			"overridden": false, "history": []map[string]any{},
		})
	})

	if err := runCLI(t, f, "gate", "result", "R-1001", "unit-tests",
		"--status", "pass", "--evidence-url", "https://ci.internal/run/9/tests"); err != nil {
		t.Fatalf("result: %v", err)
	}
	if posted != 1 {
		t.Fatalf("posts = %d", posted)
	}
	if !strings.Contains(f.out(), "PASSED") || !strings.Contains(f.out(), "https://ci.internal/run/9/tests") {
		t.Errorf("output:\n%s", f.out())
	}
}

func TestGateResultNumericValueAndUsageMirrors(t *testing.T) {
	f := gateWorld(t)
	f.HandleFunc("/api/v1/releases/"+relUUID+"/gates/coverage-threshold/result", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["observedValue"] != 87.5 {
			t.Errorf("observedValue = %v (want 87.5)", body["observedValue"])
		}
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"gateType": "coverage-threshold", "schema": "NUMERIC_MIN", "status": "PASSED",
			"observedValue": 87.5, "threshold": 80, "evidenceUrl": "https://ci.internal/run/9/cov",
			"source": "SUBMITTED", "overridden": false, "history": []map[string]any{},
		})
	})
	if err := runCLI(t, f, "gate", "result", "R-1001", "coverage-threshold",
		"--status", "passed", "--evidence-url", "https://ci.internal/run/9/cov", "--value", "87.5"); err != nil {
		t.Fatalf("numeric result: %v", err)
	}
	if !strings.Contains(f.out(), "87.5 (threshold 80)") {
		t.Errorf("output:\n%s", f.out())
	}

	// The local usage mirrors: outcome vocabulary, evidence shape,
	// metric-backed refusal (fail-closed analytics kinds). The
	// command's own mirrors answer the usage exit code; cobra's
	// required-flag guard answers its own non-zero (1).
	for _, tc := range []struct {
		name     string
		args     []string
		wantCode int
	}{
		{"bad status", []string{"gate", "result", "R-1001", "unit-tests", "--status", "maybe",
			"--evidence-url", "https://ci.internal/x"}, exitcode.Usage},
		{"missing evidence", []string{"gate", "result", "R-1001", "unit-tests", "--status", "pass"}, 1},
		{"non-http evidence", []string{"gate", "result", "R-1001", "unit-tests", "--status", "pass",
			"--evidence-url", "ftp://example.com/x"}, exitcode.Usage},
		{"metric-backed refused", []string{"gate", "result", "R-1001", "crash-free-threshold",
			"--status", "pass", "--evidence-url", "https://ci.internal/x"}, exitcode.Usage},
		{"bad type shape", []string{"gate", "result", "R-1001", "Not A Type", "--status", "pass",
			"--evidence-url", "https://ci.internal/x"}, exitcode.Usage},
	} {
		if err := runCLI(t, f, tc.args...); err == nil {
			t.Errorf("%s: expected a usage error", tc.name)
		} else if code := ExitCode(err); code != tc.wantCode {
			t.Errorf("%s: exit = %d (want %d; %v)", tc.name, code, tc.wantCode, err)
		}
	}
}

func TestGateListRendersAllFourStates(t *testing.T) {
	f := gateWorld(t)
	f.handleJSON("/api/v1/releases/"+relUUID+"/gates", func() any { return gatesView() })

	if err := runCLI(t, f, "gate", "list", "R-1001"); err != nil {
		t.Fatalf("list: %v", err)
	}
	out := f.out()
	for _, want := range []string{
		"unit-tests: PASSED",
		"https://ci.internal/run/9/tests",
		"coverage-threshold: FAILED (blocking until passed or overridden)",
		"72.5 (threshold 80)",
		"crash-free-threshold: NO_DATA (fail-closed: no analytics data yet)",
		"source: analytics",
		`overridden by u-1 - "telemetry lag accepted for the pilot"`,
		"sast (advisory): not reported yet",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "gate", "list", "R-1001", "--json"); err != nil {
		t.Fatalf("list --json: %v", err)
	}
	var view api.ReleaseGatesView
	if err := json.Unmarshal([]byte(f.out()), &view); err != nil {
		t.Fatalf("json decode: %v\n%s", err, f.out())
	}
	if len(view.Gates) != 4 || view.Ring != "qa" {
		t.Errorf("view = %+v", view)
	}
}

func TestGateOverridePostsMandatoryReason(t *testing.T) {
	f := gateWorld(t)
	f.HandleFunc("/api/v1/releases/"+relUUID+"/gates/coverage-threshold/override", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode body: %v", err)
		}
		if body["applicationId"] != appUUID || body["reason"] != "finding is a false positive" {
			t.Errorf("body = %v", body)
		}
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"gateType": "coverage-threshold", "schema": "NUMERIC_MIN", "status": "FAILED",
			"observedValue": 72.5, "threshold": 80, "overridden": true, "overriddenBy": "u-1",
			"overriddenReason": "finding is a false positive", "overriddenAt": "2026-10-08T12:00:00Z",
			"history": []map[string]any{},
		})
	})

	if err := runCLI(t, f, "gate", "override", "R-1001", "coverage-threshold",
		"--reason", "finding is a false positive"); err != nil {
		t.Fatalf("override: %v", err)
	}
	if !strings.Contains(f.out(), "overridden") {
		t.Errorf("output:\n%s", f.out())
	}

	// The reason is mandatory: cobra's required-flag guard refuses
	// the call before any network round trip.
	if err := runCLI(t, f, "gate", "override", "R-1001", "coverage-threshold"); err == nil {
		t.Error("missing --reason: expected a usage error")
	}
}
