package commands

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"testing"

	"github.com/ShristiLabs/shipperdog/cli/internal/api"
	"github.com/ShristiLabs/shipperdog/cli/internal/exitcode"
)

// The S9-06 release-command suite (issue #60): creation body and
// artifact auto-selection, the API's validation errors surfaced as the
// CI reason list, resolution of quotable ids across applications, the
// transition mirrors, and the promote walk - including the blocked
// state's reason list and exit class (AC2) and the machine-parseable
// detail (AC4).

const (
	relUUID     = "77777777-7777-7777-7777-777777777777"
	relUUID2    = "88888888-8888-8888-8888-888888888888"
	tenantUUID  = "99999999-9999-9999-9999-999999999999"
	tenantUUID2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	groupUUID   = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
	artNew      = "cccccccc-cccc-cccc-cccc-cccccccccccc"
	artOld      = "dddddddd-dddd-dddd-dddd-dddddddddddd"
	artAndroid  = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
)

// releaseWorld is the fake release surface: one application whose
// version carries two IOS artifacts (an older and a newer READY - the
// auto-selection must pick the newer) plus one READY ANDROID artifact,
// and one FAILED upload that must never be selected.
func releaseWorld(t *testing.T) *fakeAPI {
	t.Helper()
	f := newFakeAPI(t)
	f.handleJSON("/api/v1/auth/me", func() any {
		return map[string]any{"id": "u-1", "orgId": "o-1"}
	})
	f.handleJSON("/api/v1/applications", func() any {
		return map[string]any{"items": []map[string]any{{"id": appUUID, "name": "Banking"}}}
	})
	f.handleJSON("/api/v1/applications/"+appUUID, func() any {
		return map[string]any{"id": appUUID, "name": "Banking"}
	})
	f.handleJSON("/api/v1/applications/"+appUUID+"/versions", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": verUUID, "versionName": "7.12.0", "status": "ACTIVE",
			"artifactsByPlatform": map[string]any{
				"IOS": []map[string]any{
					{"id": artOld, "platform": "IOS", "status": "READY", "uploadedAt": "2026-10-06T09:00:00Z"},
					{"id": artNew, "platform": "IOS", "status": "READY", "uploadedAt": "2026-10-07T09:00:00Z"},
				},
				"ANDROID": []map[string]any{
					{"id": artAndroid, "platform": "ANDROID", "status": "READY", "uploadedAt": "2026-10-07T10:00:00Z"},
					{"id": artUUID2, "platform": "ANDROID", "status": "FAILED", "uploadedAt": "2026-10-07T11:00:00Z"},
				},
			},
		}}}
	})
	f.start()
	return f
}

// detailJSON is the served release detail for the happy-path reads.
func detailJSON() map[string]any {
	return map[string]any{
		"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID,
		"status": "DRAFT", "ring": "engineering", "ringName": "Engineering",
		"artifacts": []map[string]any{{"platform": "IOS", "artifactId": artNew}},
		"targets":   []map[string]any{{"tenantId": tenantUUID, "ring": "engineering", "testerGroupIds": []string{}}},
		"createdBy": "u-1", "createdAt": "2026-10-07T09:30:00Z", "updatedAt": "2026-10-07T09:30:00Z",
	}
}

