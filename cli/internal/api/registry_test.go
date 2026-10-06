package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const (
	testAppUUID = "11111111-1111-1111-1111-111111111111"
	testVerUUID = "22222222-2222-2222-2222-222222222222"
)

// fakeAPI answers the registry surface with a fixed world: org o-1 has
// applications Alpha and Beta; Alpha has versions 7.12.0 and 7.13.0.
func fakeAPI(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc(pathAuthMe, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(MeResponse{ID: "u-1", OrgID: "o-1"})
	})
	mux.HandleFunc(pathApplications, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("organizationId") != "o-1" {
			t.Errorf("organizationId = %q", r.URL.Query().Get("organizationId"))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(ApplicationPage{Items: []ApplicationView{
			{ID: testAppUUID, Name: "Alpha"},
			{ID: "33333333-3333-3333-3333-333333333333", Name: "Beta"},
		}})
	})
	mux.HandleFunc(expand(pathApplication, testAppUUID), func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(ApplicationView{ID: testAppUUID, Name: "Alpha"})
	})
	mux.HandleFunc(expand(pathApplicationVersions, testAppUUID), func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(VersionPage{Items: []VersionView{
			{ID: testVerUUID, VersionName: "7.12.0"},
			{ID: "44444444-4444-4444-4444-444444444444", VersionName: "7.13.0"},
		}})
	})
	mux.HandleFunc(expand(pathApplicationVersionOne, testAppUUID, testVerUUID), func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(VersionView{ID: testVerUUID, VersionName: "7.12.0"})
	})
	return httptest.NewServer(mux)
}

func TestResolveApplicationByNameAndUUID(t *testing.T) {
	server := fakeAPI(t)
	defer server.Close()
	client := New(server.URL, "token")
	ctx := context.Background()

	byName, err := client.ResolveApplication(ctx, "alpha")
	if err != nil || byName.ID != testAppUUID {
		t.Fatalf("by name = %+v, %v", byName, err)
	}
	byUUID, err := client.ResolveApplication(ctx, testAppUUID)
	if err != nil || byUUID.Name != "Alpha" {
		t.Fatalf("by uuid = %+v, %v", byUUID, err)
	}
	if _, err := client.ResolveApplication(ctx, "Gamma"); err == nil {
		t.Error("unknown name should fail")
	} else if resErr, ok := err.(*ResolutionError); !ok || len(resErr.Candidates) != 0 {
		t.Errorf("err = %v", err)
	}
}

func TestResolveVersionWithinApplication(t *testing.T) {
	server := fakeAPI(t)
	defer server.Close()
	client := New(server.URL, "token")
	ctx := context.Background()

	byName, err := client.ResolveVersion(ctx, testAppUUID, "7.12.0")
	if err != nil || byName.ID != testVerUUID {
		t.Fatalf("by name = %+v, %v", byName, err)
	}
	byUUID, err := client.ResolveVersion(ctx, testAppUUID, testVerUUID)
	if err != nil || byUUID.VersionName != "7.12.0" {
		t.Fatalf("by uuid = %+v, %v", byUUID, err)
	}
	if _, err := client.ResolveVersion(ctx, testAppUUID, "9.9.9"); err == nil {
		t.Error("unknown version should fail")
	}
}

func TestResolveVersionAnywhere(t *testing.T) {
	server := fakeAPI(t)
	defer server.Close()
	client := New(server.URL, "token")
	ctx := context.Background()

	app, version, err := client.ResolveVersionAnywhere(ctx, "7.12.0")
	if err != nil || app.ID != testAppUUID || version.ID != testVerUUID {
		t.Fatalf("resolve = %+v, %+v, %v", app, version, err)
	}

	// A version name present in two applications must be ambiguous.
	ambiguous := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case pathAuthMe:
			_ = json.NewEncoder(w).Encode(MeResponse{ID: "u-1", OrgID: "o-1"})
		case pathApplications:
			_ = json.NewEncoder(w).Encode(ApplicationPage{Items: []ApplicationView{
				{ID: testAppUUID, Name: "Alpha"}, {ID: "33333333-3333-3333-3333-333333333333", Name: "Beta"},
			}})
		default:
			_ = json.NewEncoder(w).Encode(VersionPage{Items: []VersionView{{ID: testVerUUID, VersionName: "7.12.0"}}})
		}
	}))
	defer ambiguous.Close()
	ac := New(ambiguous.URL, "token")
	if _, _, err := ac.ResolveVersionAnywhere(ctx, "7.12.0"); err == nil {
		t.Fatal("expected ambiguity error")
	} else if resErr, ok := err.(*ResolutionError); !ok || len(resErr.Candidates) != 2 {
		t.Errorf("err = %v", err)
	} else if !strings.Contains(resErr.Error(), "ambiguous") {
		t.Errorf("message = %q", resErr.Error())
	}
}

func TestLooksLikeUUID(t *testing.T) {
	if !LooksLikeUUID(testAppUUID) {
		t.Error("valid uuid rejected")
	}
	for _, bad := range []string{"7.12.0", "alpha", "11111111-1111-1111-1111"} {
		if LooksLikeUUID(bad) {
			t.Errorf("%q accepted as uuid", bad)
		}
	}
}
