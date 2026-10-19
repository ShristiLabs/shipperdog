package commands

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/ShristiLabs/shipperdog/cli/internal/exitcode"
)

// The S16-14 ota-command suite (issue #176): the create body and its
// grant-forwarding output, the upload's direct-to-storage PUT followed
// by the finalize, the attach ladder's composition, halt's mandatory
// reason, the status rendering, the tenant-addressed funnel read, and
// the exactly-once sdc1. print.

const (
	otaBundleUUID  = "12345678-1234-1234-1234-123456789012"
	otaVersionUUID = "87654321-4321-4321-4321-210987654321"
	otaTokenUUID   = "fedccccc-bbbb-aaaa-9999-888888888888"
)

// otaWorld is the fake OTA surface: one OTA-enabled application (with
// its tenant context), its release, and the OTA family's routes.
func otaWorld(t *testing.T) *fakeAPI {
	t.Helper()
	f := newFakeAPI(t)
	f.handleJSON("/api/v1/auth/me", func() any {
		return map[string]any{"id": "u-1", "orgId": "o-1"}
	})
	f.handleJSON("/api/v1/applications", func() any {
		return map[string]any{"items": []map[string]any{{"id": appUUID, "name": "Banking", "tenantId": tenantUUID}}}
	})
	f.handleJSON("/api/v1/applications/"+appUUID, func() any {
		return map[string]any{"id": appUUID, "name": "Banking", "tenantId": tenantUUID}
	})
	f.handleJSON("/api/v1/releases/"+relUUID, func() any {
		return map[string]any{
			"id": relUUID, "releaseRef": "R-1001", "applicationId": appUUID, "versionId": verUUID,
			"status": "ACTIVE", "ring": "production", "targets": []map[string]any{},
			"createdBy": "u-1", "createdAt": "2026-10-19T09:30:00Z", "updatedAt": "2026-10-19T09:30:00Z",
		}
	})
	f.start()
	return f
}

func TestOtaCreateCommandBodyAndGrantOutput(t *testing.T) {
	f := otaWorld(t)
	var mu sync.Mutex
	var bodies []map[string]any
	var idemKeys []string
	f.HandleFunc("/api/v1/ota/bundles", func(w http.ResponseWriter, r *http.Request) {
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
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": otaBundleUUID, "applicationId": appUUID, "versionId": otaVersionUUID,
			"bundleVersion": "1.4.0", "platformTag": "rn", "forced": true, "minAppVersion": "7.12.0",
			"status": "UPLOADING",
			"upload": map[string]any{"artifactId": otaBundleUUID, "uploadUrl": f.storage.URL + "/grant", "expiresAt": "2026-10-19T19:00:00Z"},
		})
	})

	if err := runCLI(t, f, "ota", "create", "Banking", "1.4.0",
		"--platform-tag", "rn", "--file-name", "bundle.zip",
		"--forced", "--min-app-version", "7.12.0", "--size-hint", "2048"); err != nil {
		t.Fatalf("create: %v", err)
	}
	if len(bodies) != 1 {
		t.Fatalf("creation POSTs = %d", len(bodies))
	}
	if idemKeys[0] == "" {
		t.Error("creation carried no Idempotency-Key")
	}
	body := bodies[0]
	if body["applicationId"] != appUUID || body["bundleVersion"] != "1.4.0" ||
		body["platformTag"] != "rn" || body["fileName"] != "bundle.zip" ||
		body["forced"] != true || body["minAppVersion"] != "7.12.0" || body["sizeHint"] != float64(2048) {
		t.Errorf("body = %v", body)
	}
	out := f.out()
	for _, want := range []string{otaBundleUUID, otaVersionUUID, "/grant", "ota upload"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}
}