func TestReleaseCreateBodyAutoSelectionAndOutput(t *testing.T) {
	f := releaseWorld(t)
	var mu sync.Mutex
	var bodies []map[string]any
	var idemKeys []string
	f.HandleFunc("/api/v1/releases", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s", r.Method)
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		bodies = append(bodies, body)
		idemKeys = append(idemKeys, r.Header.Get("Idempotency-Key"))
		mu.Unlock()
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(detailJSON())
	})

	err := runCLI(t, f, "release", "create", "Banking", "7.12.0",
		"--tenants", tenantUUID+","+tenantUUID2,
		"--groups", tenantUUID+"="+groupUUID, "--groups", groupUUID,
		"--ring", " Tenant-Pilot ", "--environment", "12121212-1212-1212-1212-121212121212")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if len(bodies) != 1 {
		t.Fatalf("creation POSTs = %d", len(bodies))
	}
	if idemKeys[0] == "" {
		t.Error("creation carried no Idempotency-Key")
	}
	body := bodies[0]
	if body["applicationId"] != appUUID || body["versionId"] != verUUID {
		t.Errorf("ids = %v", body)
	}
	if body["ring"] != "tenant-pilot" {
		t.Errorf("ring not normalized: %v", body["ring"])
	}
	if body["environmentId"] != "12121212-1212-1212-1212-121212121212" {
		t.Errorf("environmentId = %v", body["environmentId"])
	}
	artifacts, _ := body["artifacts"].([]any)
	if len(artifacts) != 2 {
		t.Fatalf("artifacts = %v", artifacts)
	}
	byPlatform := map[string]any{}
	for _, slot := range artifacts {
		m := slot.(map[string]any)
		byPlatform[m["platform"].(string)] = m["artifactId"]
	}
	if byPlatform["IOS"] != artNew {
		t.Errorf("IOS auto-select = %v (want the newest READY)", byPlatform["IOS"])
	}
	if byPlatform["ANDROID"] != artAndroid {
		t.Errorf("ANDROID auto-select = %v (a FAILED artifact must never be selected)", byPlatform["ANDROID"])
	}
	targets, _ := body["targets"].([]any)
	if len(targets) != 2 {
		t.Fatalf("targets = %v", targets)
	}
	first := targets[0].(map[string]any)
	if first["tenantId"] != tenantUUID {
		t.Errorf("target order not --tenants order: %v", first)
	}
	firstGroups, _ := first["testerGroupIds"].([]any)
	if len(firstGroups) != 2 {
		t.Errorf("scoped+bare groups not merged: %v", first["testerGroupIds"])
	}
	second := targets[1].(map[string]any)
	if second["tenantId"] != tenantUUID2 {
		t.Errorf("second target = %v", second)
	}
	if !strings.Contains(f.out(), "R-1001") {
		t.Errorf("stdout missing the quotable id:\n%s", f.out())
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "release", "create", "Banking", "7.12.0",
		"--tenants", tenantUUID, "--ring", "engineering", "--json"); err != nil {
		t.Fatalf("create --json: %v", err)
	}
	var detail map[string]any
	if err := json.Unmarshal([]byte(f.out()), &detail); err != nil {
		t.Fatalf("json decode: %v\n%s", err, f.out())
	}
	if detail["releaseRef"] != "R-1001" || detail["status"] != "DRAFT" {
		t.Errorf("detail = %v", detail)
	}
}

