package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// The S16-14 OTA client suite (issue #176): the stage-ladder parser's
// contract mirror, the designated POSTs' bodies and idempotency keys,
// the page walk of the bundle listing, the control-route composition,
// the tenant-addressed funnel read, and bundle resolution (uuid read
// vs label walk). The endpoint templates themselves are pinned against
// docs/openapi/baseline.yaml by endpoints_test.go.

const (
	otaAppUUID  = "11111111-2222-3333-4444-555555555555"
	otaRelUUID  = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	otaBunUUID  = "12345678-1234-1234-1234-123456789012"
	otaVerUUID  = "87654321-4321-4321-4321-210987654321"
	otaTokUUID  = "fedccccc-bbbb-aaaa-9999-888888888888"
	otaTenantID = "99999999-1111-2222-3333-444444444444"
)

func TestParseOtaStages(t *testing.T) {
	got, err := ParseOtaStages("1000,5000,10000")
	if err != nil {
		t.Fatalf("valid ladder rejected: %v", err)
	}
	if len(got) != 3 || got[0].BasisPoints != 1000 || got[2].BasisPoints != 10000 {
		t.Errorf("ladder = %+v", got)
	}

	got, err = ParseOtaStages(" 250 = inner ring , 10000=full ")
	if err != nil {
		t.Fatalf("noted ladder rejected: %v", err)
	}
	if len(got) != 2 || got[0].Note != "inner ring" || got[1].Note != "full" {
		t.Errorf("notes not carried: %+v", got)
	}

	// A single full-fleet stage is the smallest legal ladder.
	if got, err = ParseOtaStages("10000"); err != nil || len(got) != 1 {
		t.Errorf("single 10000 ladder: %+v, %v", got, err)
	}

	for _, bad := range []string{
		"",            // empty
		" ",           // blank entry
		"abc",         // non-integer
		"0",           // below 1
		"10001",       // above 10000
		"5000,5000",   // not strictly ascending
		"9000,5000",   // descending
		"1000,5000",   // final stage is not 10000
		"1000,,10000", // hole
	} {
		if _, err := ParseOtaStages(bad); err == nil {
			t.Errorf("ParseOtaStages(%q) accepted an invalid ladder", bad)
		}
	}
}

func TestOtaModeVocabulary(t *testing.T) {
	for _, mode := range []string{"AUTOMATIC", "CHECKPOINT"} {
		if !ValidOtaMode(mode) {
			t.Errorf("%s rejected", mode)
		}
	}
	for _, bad := range []string{"", "AUTO", "manual", "CHECKPOINT "} {
		if ValidOtaMode(bad) {
			t.Errorf("%q accepted", bad)
		}
	}
	if NormalizeOtaMode(" automatic ") != "AUTOMATIC" {
		t.Error("mode not normalized like the server does")
	}
}

func TestCreateOtaBundleBodyAndIdempotency(t *testing.T) {
	var gotPath string
	var gotBody map[string]any
	var gotIdem string
	forced := true
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotIdem = r.Header.Get("Idempotency-Key")
		body := make(map[string]any)
		_ = json.NewDecoder(r.Body).Decode(&body)
		gotBody = body
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": otaBunUUID, "applicationId": otaAppUUID, "versionId": otaVerUUID,
			"bundleVersion": "1.4.0", "platformTag": "rn", "forced": true, "status": "UPLOADING",
			"upload": map[string]any{"artifactId": otaBunUUID, "uploadUrl": "http://storage/put", "expiresAt": "2026-10-19T19:00:00Z"},
		})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	draft, err := client.CreateOtaBundle(context.Background(), CreateOtaBundleRequest{
		ApplicationID: otaAppUUID,
		BundleVersion: "1.4.0",
		PlatformTag:   "rn",
		FileName:      "bundle.zip",
		Forced:        &forced,
		MinAppVersion: "7.12.0",
		SizeHint:      2048,
	})
	if err != nil {
		t.Fatalf("CreateOtaBundle: %v", err)
	}
	if gotPath != pathOtaBundles {
		t.Errorf("path = %q", gotPath)
	}
	if gotIdem == "" {
		t.Error("create carried no Idempotency-Key")
	}
	if gotBody["applicationId"] != otaAppUUID || gotBody["bundleVersion"] != "1.4.0" ||
		gotBody["platformTag"] != "rn" || gotBody["fileName"] != "bundle.zip" ||
		gotBody["forced"] != true || gotBody["minAppVersion"] != "7.12.0" || gotBody["sizeHint"] != float64(2048) {
		t.Errorf("body = %v", gotBody)
	}
	// forced:false (an unset pointer) must be omitted, not sent.
	_, err = client.CreateOtaBundle(context.Background(), CreateOtaBundleRequest{
		ApplicationID: otaAppUUID, BundleVersion: "1.4.1", PlatformTag: "rn", FileName: "b.zip",
	})
	if err != nil {
		t.Fatalf("unforced create: %v", err)
	}
	if _, present := gotBody["forced"]; present {
		t.Errorf("forced member unexpectedly present: %v", gotBody)
	}
	if draft.Upload == nil || draft.Upload.UploadURL != "http://storage/put" || draft.VersionID != otaVerUUID {
		t.Errorf("draft grant = %+v", draft.Upload)
	}
}