func TestOtaUploadCommandStreamsAndFinalizes(t *testing.T) {
	f := otaWorld(t)
	dir := t.TempDir()
	filePath := filepath.Join(dir, "bundle.zip")
	payload := []byte("bundle-bytes")
	if err := os.WriteFile(filePath, payload, 0o600); err != nil {
		t.Fatalf("writing fixture: %v", err)
	}

	var finalizeBodies []map[string]any
	f.HandleFunc("/api/v1/ota/bundles/"+otaBundleUUID+"/finalize", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("finalize method = %s", r.Method)
		}
		if r.Header.Get("Idempotency-Key") == "" {
			t.Error("finalize carried no Idempotency-Key")
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		finalizeBodies = append(finalizeBodies, body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"created": true,
			"bundle": map[string]any{
				"id": otaBundleUUID, "bundleVersion": "1.4.0", "platformTag": "rn", "status": "SIGNED",
				"sha256": "abc123", "sizeBytes": len(payload), "signature": "sig", "keyId": "k-1",
				"createdBy": "u-1", "createdAt": "2026-10-19T18:00:00Z",
			},
		})
	})

	if err := runCLI(t, f, "ota", "upload", filePath,
		"--bundle", otaBundleUUID, "--version", otaVersionUUID,
		"--url", f.storage.URL+"/grant"); err != nil {
		t.Fatalf("upload: %v", err)
	}
	if len(f.storagePuts) != 1 || string(f.storagePuts[0]) != string(payload) {
		t.Errorf("storage PUTs = %d (%q)", len(f.storagePuts), f.storagePuts)
	}
	if len(finalizeBodies) != 1 || finalizeBodies[0]["versionId"] != otaVersionUUID {
		t.Errorf("finalize bodies = %v", finalizeBodies)
	}
	out := f.out()
	for _, want := range []string{"SIGNED", "abc123", "k-1"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}
}

func TestOtaPromoteCommandAttachesLadder(t *testing.T) {
	f := otaWorld(t)
	var bodies []map[string]any
	f.HandleFunc("/api/v1/ota/rollouts", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s", r.Method)
		}
		if r.Header.Get("Idempotency-Key") == "" {
			t.Error("attach carried no Idempotency-Key")
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		bodies = append(bodies, body)
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"releaseId": relUUID, "applicationId": appUUID, "mode": "AUTOMATIC",
			"status": "RUNNING", "currentStage": 1, "planSalt": "salt",
			"stages": []map[string]any{
				{"position": 1, "basisPoints": 1000, "status": "REACHED", "reachedAt": "2026-10-19T18:01:00Z"},
				{"position": 2, "basisPoints": 10000, "status": "PENDING"},
			},
			"timeline": []map[string]any{{"action": "COMMENCED", "stagePosition": 1, "actorId": "u-1", "occurredAt": "2026-10-19T18:01:00Z"}},
		})
	})

	if err := runCLI(t, f, "ota", "promote", relUUID, "--app", "Banking",
		"--mode", " automatic ", "--stages", "1000,10000=full fleet"); err != nil {
		t.Fatalf("promote: %v", err)
	}
	if len(bodies) != 1 {
		t.Fatalf("attach POSTs = %d", len(bodies))
	}
	body := bodies[0]
	if body["applicationId"] != appUUID || body["releaseId"] != relUUID || body["mode"] != "AUTOMATIC" {
		t.Errorf("body = %v", body)
	}
	stages, ok := body["stages"].([]any)
	if !ok || len(stages) != 2 {
		t.Fatalf("stages = %v", body["stages"])
	}
	first, _ := stages[0].(map[string]any)
	second, _ := stages[1].(map[string]any)
	if first["basisPoints"] != float64(1000) {
		t.Errorf("stage 1 = %v", first)
	}
	if _, has := first["note"]; has {
		t.Errorf("empty note not omitted: %v", first)
	}
	if second["basisPoints"] != float64(10000) || second["note"] != "full fleet" {
		t.Errorf("stage 2 = %v", second)
	}
	out := f.out()
	for _, want := range []string{"RUNNING", "REACHED", "COMMENCED"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}

	// An illegal ladder refuses locally (exit 2), before any request.
	f.stdout.Reset()
	err := runCLI(t, f, "ota", "promote", relUUID, "--app", "Banking", "--mode", "automatic", "--stages", "5000,1000,10000")
	if err == nil {
		t.Fatal("descending ladder accepted")
	}
	if code := ExitCode(err); code != exitcode.Usage {
		t.Errorf("descending ladder exit = %d, want usage", code)
	}
}