func TestReleaseCreateMirrorsAPIErrors(t *testing.T) {
	f := releaseWorld(t)
	f.HandleFunc("/api/v1/releases", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"status":422,"code":"RELEASE_TARGETS_INVALID","detail":"One or more release targets are invalid.",` +
			`"errors":{"` + tenantUUID + `":"tenant does not hold the application"}}`))
	})
	err := runCLI(t, f, "release", "create", "Banking", "7.12.0",
		"--tenants", tenantUUID, "--ring", "engineering")
	var apiErr *api.APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("err = %v, want APIError", err)
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("exit code = %d, want validation", code)
	}
	if len(apiErr.Problem.Errors) != 1 || apiErr.Problem.Errors[tenantUUID] == "" {
		t.Errorf("errors member not parsed: %v", apiErr.Problem.Errors)
	}
}

func TestReleaseCreateLocalValidation(t *testing.T) {
	f := releaseWorld(t)
	f.HandleFunc("/api/v1/releases", func(w http.ResponseWriter, _ *http.Request) {
		t.Error("server must not be reached by a locally-refused creation")
		w.WriteHeader(http.StatusBadRequest)
	})
	cases := []struct {
		name string
		args []string
	}{
		{"no ready artifacts", []string{"release", "create", "Retail", "3.0.0", "--tenants", tenantUUID, "--ring", "qa"}},
		{"non-uuid tenant", []string{"release", "create", "Banking", "7.12.0", "--tenants", "acme", "--ring", "qa"}},
		{"duplicate tenant", []string{"release", "create", "Banking", "7.12.0", "--tenants", tenantUUID + "," + tenantUUID, "--ring", "qa"}},
		{"bad group prefix", []string{"release", "create", "Banking", "7.12.0", "--tenants", tenantUUID, "--groups", "acme=" + groupUUID, "--ring", "qa"}},
		{"bad artifact platform", []string{"release", "create", "Banking", "7.12.0", "--tenants", tenantUUID, "--ring", "qa", "--artifact", "web=" + artNew}},
		{"bad artifact id", []string{"release", "create", "Banking", "7.12.0", "--tenants", tenantUUID, "--ring", "qa", "--artifact", "ios=not-a-uuid"}},
		{"bad environment", []string{"release", "create", "Banking", "7.12.0", "--tenants", tenantUUID, "--ring", "qa", "--environment", "env"}},
		{"scoped group outside tenants", []string{"release", "create", "Banking", "7.12.0",
			"--tenants", tenantUUID, "--groups", tenantUUID2 + "=" + groupUUID, "--ring", "qa"}},
	}
	for _, tc := range cases {
		err := runCLI(t, f, tc.args...)
		var usage *UsageError
		var resolution *api.ResolutionError
		if !errors.As(err, &usage) && !errors.As(err, &resolution) {
			t.Errorf("%s: err = %v, want usage/resolution class", tc.name, err)
			continue
		}
		if code := ExitCode(err); code != exitcode.Usage {
			t.Errorf("%s: exit code = %d, want usage", tc.name, code)
		}
	}
}

func TestReleaseListFiltersAndOutput(t *testing.T) {
	f := releaseWorld(t)
	var seenQuery string
	f.HandleFunc("/api/v1/releases", func(w http.ResponseWriter, r *http.Request) {
		seenQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"items": []map[string]any{{
				"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID,
				"status": "DRAFT", "ring": "engineering", "environment": nil, "targetCount": 2,
				"createdAt": "2026-10-07T09:30:00Z", "updatedAt": "2026-10-07T09:30:00Z",
			}},
			"next_cursor": "",
		})
	})
	if err := runCLI(t, f, "release", "list", "Banking",
		"--version", "7.12.0", "--status", "draft", "--tenant", tenantUUID); err != nil {
		t.Fatalf("list: %v", err)
	}
	for _, want := range []string{
		"applicationId=" + appUUID, "versionId=" + verUUID, "status=DRAFT", "tenantId=" + tenantUUID,
	} {
		if !strings.Contains(seenQuery, want) {
			t.Errorf("query %q missing %q", seenQuery, want)
		}
	}
	for _, want := range []string{"R-1001", "DRAFT", "engineering", "Banking"} {
		if !strings.Contains(f.out(), want) {
			t.Errorf("table missing %q:\n%s", want, f.out())
		}
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "release", "list", "Banking", "--json"); err != nil {
		t.Fatalf("list --json: %v", err)
	}
	var rows []map[string]any
	if err := json.Unmarshal([]byte(f.out()), &rows); err != nil {
		t.Fatalf("json decode: %v\n%s", err, f.out())
	}
	if len(rows) != 1 || rows[0]["releaseRef"] != "R-1001" || rows[0]["targetCount"] != float64(2) {
		t.Errorf("rows = %v", rows)
	}
}

func TestReleaseGetResolutionFormsAndJSON(t *testing.T) {
	f := releaseWorld(t)
	f.handleJSON("/api/v1/releases", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID,
			"status": "DRAFT", "ring": "engineering", "targetCount": 1,
		}}, "next_cursor": ""}
	})
	f.handleJSON("/api/v1/releases/"+relUUID, func() any { return detailJSON() })
	// The gate block read rides every get since S10-03 (issue #42):
	// this release's ring expects nothing and nothing was reported.
	f.handleJSON("/api/v1/releases/"+relUUID+"/gates", func() any {
		return map[string]any{
			"releaseId": relUUID, "releaseRef": "R-1001", "status": "DRAFT", "ring": "engineering",
			"gates": []map[string]any{},
		}
	})

	for _, ref := range []string{"R-1001", "r1001", "1001", relUUID} {
		f.stdout.Reset()
		if err := runCLI(t, f, "release", "get", ref); err != nil {
			t.Fatalf("get %s: %v", ref, err)
		}
		if !strings.Contains(f.out(), "R-1001") || !strings.Contains(f.out(), "No gates required") {
			t.Errorf("get %s output:\n%s", ref, f.out())
		}
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "release", "get", "R-1001", "--json"); err != nil {
		t.Fatalf("get --json: %v", err)
	}
	var detail map[string]any
	if err := json.Unmarshal([]byte(f.out()), &detail); err != nil {
		t.Fatalf("json decode: %v\n%s", err, f.out())
	}
	if detail["id"] != relUUID || detail["ringName"] != "Engineering" {
		t.Errorf("detail = %v", detail)
	}

	err := runCLI(t, f, "release", "get", "not-a-ref")
	var resolution *api.ResolutionError
	if !errors.As(err, &resolution) {
		t.Errorf("bad ref: err = %v", err)
	}
	if code := ExitCode(err); code != exitcode.Usage {
		t.Errorf("bad ref exit code = %d", code)
	}
}

// A --app-scoped read that misses must report not-found, never panic
// on a nil detail (the review round's blocker).
func TestReleaseGetWithAppMissReportsNotFound(t *testing.T) {
	f := releaseWorld(t)
	f.HandleFunc("/api/v1/releases/"+relUUID, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"status":404,"code":"RELEASE_NOT_FOUND","detail":"release not found"}`))
	})
	err := runCLI(t, f, "release", "get", relUUID, "--app", appUUID)
	var resolution *api.ResolutionError
	if !errors.As(err, &resolution) {
		t.Fatalf("err = %v (%T), want ResolutionError", err, err)
	}
	if !strings.Contains(err.Error(), "not found in this application") {
		t.Errorf("message = %v", err)
	}
}

