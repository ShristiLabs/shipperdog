package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClientSendsAuthAndCorrelationID(t *testing.T) {
	var gotAuth, gotCorrelation string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotCorrelation = r.Header.Get("X-Correlation-Id")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(MeResponse{ID: "u1", Email: "dev@example.com"})
	}))
	defer server.Close()

	client := New(server.URL, "sdp_test_token")
	if _, err := client.Me(context.Background()); err != nil {
		t.Fatalf("Me: %v", err)
	}
	if gotAuth != "Bearer sdp_test_token" {
		t.Errorf("Authorization = %q", gotAuth)
	}
	if gotCorrelation == "" {
		t.Error("X-Correlation-Id not sent")
	}
}

func TestProblemParsingAndClassification(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"type":"https://api.shipperdog.dev/problems/unauthorized","title":"Unauthorized","status":401,"code":"UNAUTHENTICATED","detail":"Authentication is required.","trace_id":"abc-123"}`))
	}))
	defer server.Close()

	client := New(server.URL, "sdp_bad")
	_, err := client.Me(context.Background())
	if err == nil {
		t.Fatal("expected error")
	}
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("error type %T", err)
	}
	if apiErr.Problem.Code != "UNAUTHENTICATED" {
		t.Errorf("code = %q", apiErr.Problem.Code)
	}
	if apiErr.Network {
		t.Error("classified as network")
	}
	if apiErr.TraceID() != "abc-123" {
		t.Errorf("trace id = %q", apiErr.TraceID())
	}
}

func TestNetworkErrorClassification(t *testing.T) {
	// Port 1 on localhost refuses connections.
	client := New("http://127.0.0.1:1", "token")
	_, err := client.Me(context.Background())
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("error type %T, want *APIError", err)
	}
	if !apiErr.Network {
		t.Error("expected network classification")
	}
}

func TestPaginationWalk(t *testing.T) {
	var calls int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != pathApplications {
			t.Errorf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("organizationId") != "org-1" {
			t.Errorf("organizationId = %q", r.URL.Query().Get("organizationId"))
		}
		cursor := r.URL.Query().Get("cursor")
		w.Header().Set("Content-Type", "application/json")
		if cursor == "" {
			_, _ = w.Write([]byte(`{"items":[{"id":"a","name":"Alpha"}],"next_cursor":"c2"}`))
			return
		}
		_, _ = w.Write([]byte(`{"items":[{"id":"b","name":"Beta"}],"next_cursor":""}`))
	}))
	defer server.Close()

	client := New(server.URL, "token")
	apps, err := client.ListApplications(context.Background(), "org-1")
	if err != nil {
		t.Fatalf("ListApplications: %v", err)
	}
	if len(apps) != 2 || apps[0].ID != "a" || apps[1].ID != "b" {
		t.Errorf("apps = %+v", apps)
	}
	if calls != 2 {
		t.Errorf("calls = %d, want 2", calls)
	}
}