func TestOtaHaltRequiresReasonAndSendsBody(t *testing.T) {
	f := otaWorld(t)
	var bodies []map[string]any
	f.HandleFunc("/api/v1/ota/rollouts/"+relUUID+"/halt", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		bodies = append(bodies, body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"releaseId": relUUID, "status": "HALTED", "mode": "AUTOMATIC"})
	})

	// An empty --reason (past cobra's required-flag check) refuses
	// locally as a usage error (exit 2) - the server would 422 anyway.
	err := runCLI(t, f, "ota", "halt", relUUID, "--app", "Banking", "--reason", "  ")
	if err == nil {
		t.Fatal("halt with an empty --reason accepted")
	}
	if code := ExitCode(err); code != exitcode.Usage {
		t.Errorf("empty-reason exit = %d, want usage", code)
	}
	if len(bodies) != 0 {
		t.Fatalf("a refused halt still posted %d body(ies)", len(bodies))
	}

	if err := runCLI(t, f, "ota", "halt", relUUID, "--app", "Banking", "--reason", "crash spike on stage 1"); err != nil {
		t.Fatalf("halt: %v", err)
	}
	if len(bodies) != 1 || bodies[0]["applicationId"] != appUUID || bodies[0]["reason"] != "crash spike on stage 1" {
		t.Errorf("halt bodies = %v", bodies)
	}
	if out := f.out(); !strings.Contains(out, "HALTED") {
		t.Errorf("output missing HALTED:\n%s", out)
	}
}

func TestOtaResumeCommand(t *testing.T) {
	f := otaWorld(t)
	var bodies []map[string]any
	f.HandleFunc("/api/v1/ota/rollouts/"+relUUID+"/resume", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		bodies = append(bodies, body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"releaseId": relUUID, "status": "RUNNING", "mode": "AUTOMATIC"})
	})

	if err := runCLI(t, f, "ota", "resume", relUUID, "--app", "Banking", "--reason", "fix shipped"); err != nil {
		t.Fatalf("resume: %v", err)
	}
	if len(bodies) != 1 || bodies[0]["reason"] != "fix shipped" || bodies[0]["applicationId"] != appUUID {
		t.Errorf("resume bodies = %v", bodies)
	}
	if out := f.out(); !strings.Contains(out, "RUNNING") {
		t.Errorf("output missing RUNNING:\n%s", out)
	}
}

func TestOtaStatusCommandRendersPlan(t *testing.T) {
	f := otaWorld(t)
	f.handleJSON("/api/v1/ota/rollouts/"+relUUID, func() any {
		return map[string]any{
			"releaseId": relUUID, "applicationId": appUUID, "mode": "CHECKPOINT",
			"status": "PAUSED", "currentStage": 1, "planSalt": "salt",
			"stages": []map[string]any{
				{"position": 1, "basisPoints": 2500, "note": "inner ring", "status": "REACHED", "reachedAt": "2026-10-19T18:01:00Z"},
				{"position": 2, "basisPoints": 10000, "status": "PENDING"},
			},
			"timeline": []map[string]any{
				{"action": "COMMENCED", "occurredAt": "2026-10-19T18:01:00Z", "actorId": "u-1"},
				{"action": "PAUSED", "detail": "operator break", "occurredAt": "2026-10-19T18:05:00Z", "actorId": "u-1"},
			},
		}
	})

	if err := runCLI(t, f, "ota", "status", relUUID, "--app", "Banking"); err != nil {
		t.Fatalf("status: %v", err)
	}
	out := f.out()
	for _, want := range []string{"PAUSED", "CHECKPOINT", "inner ring", "COMMENCED", "operator break"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}
}