func TestReleaseGetAmbiguousAcrossApps(t *testing.T) {
	f := newFakeAPI(t)
	f.handleJSON("/api/v1/auth/me", func() any {
		return map[string]any{"id": "u-1", "orgId": "o-1"}
	})
	f.handleJSON("/api/v1/applications", func() any {
		return map[string]any{"items": []map[string]any{
			{"id": appUUID, "name": "Banking"},
			{"id": appUUID2, "name": "Retail"},
		}}
	})
	for _, app := range []string{appUUID, appUUID2} {
		f.handleJSON("/api/v1/applications/"+app+"/versions", func() any {
			return map[string]any{"items": []map[string]any{{
				"id": verUUID, "versionName": "7.12.0", "artifactsByPlatform": map[string]any{},
			}}}
		})
	}
	f.handleJSON("/api/v1/releases", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID, "status": "DRAFT",
		}}, "next_cursor": ""}
	})
	f.handleJSON("/api/v1/releases/"+relUUID, func() any { return detailJSON() })
	f.start()

	err := runCLI(t, f, "release", "get", "R-1001")
	var resolution *api.ResolutionError
	if !errors.As(err, &resolution) {
		t.Fatalf("err = %v, want ResolutionError", err)
	}
	if !strings.Contains(err.Error(), "ambiguous") {
		t.Errorf("message = %v", err)
	}
}

// transitionRecorder serves the transition POST and the S10-05 promote
// POST, advancing a release through PRD 17 and then through the ring
// order from its starting status, and records every body.
type transitionRecorder struct {
	mu         sync.Mutex
	status     string
	rings      []string
	ringIdx    int
	bodies     []map[string]any
	promotes   []map[string]any
	refuse     map[string]map[string]string // to-state -> errors member
	refuseRing map[string]string            // non-nil -> every promote answers the 409
}

