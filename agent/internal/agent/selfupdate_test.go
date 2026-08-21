package agent

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

type updateFixture struct {
	cfg      *config.Config
	client   *api.Client
	captured *capturedEvents
	target   string
	execed   []string
}

func setupUpdate(t *testing.T, binary, signature []byte, publicKey string) *updateFixture {
	t.Helper()
	fixture := &updateFixture{captured: &capturedEvents{}}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/events"):
			var e api.JobEvent
			json.NewDecoder(r.Body).Decode(&e)
			fixture.captured.add(e)
			w.Write([]byte(`{"ok":true}`))
		case strings.HasSuffix(r.URL.Path, ".sig"):
			w.Write([]byte(base64.StdEncoding.EncodeToString(signature)))
		case strings.Contains(r.URL.Path, "/install/agent/"):
			w.Write(binary)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)

	fixture.target = filepath.Join(t.TempDir(), "svenager-agent")
	if err := os.WriteFile(fixture.target, []byte("old binary"), 0o755); err != nil {
		t.Fatal(err)
	}

	oldTarget, oldExec := updateTargetPath, execRestart
	updateTargetPath = func() (string, error) { return fixture.target, nil }
	execRestart = func(binary string, args []string, env []string) error {
		fixture.execed = append(fixture.execed, binary)
		return nil
	}
	t.Cleanup(func() { updateTargetPath, execRestart = oldTarget, oldExec })

	fixture.cfg = &config.Config{
		ServerURL:       server.URL,
		DeviceToken:     "svdt_test",
		StateDir:        t.TempDir(),
		UpdatePublicKey: publicKey,
	}
	fixture.client = api.NewClient(fixture.cfg, "test")
	return fixture
}

func updateJob(t *testing.T) *api.Job {
	t.Helper()
	payload, _ := json.Marshal(map[string]string{"version": "1.2.3"})
	return &api.Job{ID: "7", Type: "AGENT_UPDATE", Payload: payload}
}

func TestSelfUpdateVerifiesAndReplacesAndRestarts(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	binary := []byte("brand new agent binary")
	fixture := setupUpdate(t, binary, ed25519.Sign(priv, binary),
		base64.StdEncoding.EncodeToString(pub))

	runJob(context.Background(), fixture.cfg, fixture.client, updateJob(t))

	if exit, ok := fixture.captured.finalExit(); !ok || exit != 0 {
		t.Fatalf("expected exit 0, got %v %v (log: %s)", exit, ok, fixture.captured.log())
	}
	replaced, _ := os.ReadFile(fixture.target)
	if string(replaced) != string(binary) {
		t.Errorf("binary was not replaced")
	}
	if len(fixture.execed) != 1 || fixture.execed[0] != fixture.target {
		t.Errorf("expected exec of %s, got %v", fixture.target, fixture.execed)
	}
}

func TestSelfUpdateRejectsBadSignature(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	binary := []byte("brand new agent binary")
	fixture := setupUpdate(t, binary, ed25519.Sign(priv, []byte("something else")),
		base64.StdEncoding.EncodeToString(pub))

	runJob(context.Background(), fixture.cfg, fixture.client, updateJob(t))

	if exit, _ := fixture.captured.finalExit(); exit != 1 {
		t.Fatalf("expected exit 1, got %v", exit)
	}
	if !strings.Contains(fixture.captured.log(), "SIGNATURE VERIFICATION FAILED") {
		t.Errorf("missing verification failure notice:\n%s", fixture.captured.log())
	}
	untouched, _ := os.ReadFile(fixture.target)
	if string(untouched) != "old binary" {
		t.Errorf("binary must stay untouched on bad signature")
	}
	if len(fixture.execed) != 0 {
		t.Errorf("must not restart on bad signature")
	}
}

func TestSelfUpdateRefusesWithoutConfiguredKey(t *testing.T) {
	_, priv, _ := ed25519.GenerateKey(nil)
	binary := []byte("new")
	fixture := setupUpdate(t, binary, ed25519.Sign(priv, binary), "")

	runJob(context.Background(), fixture.cfg, fixture.client, updateJob(t))

	if exit, _ := fixture.captured.finalExit(); exit != 1 {
		t.Fatalf("expected exit 1, got %v", exit)
	}
	if !strings.Contains(fixture.captured.log(), "no update_public_key") {
		t.Errorf("missing refusal notice:\n%s", fixture.captured.log())
	}
}
