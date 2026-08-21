package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/klackwerk/svenager/agent/internal/config"
)

func TestEnrollAndCheckin(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/enroll":
			var req EnrollRequest
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				t.Fatal(err)
			}
			if req.EnrollmentToken != "svet_test" || req.Hostname != "kiosk-01" {
				t.Errorf("unexpected enroll request: %+v", req)
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(EnrollResponse{DeviceID: "dev-1", DeviceToken: "svdt_issued"})
		case "/api/v1/agent/checkin":
			if got := r.Header.Get("Authorization"); got != "Bearer svdt_issued" {
				t.Errorf("Authorization = %q", got)
			}
			var req CheckinRequest
			json.NewDecoder(r.Body).Decode(&req)
			if req.AgentVersion != "1.2.3" {
				t.Errorf("AgentVersion = %q, want auto-filled 1.2.3", req.AgentVersion)
			}
			json.NewEncoder(w).Encode(CheckinResponse{PollIntervalSeconds: 30})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	enrolled, err := Enroll(context.Background(), server.URL, EnrollRequest{
		EnrollmentToken: "svet_test",
		Hostname:        "kiosk-01",
	})
	if err != nil {
		t.Fatal(err)
	}
	if enrolled.DeviceToken != "svdt_issued" {
		t.Fatalf("unexpected enroll response: %+v", enrolled)
	}

	client := NewClient(&config.Config{ServerURL: server.URL, DeviceToken: enrolled.DeviceToken}, "1.2.3")
	resp, err := client.Checkin(context.Background(), CheckinRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if resp.PollIntervalSeconds != 30 || resp.Job != nil {
		t.Fatalf("unexpected checkin response: %+v", resp)
	}
}

func TestCheckinReportsServerErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"error":"unknown device"}`, http.StatusUnauthorized)
	}))
	defer server.Close()

	client := NewClient(&config.Config{ServerURL: server.URL, DeviceToken: "svdt_revoked"}, "dev")
	if _, err := client.Checkin(context.Background(), CheckinRequest{}); err == nil {
		t.Fatal("expected error for 401 response")
	}
}