func (rec *transitionRecorder) handler(w http.ResponseWriter, r *http.Request) {
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	to, _ := body["to"].(string)
	rec.mu.Lock()
	rec.bodies = append(rec.bodies, body)
	current := rec.status
	if errorsMember, blocked := rec.refuse[to]; blocked {
		rec.mu.Unlock()
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status": 422, "code": "RELEASE_TRANSITION_BLOCKED",
			"detail": "The transition is blocked by a release policy.",
			"errors": errorsMember,
		})
		return
	}
	rec.status = to
	rec.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(detailWithStatus(current, to))
}

// handlerPromote serves POST /promote (S10-05): one ring hop at a
// time through the recorder's ring order, the final hop carrying the
// release to PROMOTED; refuseRing turns every hop into the engine's
// complete-blocker-list 409.
func (rec *transitionRecorder) handlerPromote(w http.ResponseWriter, r *http.Request) {
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	rec.mu.Lock()
	rec.promotes = append(rec.promotes, body)
	if rec.refuseRing != nil {
		errorsMember := rec.refuseRing
		rec.mu.Unlock()
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status": 409, "code": "RELEASE_PROMOTION_BLOCKED",
			"detail": "The promotion is blocked; see the errors member for the complete blocker list.",
			"errors": errorsMember,
		})
		return
	}
	from := rec.rings[rec.ringIdx]
	rec.ringIdx++
	to := rec.rings[rec.ringIdx]
	tenants, _ := body["tenants"].([]any)
	if tenants == nil {
		tenants = []any{tenantUUID}
	}
	crossed := rec.ringIdx == len(rec.rings)-1
	if crossed {
		rec.status = "PROMOTED"
	}
	status := rec.status
	rec.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"releaseId": relUUID, "releaseRef": "R-1001", "status": status,
		"fromRing": from, "toRing": to, "tenants": tenants,
		"crossedToPromoted": crossed, "promotionId": "11111111-1111-1111-1111-111111111111",
		"promotionStatus": "COMPLETED", "blockers": map[string]string{},
		"targets": []map[string]any{{"tenantId": tenantUUID, "ring": to}},
	})
}

func (rec *transitionRecorder) handlerDetail(w http.ResponseWriter, _ *http.Request) {
	rec.mu.Lock()
	status := rec.status
	ring := rec.rings[rec.ringIdx]
	rec.mu.Unlock()
	detail := detailWithStatus(status, status)
	targets, _ := detail["targets"].([]map[string]any)
	targets[0]["ring"] = ring
	_ = json.NewEncoder(w).Encode(detail)
}

func detailWithStatus(from, to string) map[string]any {
	detail := detailJSON()
	detail["status"] = to
	if to == "FAILED" || to == "ROLLED_BACK" {
		detail["status"] = from
	}
	return detail
}

// wireTransitionWorld mounts the recorder on the release world: the
// release list, detail, transitions, and promote routes.
func wireTransitionWorld(t *testing.T, starting string) (*fakeAPI, *transitionRecorder) {
	t.Helper()
	f := releaseWorld(t)
	rec := &transitionRecorder{
		status: starting,
		rings:  []string{"engineering", "tenant-pilot", "uat", "production"},
		refuse: map[string]map[string]string{},
	}
	f.handleJSON("/api/v1/releases", func() any {
		return map[string]any{"items": []map[string]any{{
			"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID, "status": starting,
		}}, "next_cursor": ""}
	})
	f.HandleFunc("/api/v1/releases/"+relUUID, rec.handlerDetail)
	f.HandleFunc("/api/v1/releases/"+relUUID+"/transitions", rec.handler)
	f.HandleFunc("/api/v1/releases/"+relUUID+"/promote", rec.handlerPromote)
	return f, rec
}