func TestFinalizeOtaBundlePathAndBody(t *testing.T) {
	var gotPath string
	var gotBody map[string]any
	var gotIdem string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotIdem = r.Header.Get("Idempotency-Key")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"created": true,
			"bundle":  map[string]any{"id": otaBunUUID, "status": "SIGNED", "sha256": "abc123", "keyId": "k-1", "sizeBytes": 2048},
		})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	final, err := client.FinalizeOtaBundle(context.Background(), otaBunUUID, otaVerUUID)
	if err != nil {
		t.Fatalf("FinalizeOtaBundle: %v", err)
	}
	if gotPath != "/api/v1/ota/bundles/"+otaBunUUID+"/finalize" {
		t.Errorf("path = %q", gotPath)
	}
	if gotBody["versionId"] != otaVerUUID {
		t.Errorf("body = %v", gotBody)
	}
	if gotIdem == "" {
		t.Error("finalize carried no Idempotency-Key")
	}
	if !final.Created || final.Bundle == nil || final.Bundle.Status != "SIGNED" {
		t.Errorf("final = %+v", final)
	}
}

func TestListOtaBundlesPaginationAndStatusFilter(t *testing.T) {
	var calls int
	var gotStatus string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != pathOtaBundles {
			t.Errorf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("applicationId") != otaAppUUID {
			t.Errorf("applicationId = %q", r.URL.Query().Get("applicationId"))
		}
		gotStatus = r.URL.Query().Get("status")
		cursor := r.URL.Query().Get("cursor")
		w.Header().Set("Content-Type", "application/json")
		if cursor == "" {
			_, _ = w.Write([]byte(`{"items":[{"id":"b1","bundleVersion":"1.0.0"}],"next_cursor":"c2"}`))
			return
		}
		_, _ = w.Write([]byte(`{"items":[{"id":"b2","bundleVersion":"1.1.0"}],"next_cursor":""}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	bundles, err := client.ListOtaBundles(context.Background(), otaAppUUID, "SIGNED")
	if err != nil {
		t.Fatalf("ListOtaBundles: %v", err)
	}
	if len(bundles) != 2 || bundles[0].ID != "b1" || bundles[1].BundleVersion != "1.1.0" {
		t.Errorf("bundles = %+v", bundles)
	}
	if calls != 2 {
		t.Errorf("calls = %d, want 2", calls)
	}
	if gotStatus != "SIGNED" {
		t.Errorf("status filter = %q", gotStatus)
	}
}

func TestIssueOtaAppToken(t *testing.T) {
	var gotPath string
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"token": map[string]any{"id": otaTokUUID, "applicationId": otaAppUUID, "name": "prod"},
			"value": "sdc1_value_once",
		})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	issued, err := client.IssueOtaAppToken(context.Background(), otaAppUUID, "prod")
	if err != nil {
		t.Fatalf("IssueOtaAppToken: %v", err)
	}
	if gotPath != pathOtaAppTokens {
		t.Errorf("path = %q", gotPath)
	}
	if gotBody["applicationId"] != otaAppUUID || gotBody["name"] != "prod" {
		t.Errorf("body = %v", gotBody)
	}
	if issued.Value != "sdc1_value_once" || issued.Token == nil || issued.Token.ID != otaTokUUID {
		t.Errorf("issued = %+v", issued)
	}
}

func TestAttachRolloutBodyAndModeNormalization(t *testing.T) {
	var gotPath string
	var gotBody AttachOtaRolloutRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"releaseId": otaRelUUID, "applicationId": otaAppUUID, "mode": "AUTOMATIC",
			"status": "RUNNING", "currentStage": 1, "planSalt": "salt",
			"stages":   []map[string]any{{"position": 1, "basisPoints": 1000, "status": "REACHED"}},
			"timeline": []map[string]any{{"action": "COMMENCED", "stagePosition": 1}},
		})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	view, err := client.AttachOtaRollout(context.Background(), AttachOtaRolloutRequest{
		ApplicationID: otaAppUUID,
		ReleaseID:     otaRelUUID,
		Mode:          NormalizeOtaMode(" automatic "),
		Stages:        []OtaStageBody{{BasisPoints: 1000}, {BasisPoints: 10000, Note: "full"}},
	})
	if err != nil {
		t.Fatalf("AttachOtaRollout: %v", err)
	}
	if gotPath != pathOtaRollouts {
		t.Errorf("path = %q", gotPath)
	}
	if gotBody.Mode != "AUTOMATIC" || gotBody.ReleaseID != otaRelUUID || len(gotBody.Stages) != 2 {
		t.Errorf("body = %+v", gotBody)
	}
	if view.Status != "RUNNING" || len(view.Stages) != 1 || len(view.Timeline) != 1 {
		t.Errorf("view = %+v", view)
	}
}

func TestHaltAndResumeRolloutRoutes(t *testing.T) {
	var gotPath string
	var gotBody OtaControlRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"releaseId": otaRelUUID, "status": "HALTED"})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	if _, err := client.HaltOtaRollout(context.Background(), otaAppUUID, otaRelUUID, "crash spike"); err != nil {
		t.Fatalf("HaltOtaRollout: %v", err)
	}
	if gotPath != "/api/v1/ota/rollouts/"+otaRelUUID+"/halt" {
		t.Errorf("halt path = %q", gotPath)
	}
	if gotBody.ApplicationID != otaAppUUID || gotBody.Reason != "crash spike" {
		t.Errorf("halt body = %+v", gotBody)
	}

	if _, err := client.ResumeOtaRollout(context.Background(), otaAppUUID, otaRelUUID, ""); err != nil {
		t.Fatalf("ResumeOtaRollout: %v", err)
	}
	if gotPath != "/api/v1/ota/rollouts/"+otaRelUUID+"/resume" {
		t.Errorf("resume path = %q", gotPath)
	}
}

func TestGetOtaRolloutStatusRead(t *testing.T) {
	var gotQuery string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/ota/rollouts/"+otaRelUUID {
			t.Errorf("path = %q", r.URL.Path)
		}
		gotQuery = r.URL.Query().Get("applicationId")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"releaseId": otaRelUUID, "status": "RUNNING", "mode": "CHECKPOINT"})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	view, err := client.GetOtaRollout(context.Background(), otaAppUUID, otaRelUUID)
	if err != nil {
		t.Fatalf("GetOtaRollout: %v", err)
	}
	if gotQuery != otaAppUUID {
		t.Errorf("applicationId = %q", gotQuery)
	}
	if view.Mode != "CHECKPOINT" {
		t.Errorf("mode = %q", view.Mode)
	}
}

func TestGetOtaFunnelTenantAddressedRead(t *testing.T) {
	var gotPath, gotRing string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotRing = r.URL.Query().Get("ring")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"bundleId": otaBunUUID, "bundleVersion": "1.4.0", "releaseRef": "R-1001",
			"stages":   map[string]any{"checks": 100, "seen": 90, "downloads": 80, "applied": 70, "failed": 3, "rolledBack": 1},
			"failures": []map[string]any{{"reason": "checksum_mismatch", "count": 3}},
			"adoption": []map[string]any{{"day": "2026-10-19", "seenDevices": 90, "appliedDevices": 70, "adoptionRate": 0.78}},
		})
	}))
	defer server.Close()

	client := New(server.URL, "token")
	view, err := client.GetOtaFunnel(context.Background(), otaTenantID, otaBunUUID, "production")
	if err != nil {
		t.Fatalf("GetOtaFunnel: %v", err)
	}
	wantPath := "/api/v1/tenants/" + otaTenantID + "/dashboard/ota/bundles/" + otaBunUUID + "/funnel"
	if gotPath != wantPath {
		t.Errorf("path = %q, want %q", gotPath, wantPath)
	}
	if gotRing != "production" {
		t.Errorf("ring = %q", gotRing)
	}
	if view.Stages == nil || view.Stages.Checks != 100 || view.Stages.Applied != 70 {
		t.Errorf("stages = %+v", view.Stages)
	}
	if len(view.Failures) != 1 || len(view.Adoption) != 1 {
		t.Errorf("funnel extras = %+v %+v", view.Failures, view.Adoption)
	}
}

func TestResolveOtaBundleByUUIDAndLabel(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/api/v1/ota/bundles/"+otaBunUUID && r.Method == http.MethodGet:
			_ = json.NewEncoder(w).Encode(map[string]any{"id": otaBunUUID, "bundleVersion": "1.4.0", "status": "SIGNED"})
		case r.URL.Path == pathOtaBundles:
			_, _ = w.Write([]byte(`{"items":[{"id":"` + otaBunUUID + `","bundleVersion":"1.4.0"}],"next_cursor":""}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client := New(server.URL, "token")
	app := &ApplicationView{ID: otaAppUUID, TenantID: otaTenantID}

	byUUID, err := client.ResolveOtaBundle(context.Background(), app, otaBunUUID)
	if err != nil || byUUID.ID != otaBunUUID {
		t.Fatalf("uuid resolution: %+v, %v", byUUID, err)
	}
	byLabel, err := client.ResolveOtaBundle(context.Background(), app, "1.4.0")
	if err != nil || byLabel.ID != otaBunUUID {
		t.Fatalf("label resolution: %+v, %v", byLabel, err)
	}
	if _, err := client.ResolveOtaBundle(context.Background(), app, "9.9.9"); err == nil {
		t.Error("unknown label resolved")
	}
	// A foreign uuid conceals as the resolution miss, not a raw 404.
	if _, err := client.ResolveOtaBundle(context.Background(), app, "00000000-0000-0000-0000-000000000000"); err == nil {
		t.Error("foreign uuid resolved")
	}
}

func TestParseOtaStagesNoteEdges(t *testing.T) {
	// A threshold-less note entry is a mistake, not a stage.
	if _, err := ParseOtaStages("=note"); err == nil {
		t.Error("note-only entry accepted")
	}
	// An empty note is simply absent from the wire body (omitempty).
	stages, err := ParseOtaStages("10000=")
	if err != nil {
		t.Fatalf("empty note rejected: %v", err)
	}
	if stages[0].Note != "" {
		t.Errorf("note = %q", stages[0].Note)
	}
	// Whitespace around entries and thresholds is tolerated.
	if stages, err = ParseOtaStages(" 250 , 5000 , 10000 "); err != nil || len(stages) != 3 {
		t.Errorf("whitespace tolerance: %+v, %v", stages, err)
	}
	// Negative and overflowing thresholds refuse.
	for _, bad := range []string{"-1", "10000,10001", "1=note,0"} {
		if _, err := ParseOtaStages(bad); err == nil {
			t.Errorf("ParseOtaStages(%q) accepted", bad)
		}
	}
}

func TestGetOtaFunnelOmitsEmptyRing(t *testing.T) {
	var gotRingParam bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, gotRingParam = r.URL.Query()["ring"]
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"bundleId":"` + otaBunUUID + `"}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	if _, err := client.GetOtaFunnel(context.Background(), otaTenantID, otaBunUUID, ""); err != nil {
		t.Fatalf("GetOtaFunnel: %v", err)
	}
	if gotRingParam {
		t.Error("absent ring sent as a query parameter")
	}
}

func TestHaltOtaRolloutClassifiesProblem(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"type":"...","title":"Conflict","status":409,"code":"OTA_ROLLOUT_STATE_CONFLICT","detail":"not haltable","trace_id":"t-9","errors":{"status":"COMPLETE plans never halt"}}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	_, err := client.HaltOtaRollout(context.Background(), otaAppUUID, otaRelUUID, "why")
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("error type %T", err)
	}
	if apiErr.Network || apiErr.Problem.Status != 409 || apiErr.Problem.Code != "OTA_ROLLOUT_STATE_CONFLICT" {
		t.Errorf("classification = %+v", apiErr.Problem)
	}
	if apiErr.TraceID() != "t-9" || apiErr.Problem.Errors["status"] != "COMPLETE plans never halt" {
		t.Errorf("problem detail = %+v", apiErr.Problem)
	}
}