func TestOtaFunnelCommandTenantAddressed(t *testing.T) {
	f := otaWorld(t)
	f.handleJSON("/api/v1/ota/bundles", func() any {
		return map[string]any{"items": []map[string]any{{"id": otaBundleUUID, "bundleVersion": "1.4.0", "status": "RELEASED"}}}
	})
	funnelHits := 0
	f.HandleFunc("/api/v1/tenants/"+tenantUUID+"/dashboard/ota/bundles/"+otaBundleUUID+"/funnel", func(w http.ResponseWriter, r *http.Request) {
		funnelHits++
		if r.URL.Query().Get("ring") != "production" {
			t.Errorf("ring = %q", r.URL.Query().Get("ring"))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"bundleId": otaBundleUUID, "bundleVersion": "1.4.0", "releaseId": relUUID, "releaseRef": "R-1001",
			"stages":   map[string]any{"checks": 1000, "seen": 900, "downloads": 800, "applied": 700, "failed": 30, "rolledBack": 2},
			"failures": []map[string]any{{"reason": "checksum_mismatch", "count": 21}, {"reason": "apply_error", "count": 9}},
			"adoption": []map[string]any{{"day": "2026-10-19", "seenDevices": 900, "appliedDevices": 700, "adoptionRate": 0.778}},
		})
	})

	if err := runCLI(t, f, "ota", "funnel", "1.4.0", "--app", "Banking", "--ring", "production"); err != nil {
		t.Fatalf("funnel: %v", err)
	}
	if funnelHits != 1 {
		t.Errorf("funnel reads = %d", funnelHits)
	}
	out := f.out()
	for _, want := range []string{"R-1001", "ring production", "1000", "checksum_mismatch", "77.8%"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}

	// Without --app the command refuses: the route is tenant-addressed.
	f.stdout.Reset()
	if err := runCLI(t, f, "ota", "funnel", "1.4.0"); err == nil {
		t.Error("funnel without --app accepted")
	}
}

func TestOtaTokenIssuePrintsValueOnce(t *testing.T) {
	f := otaWorld(t)
	f.HandleFunc("/api/v1/ota/app-tokens", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s", r.Method)
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["applicationId"] != appUUID || body["name"] != "prod-fleet" {
			t.Errorf("body = %v", body)
		}
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"token": map[string]any{"id": otaTokenUUID, "applicationId": appUUID, "name": "prod-fleet", "createdBy": "u-1"},
			"value": "sdc1_live_never_again",
		})
	})

	if err := runCLI(t, f, "ota", "token", "issue", "Banking", "--name", "prod-fleet"); err != nil {
		t.Fatalf("token issue: %v", err)
	}
	// The plain output is the VALUE ALONE on stdout - scripts capture it
	// directly, and the value never appears a second time.
	if got, want := f.out(), "sdc1_live_never_again\n"; got != want {
		t.Errorf("stdout = %q, want %q", got, want)
	}

	// --name is required locally.
	if err := runCLI(t, f, "ota", "token", "issue", "Banking"); err == nil {
		t.Error("token issue without --name accepted")
	}
}

// otaChainWorld is the full fake OTA surface with mutable state so the
// eight-command chain can walk it end to end: the draft the create
// answers becomes the SIGNED bundle the finalize lands, the rollout
// plan's standing moves HALTED -> RUNNING, and the funnel reads the
// released bundle.
type otaChainWorld struct {
	*fakeAPI
	mu          sync.Mutex
	uploadGrant string
	rolloutView map[string]any
}