func TestReleaseTransitionCommand(t *testing.T) {
	f, rec := wireTransitionWorld(t, "DRAFT")
	err := runCLI(t, f, "release", "transition", "R-1001", "validating", "--reason", "smoke")
	if err != nil {
		t.Fatalf("transition: %v", err)
	}
	if len(rec.bodies) != 1 {
		t.Fatalf("transition POSTs = %d", len(rec.bodies))
	}
	body := rec.bodies[0]
	if body["to"] != "VALIDATING" || body["applicationId"] != appUUID || body["reason"] != "smoke" {
		t.Errorf("body = %v", body)
	}

	err = runCLI(t, f, "release", "transition", "R-1001", "SOMEWHERE")
	var usage *UsageError
	if !errors.As(err, &usage) {
		t.Errorf("unknown state: err = %v", err)
	}
	err = runCLI(t, f, "release", "transition", "R-1001", "ROLLED_BACK")
	if !errors.As(err, &usage) {
		t.Errorf("missing reason: err = %v", err)
	}
}

func TestReleasePromoteWalkToPromoted(t *testing.T) {
	f, rec := wireTransitionWorld(t, "DRAFT")
	if err := runCLI(t, f, "release", "promote", "R-1001", "--ring", "engineering"); err != nil {
		t.Fatalf("promote: %v", err)
	}
	wantPath := []string{"VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE"}
	if len(rec.bodies) != len(wantPath) {
		t.Fatalf("crossings = %d, want %d: %+v", len(rec.bodies), len(wantPath), rec.bodies)
	}
	for i, want := range wantPath {
		if rec.bodies[i]["to"] != want {
			t.Errorf("crossing %d = %v, want %s", i, rec.bodies[i]["to"], want)
		}
	}
	// The ring walk (S10-05): three governed hops to the final ring,
	// which carries the release across ACTIVE to PROMOTED.
	wantRings := [][2]string{
		{"engineering", "tenant-pilot"},
		{"tenant-pilot", "uat"},
		{"uat", "production"},
	}
	if len(rec.promotes) != len(wantRings) {
		t.Fatalf("hops = %d, want %d", len(rec.promotes), len(wantRings))
	}
	if !strings.Contains(f.out(), "PROMOTED") {
		t.Errorf("final detail missing:\n%s", f.out())
	}
}

// The refused ring hop answers the COMPLETE blocker list (every
// blocker named) and stops the walk (first-refusal-stops, exit 4).
func TestReleasePromoteRingHopBlockedSurfacesReasons(t *testing.T) {
	f, rec := wireTransitionWorld(t, "ACTIVE")
	rec.refuseRing = map[string]string{
		"gate:coverage":              "coverage 74% below the 80% threshold (evidence: https://ci.example/run/99)",
		"approval":                   "approval policy 'prod-release' round 3 is pending",
		"eligibility:" + tenantUUID2: "the ring's eligibility excludes this tenant: no testers in audience",
	}
	err := runCLI(t, f, "release", "promote", "R-1001")
	var failure *FailureError
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want FailureError", err)
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("exit code = %d, want validation", code)
	}
	msg := failure.Error()
	for _, want := range []string{
		"refused", "RELEASE_PROMOTION_BLOCKED", "reasons:",
		"gate:coverage:", "evidence", "approval:", "eligibility:" + tenantUUID2,
	} {
		if !strings.Contains(msg, want) {
			t.Errorf("message missing %q: %s", want, msg)
		}
	}
	if len(rec.promotes) != 1 {
		t.Errorf("the walk must stop at the first refused hop (hops = %d)", len(rec.promotes))
	}
}

// The selective staged rollout: --tenant narrows the hops to the
// named targets (absent moves every target).
func TestReleasePromoteSelectiveTenants(t *testing.T) {
	f, rec := wireTransitionWorld(t, "ACTIVE")
	if err := runCLI(t, f, "release", "promote", "R-1001", "--tenant", tenantUUID, "--tenant", tenantUUID2); err != nil {
		t.Fatalf("promote: %v", err)
	}
	if len(rec.promotes) == 0 {
		t.Fatal("no promote POST recorded")
	}
	tenants, _ := rec.promotes[0]["tenants"].([]any)
	if len(tenants) != 2 || tenants[0] != tenantUUID || tenants[1] != tenantUUID2 {
		t.Errorf("hop tenants = %v", rec.promotes[0]["tenants"])
	}
}

