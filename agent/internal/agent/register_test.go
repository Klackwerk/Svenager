package agent

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/klackwerk/svenager/agent/internal/config"
)

func registrationServer(t *testing.T, approveAfter int32) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var polls atomic.Int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/enroll/request" {
			http.NotFound(w, r)
			return
		}
		var req map[string]any
		json.NewDecoder(r.Body).Decode(&req)
		if req["requestId"] == "" {
			t.Error("requestId missing in registration poll")
		}
		if polls.Add(1) <= approveAfter {
			w.WriteHeader(http.StatusAccepted)
			w.Write([]byte(`{"status":"pending"}`))
			return
		}
		w.WriteHeader(http.StatusCreated)
		w.Write([]byte(`{"status":"approved","deviceId":"dev-9","deviceToken":"svdt_x"}`))
	}))
	t.Cleanup(ts.Close)
	return ts, &polls
}

func TestRegisterAndWaitPollsUntilApproved(t *testing.T) {
	ts, polls := registrationServer(t, 2)
	configPath := filepath.Join(t.TempDir(), "agent.json")

	err := RegisterAndWait(context.Background(), ts.URL, configPath, 5*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if polls.Load() != 3 {
		t.Errorf("polls = %d, want 3", polls.Load())
	}
	cfg, err := config.Load(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DeviceID != "dev-9" || cfg.DeviceToken != "svdt_x" || cfg.ServerURL != ts.URL {
		t.Errorf("persisted config wrong: %+v", cfg)
	}
}

func TestRegisterAndWaitStopsWhenDenied(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte(`{"status":"denied"}`))
	}))
	t.Cleanup(ts.Close)

	err := RegisterAndWait(context.Background(), ts.URL, filepath.Join(t.TempDir(), "agent.json"), time.Millisecond)
	if err == nil {
		t.Fatal("expected an error for a denied request")
	}
}