func otaChainWorldOf(t *testing.T) *otaChainWorld {
	t.Helper()
	world := &otaChainWorld{fakeAPI: otaWorld(t)}
	world.rolloutView = map[string]any{"releaseId": relUUID, "applicationId": appUUID, "mode": "AUTOMATIC", "status": "RUNNING", "currentStage": 1}

	world.HandleFunc("/api/v1/ota/bundles", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			// The listing serves the finalized bundle for funnel's label
			// resolution.
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"items": []map[string]any{{"id": otaBundleUUID, "bundleVersion": "1.4.0", "status": "RELEASED"}},
			})
			return
		}
		world.mu.Lock()
		world.uploadGrant = world.storage.URL + "/grant"
		granted := world.uploadGrant
		world.mu.Unlock()
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": otaBundleUUID, "applicationId": appUUID, "versionId": otaVersionUUID,
			"bundleVersion": "1.4.0", "platformTag": "rn", "status": "UPLOADING",
			"upload": map[string]any{"artifactId": otaBundleUUID, "uploadUrl": granted, "expiresAt": "2026-10-19T19:00:00Z"},
		})
	})
	world.HandleFunc("/api/v1/ota/bundles/"+otaBundleUUID, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"id": otaBundleUUID, "bundleVersion": "1.4.0", "status": "RELEASED", "applicationId": appUUID})
	})
	world.HandleFunc("/api/v1/ota/bundles/"+otaBundleUUID+"/finalize", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"created": true,
			"bundle": map[string]any{"id": otaBundleUUID, "bundleVersion": "1.4.0", "platformTag": "rn",
				"status": "SIGNED", "sha256": "abc123", "sizeBytes": 12, "signature": "sig", "keyId": "k-1",
				"releaseId": relUUID, "createdBy": "u-1", "createdAt": "2026-10-19T18:00:00Z"},
		})
	})
	world.HandleFunc("/api/v1/ota/rollouts", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(world.rolloutView)
	})
	world.HandleFunc("/api/v1/ota/rollouts/"+relUUID, func(w http.ResponseWriter, r *http.Request) {
		world.mu.Lock()
		view := world.rolloutView
		world.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(view)
	})
	world.HandleFunc("/api/v1/ota/rollouts/"+relUUID+"/halt", func(w http.ResponseWriter, r *http.Request) {
		world.mu.Lock()
		world.rolloutView["status"] = "HALTED"
		view := world.rolloutView
		world.mu.Unlock()
		_ = json.NewEncoder(w).Encode(view)
	})
	world.HandleFunc("/api/v1/ota/rollouts/"+relUUID+"/resume", func(w http.ResponseWriter, r *http.Request) {
		world.mu.Lock()
		world.rolloutView["status"] = "RUNNING"
		view := world.rolloutView
		world.mu.Unlock()
		_ = json.NewEncoder(w).Encode(view)
	})
	world.HandleFunc("/api/v1/tenants/"+tenantUUID+"/dashboard/ota/bundles/"+otaBundleUUID+"/funnel", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"bundleId": otaBundleUUID, "bundleVersion": "1.4.0", "releaseId": relUUID, "releaseRef": "R-1001",
			"stages": map[string]any{"checks": 10, "seen": 9, "downloads": 8, "applied": 7, "failed": 0, "rolledBack": 0},
		})
	})
	world.HandleFunc("/api/v1/ota/app-tokens", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"token": map[string]any{"id": otaTokenUUID, "applicationId": appUUID, "name": "chain"},
			"value": "sdc1_chain_once",
		})
	})
	return world
}

// TestOtaFullChainAgainstFakeWorld is the in-process substitute for the
// issue's live compose happy path (AC 1): all eight commands, in the
// issue's own order, against one stateful fake surface - create ->
// upload (direct-to-storage PUT + finalize) -> promote -> status ->
// halt -> resume -> funnel -> token issue.
func TestOtaFullChainAgainstFakeWorld(t *testing.T) {
	world := otaChainWorldOf(t)

	if err := runCLI(t, world.fakeAPI, "ota", "create", "Banking", "1.4.0", "--platform-tag", "rn", "--file-name", "bundle.zip"); err != nil {
		t.Fatalf("create: %v", err)
	}
	world.mu.Lock()
	grant := world.uploadGrant
	world.mu.Unlock()
	if grant == "" {
		t.Fatal("create did not answer an upload grant")
	}

	dir := t.TempDir()
	filePath := filepath.Join(dir, "bundle.zip")
	payload := []byte("chain-payload")
	if err := os.WriteFile(filePath, payload, 0o600); err != nil {
		t.Fatalf("fixture: %v", err)
	}
	if err := runCLI(t, world.fakeAPI, "ota", "upload", filePath, "--bundle", otaBundleUUID, "--version", otaVersionUUID, "--url", grant); err != nil {
		t.Fatalf("upload: %v", err)
	}
	if len(world.storagePuts) != 1 || string(world.storagePuts[0]) != string(payload) {
		t.Errorf("storage PUTs = %q", world.storagePuts)
	}

	if err := runCLI(t, world.fakeAPI, "ota", "promote", relUUID, "--app", "Banking", "--mode", "automatic", "--stages", "1000,10000"); err != nil {
		t.Fatalf("promote: %v", err)
	}
	if err := runCLI(t, world.fakeAPI, "ota", "status", relUUID, "--app", "Banking"); err != nil {
		t.Fatalf("status: %v", err)
	}
	if err := runCLI(t, world.fakeAPI, "ota", "halt", relUUID, "--app", "Banking", "--reason", "chain check"); err != nil {
		t.Fatalf("halt: %v", err)
	}
	if !strings.Contains(world.out(), "HALTED") {
		t.Errorf("halt did not render HALTED:\n%s", world.out())
	}
	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "resume", relUUID, "--app", "Banking"); err != nil {
		t.Fatalf("resume: %v", err)
	}
	if !strings.Contains(world.out(), "RUNNING") {
		t.Errorf("resume did not render RUNNING:\n%s", world.out())
	}
	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "funnel", "1.4.0", "--app", "Banking"); err != nil {
		t.Fatalf("funnel: %v", err)
	}
	if !strings.Contains(world.out(), "R-1001") {
		t.Errorf("funnel did not render the release ref:\n%s", world.out())
	}
	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "token", "issue", "Banking", "--name", "chain"); err != nil {
		t.Fatalf("token issue: %v", err)
	}
	if got, want := world.out(), "sdc1_chain_once\n"; got != want {
		t.Errorf("token stdout = %q, want %q", got, want)
	}
}