// AC2: a blocked promotion surfaces the blocker reasons and exits 4.
func TestReleasePromoteBlockedSurfacesReasons(t *testing.T) {
	f, rec := wireTransitionWorld(t, "APPROVAL_PENDING")
	rec.refuse["APPROVED"] = map[string]string{
		"approvals":     "2 of 3 required approvals recorded",
		"gate/coverage": "coverage 74% below the 80% threshold",
	}
	err := runCLI(t, f, "release", "promote", "R-1001")
	var failure *FailureError
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want FailureError", err)
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("exit code = %d, want validation", code)
	}
	msg := failure.Error()
	for _, want := range []string{"refused", "RELEASE_TRANSITION_BLOCKED", "reasons:", "approvals:", "gate/coverage:"} {
		if !strings.Contains(msg, want) {
			t.Errorf("message missing %q: %s", want, msg)
		}
	}
	if len(rec.bodies) != 1 {
		t.Errorf("the walk must stop at the first refusal (crossings = %d)", len(rec.bodies))
	}
}

func TestReleasePromoteRingAssertionAndTerminals(t *testing.T) {
	f, _ := wireTransitionWorld(t, "ACTIVE")
	err := runCLI(t, f, "release", "promote", "R-1001", "--ring", "production")
	var usage *UsageError
	if !errors.As(err, &usage) {
		t.Errorf("ring mismatch: err = %v", err)
	}
	if !strings.Contains(err.Error(), "creation ring") {
		t.Errorf("mismatch message should name the creation-ring assertion: %v", err)
	}

	f2, _ := wireTransitionWorld(t, "ROLLED_BACK")
	err = runCLI(t, f2, "release", "promote", "R-1001")
	var failure *FailureError
	if !errors.As(err, &failure) || !strings.Contains(failure.Error(), "terminal") {
		t.Errorf("terminal: err = %v", err)
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("terminal exit code = %d", code)
	}

	f3, rec := wireTransitionWorld(t, "PROMOTED")
	if err := runCLI(t, f3, "release", "promote", "R-1001"); err != nil {
		t.Errorf("no-op promote: %v", err)
	}
	if len(rec.bodies) != 0 {
		t.Errorf("no-op promote crossed %d states", len(rec.bodies))
	}
}