// TestOtaJSONModesCarryViews pins every --json branch: the machine
// output is the served view verbatim, and the sdc1. value appears
// exactly once in the JSON too.
func TestOtaJSONModesCarryViews(t *testing.T) {
	world := otaChainWorldOf(t)

	if err := runCLI(t, world.fakeAPI, "ota", "create", "Banking", "1.4.0", "--platform-tag", "rn", "--file-name", "bundle.zip", "--json"); err != nil {
		t.Fatalf("create --json: %v", err)
	}
	var draft map[string]any
	if err := json.Unmarshal([]byte(world.out()), &draft); err != nil {
		t.Fatalf("create --json decode: %v\n%s", err, world.out())
	}
	if draft["id"] != otaBundleUUID || draft["upload"] == nil {
		t.Errorf("draft json = %v", draft)
	}

	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "promote", relUUID, "--app", "Banking", "--mode", "checkpoint", "--stages", "10000", "--json"); err != nil {
		t.Fatalf("promote --json: %v", err)
	}
	var plan map[string]any
	if err := json.Unmarshal([]byte(world.out()), &plan); err != nil {
		t.Fatalf("promote --json decode: %v\n%s", err, world.out())
	}
	if plan["releaseId"] != relUUID {
		t.Errorf("plan json = %v", plan)
	}

	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "status", relUUID, "--app", "Banking", "--json"); err != nil {
		t.Fatalf("status --json: %v", err)
	}
	var standing map[string]any
	if err := json.Unmarshal([]byte(world.out()), &standing); err != nil {
		t.Fatalf("status --json decode: %v\n%s", err, world.out())
	}
	if standing["status"] != "RUNNING" {
		t.Errorf("standing json = %v", standing)
	}

	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "funnel", otaBundleUUID, "--app", "Banking", "--json"); err != nil {
		t.Fatalf("funnel --json: %v", err)
	}
	var funnel map[string]any
	if err := json.Unmarshal([]byte(world.out()), &funnel); err != nil {
		t.Fatalf("funnel --json decode: %v\n%s", err, world.out())
	}
	if funnel["bundleId"] != otaBundleUUID {
		t.Errorf("funnel json = %v", funnel)
	}

	world.stdout.Reset()
	if err := runCLI(t, world.fakeAPI, "ota", "token", "issue", "Banking", "--name", "chain", "--json"); err != nil {
		t.Fatalf("token --json: %v", err)
	}
	issued := world.out()
	if count := strings.Count(issued, "sdc1_chain_once"); count != 1 {
		t.Errorf("value appears %d times in issued json:\n%s", count, issued)
	}
}