// The precheck read: the same evaluation the promote act runs,
// rendered as the one-screen blocker answer (issue #44's technical
// note), and the promotions timeline read.
func TestReleasePrecheckAndPromotionsReads(t *testing.T) {
	f, _ := wireTransitionWorld(t, "ACTIVE")
	f.handleJSON("/api/v1/releases/"+relUUID+"/promotion-precheck", func() any {
		return map[string]any{
			"promotable": false,
			"blockers": map[string]string{
				"gate:coverage": "coverage 74% below the 80% threshold (evidence: https://ci.example/run/99)",
				"approval":      "approval policy 'prod-release' round 3 is pending",
			},
			"gates": []map[string]any{{
				"gateType": "coverage", "required": true, "status": "FAILING",
				"evidenceUrl": "https://ci.example/run/99", "source": "SUBMITTED",
			}},
			"approval": map[string]any{
				"requirement": "PENDING", "policyName": "prod-release", "roundNumber": 3,
				"roundStatus": "PENDING",
				"waitedOn":    map[string]string{"step 1 (ROLE:ORG_ADMIN)": "awaiting approval (round 3)"},
			},
			"eligibility": []map[string]any{{
				"tenantId": tenantUUID, "admitted": true, "ring": "tenant-pilot",
			}},
		}
	})
	if err := runCLI(t, f, "release", "precheck", "R-1001"); err != nil {
		t.Fatalf("precheck: %v", err)
	}
	out := f.out()
	for _, want := range []string{
		"BLOCKED", "gate:coverage:", "https://ci.example/run/99", "approval:",
		"prod-release", "step 1 (ROLE:ORG_ADMIN)", "admitted",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("precheck render missing %q:\n%s", want, out)
		}
	}

	f.stdout.Reset()
	f.handleJSON("/api/v1/releases/"+relUUID+"/promotions", func() any {
		return map[string]any{
			"releaseId": relUUID, "releaseRef": "R-1001", "status": "ACTIVE",
			"promotions": []map[string]any{
				{
					"id": "11111111-1111-1111-1111-111111111111", "fromRing": "engineering",
					"toRing": "tenant-pilot", "tenants": []string{tenantUUID}, "status": "COMPLETED",
					"actorId": "u-1", "occurredAt": "2026-10-07T11:00:00Z",
				},
				{
					"id": "22222222-2222-2222-2222-222222222222", "fromRing": "tenant-pilot",
					"toRing": "uat", "tenants": []string{tenantUUID}, "status": "BLOCKED",
					"blockers": map[string]string{"gate:coverage": "coverage 74% below the 80% threshold"},
					"actorId":  "u-1", "occurredAt": "2026-10-07T12:00:00Z",
				},
			},
		}
	})
	if err := runCLI(t, f, "release", "promotions", "R-1001"); err != nil {
		t.Fatalf("promotions: %v", err)
	}
	out = f.out()
	for _, want := range []string{
		"engineering -> tenant-pilot: COMPLETED", "tenant-pilot -> uat: BLOCKED", "blockers:", "gate:coverage:",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("promotions render missing %q:\n%s", want, out)
		}
	}
}

func TestReleaseManifestModes(t *testing.T) {
	f, rec := wireTransitionWorld(t, "APPROVED")
	f.HandleFunc("/api/v1/releases/"+relUUID+"/manifest", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("applicationId") != appUUID {
			t.Errorf("manifest query = %q", r.URL.RawQuery)
		}
		switch r.URL.Query().Get("format") {
		case "yaml":
			w.Header().Set("Content-Type", "application/yaml")
			_, _ = w.Write([]byte("---\nreleaseRef: \"R-1001\"\n"))
		default:
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"releaseId": relUUID, "releaseRef": "R-1001", "applicationId": appUUID,
				"versionId": verUUID, "sha256": "cafebabe", "frozenAt": "2026-10-07T10:00:00Z",
				"frozenBy": "u-1", "manifest": map[string]any{"release": map[string]any{"releaseRef": "R-1001"}},
			})
		}
	})
	_ = rec

	if err := runCLI(t, f, "release", "manifest", "R-1001"); err != nil {
		t.Fatalf("manifest: %v", err)
	}
	if !strings.Contains(f.out(), "cafebabe") || !strings.Contains(f.out(), "releaseRef") {
		t.Errorf("human manifest:\n%s", f.out())
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "release", "manifest", "R-1001", "--yaml"); err != nil {
		t.Fatalf("manifest --yaml: %v", err)
	}
	if !strings.HasPrefix(strings.TrimSpace(f.out()), "---") {
		t.Errorf("yaml passthrough:\n%s", f.out())
	}

	f.stdout.Reset()
	if err := runCLI(t, f, "release", "manifest", "R-1001", "--json"); err != nil {
		t.Fatalf("manifest --json: %v", err)
	}
	var view map[string]any
	if err := json.Unmarshal([]byte(f.out()), &view); err != nil || view["sha256"] != "cafebabe" {
		t.Errorf("manifest --json:\n%s", f.out())
	}

	err := runCLI(t, f, "release", "manifest", "R-1001", "--yaml", "--json")
	var usage *UsageError
	if !errors.As(err, &usage) {
		t.Errorf("conflicting modes: err = %v", err)
	}
}