// TestOtaLocalRefusals pins the invocation mistakes the CLI refuses
// before any request leaves: a missing upload file, a non-vocabulary
// mode, and blank authoring flags.
func TestOtaLocalRefusals(t *testing.T) {
	world := otaChainWorldOf(t)

	err := runCLI(t, world.fakeAPI, "ota", "upload", "/nonexistent/bundle.zip",
		"--bundle", otaBundleUUID, "--version", otaVersionUUID, "--url", "http://storage/grant")
	if err == nil {
		t.Fatal("missing upload file accepted")
	}
	if code := ExitCode(err); code != exitcode.Usage {
		t.Errorf("missing-file exit = %d, want usage", code)
	}

	err = runCLI(t, world.fakeAPI, "ota", "promote", relUUID, "--app", "Banking", "--mode", "auto", "--stages", "10000")
	if err == nil {
		t.Fatal("non-vocabulary mode accepted")
	}
	if code := ExitCode(err); code != exitcode.Usage {
		t.Errorf("bad-mode exit = %d, want usage", code)
	}
	if !strings.Contains(err.Error(), "AUTOMATIC") || !strings.Contains(err.Error(), "CHECKPOINT") {
		t.Errorf("bad-mode error does not name the vocabulary: %v", err)
	}

	err = runCLI(t, world.fakeAPI, "ota", "create", "Banking", "1.4.0", "--platform-tag", "  ", "--file-name", "bundle.zip")
	if err == nil || ExitCode(err) != exitcode.Usage {
		t.Errorf("blank platform tag: %v (exit %d)", err, ExitCode(err))
	}
	err = runCLI(t, world.fakeAPI, "ota", "create", "Banking", "1.4.0", "--platform-tag", "rn", "--file-name", " ")
	if err == nil || ExitCode(err) != exitcode.Usage {
		t.Errorf("blank file name: %v (exit %d)", err, ExitCode(err))
	}
}

// TestOtaFunnelWithoutTenantContext pins the tenant-addressed route's
// honest refusal when the application row carries no tenant context.
func TestOtaFunnelWithoutTenantContext(t *testing.T) {
	f := newFakeAPI(t)
	f.handleJSON("/api/v1/auth/me", func() any { return map[string]any{"id": "u-1", "orgId": "o-1"} })
	f.handleJSON("/api/v1/applications", func() any {
		return map[string]any{"items": []map[string]any{{"id": appUUID, "name": "Banking"}}}
	})
	f.handleJSON("/api/v1/applications/"+appUUID, func() any {
		return map[string]any{"id": appUUID, "name": "Banking"}
	})
	f.start()

	err := runCLI(t, f, "ota", "funnel", otaBundleUUID, "--app", "Banking")
	if err == nil {
		t.Fatal("funnel without tenant context accepted")
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("no-tenant exit = %d, want validation", code)
	}
}

// TestOtaHaltSurfacesApiReasonList pins the refusal rendering: a 409
// problem+json with an errors member becomes the CI-friendly failure
// (exit 4) naming every reason.
func TestOtaHaltSurfacesApiReasonList(t *testing.T) {
	f := otaWorld(t)
	f.HandleFunc("/api/v1/ota/rollouts/"+relUUID+"/halt", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"type":"https://api.shipperdog.dev/problems/conflict","title":"Conflict","status":409,"code":"OTA_ROLLOUT_STATE_CONFLICT","detail":"the plan is not haltable","trace_id":"t-1","errors":{"status":"COMPLETE plans never halt"}}`))
	})

	err := runCLI(t, f, "ota", "halt", relUUID, "--app", "Banking", "--reason", "try")
	if err == nil {
		t.Fatal("refused halt reported success")
	}
	if code := ExitCode(err); code != exitcode.Validation {
		t.Errorf("refused halt exit = %d, want validation", code)
	}
	if !strings.Contains(err.Error(), "OTA_ROLLOUT_STATE_CONFLICT") || !strings.Contains(err.Error(), "COMPLETE plans never halt") {
		t.Errorf("refusal does not carry the reason list: %v", err)
	}
}

func TestShareOfZeroDenominator(t *testing.T) {
	if got := shareOf(0, 0); got != "-" {
		t.Errorf("shareOf(0,0) = %q, want -", got)
	}
	if got := shareOf(3, 0); got != "-" {
		t.Errorf("shareOf(3,0) = %q, want -", got)
	}
	if got := shareOf(1, 2); got != "50.0%" {
		t.Errorf("shareOf(1,2) = %q", got)
	}
}
