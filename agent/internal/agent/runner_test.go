package agent

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

func tarGz(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, content := range files {
		if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(content))}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	tw.Close()
	gz.Close()
	return buf.Bytes()
}

// stubPlaybook installs a fake ansible-playbook that records its invocation.
func stubPlaybook(t *testing.T, dir string, exitCode int) {
	t.Helper()
	script := "#!/bin/sh\necho RUNNING in $PWD with args: $@\ncat svenager-play.yml\ncat svenager-vars.json\nexit " +
		map[int]string{0: "0", 2: "2"}[exitCode] + "\n"
	path := filepath.Join(dir, "fake-playbook")
	if err := os.WriteFile(path, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	old := ansiblePlaybookCommand
	ansiblePlaybookCommand = path
	t.Cleanup(func() { ansiblePlaybookCommand = old })
}

type capturedEvents struct {
	mu     sync.Mutex
	events []api.JobEvent
}

func (c *capturedEvents) add(e api.JobEvent) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.events = append(c.events, e)
}

func (c *capturedEvents) log() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var b strings.Builder
	for _, e := range c.events {
		if e.Event == "log" {
			b.WriteString(e.Chunk)
		}
	}
	return b.String()
}

func (c *capturedEvents) finalExit() (int, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, e := range c.events {
		if e.Event == "finished" && e.ExitCode != nil {
			return *e.ExitCode, true
		}
	}
	return 0, false
}

func setupJobServer(t *testing.T, bundle []byte) (*api.Client, *config.Config, *capturedEvents) {
	t.Helper()
	captured := &capturedEvents{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/events"):
			var e api.JobEvent
			json.NewDecoder(r.Body).Decode(&e)
			captured.add(e)
			w.Write([]byte(`{"ok":true}`))
		case strings.Contains(r.URL.Path, "/bundles/"):
			w.Write(bundle)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	cfg := &config.Config{ServerURL: server.URL, DeviceToken: "svdt_test", StateDir: t.TempDir()}
	return api.NewClient(cfg, "test"), cfg, captured
}

func applyJob(t *testing.T, extraVars map[string]any) *api.Job {
	t.Helper()
	payload, err := json.Marshal(applyPayload{
		TimeoutSeconds: 60,
		Plays:          []play{{RepoID: 7, RepoName: "ref", Commit: "abc123", Roles: []string{"base", "banner"}}},
		ExtraVars:      extraVars,
	})
	if err != nil {
		t.Fatal(err)
	}
	return &api.Job{ID: "42", Type: "APPLY_CONFIG", Payload: payload}
}

func TestRunJobHappyPath(t *testing.T) {
	bundle := tarGz(t, map[string]string{"roles/banner/tasks/main.yml": "---\n"})
	client, cfg, captured := setupJobServer(t, bundle)
	stubPlaybook(t, t.TempDir(), 0)

	runJob(context.Background(), cfg, client, applyJob(t, map[string]any{"banner_text": "hi"}))

	if exit, ok := captured.finalExit(); !ok || exit != 0 {
		t.Fatalf("expected finished with exit 0, got %v %v", exit, ok)
	}
	log := captured.log()
	for _, want := range []string{"RUNNING in", `- "base"`, `- "banner"`, `"banner_text":"hi"`, "connection: local"} {
		if !strings.Contains(log, want) {
			t.Errorf("log missing %q:\n%s", want, log)
		}
	}
	if captured.events[0].Event != "started" {
		t.Errorf("first event should be started, got %+v", captured.events[0])
	}
}

func TestRunJobCheckModePassesCheckFlag(t *testing.T) {
	bundle := tarGz(t, map[string]string{"roles/banner/tasks/main.yml": "---\n"})
	client, cfg, captured := setupJobServer(t, bundle)
	stubPlaybook(t, t.TempDir(), 0)

	job := applyJob(t, nil)
	job.Type = "CHECK_CONFIG"
	runJob(context.Background(), cfg, client, job)

	if exit, ok := captured.finalExit(); !ok || exit != 0 {
		t.Fatalf("expected finished with exit 0, got %v %v", exit, ok)
	}
	if log := captured.log(); !strings.Contains(log, "--check") {
		t.Errorf("check mode did not pass --check to ansible-playbook:\n%s", log)
	}
}

func TestRunJobRejectsUnknownTypeGracefully(t *testing.T) {
	client, cfg, captured := setupJobServer(t, nil)

	runJob(context.Background(), cfg, client, &api.Job{ID: "43", Type: "FROM_THE_FUTURE"})

	if exit, ok := captured.finalExit(); !ok || exit != 1 {
		t.Fatalf("expected finished with exit 1, got %v %v", exit, ok)
	}
	if log := captured.log(); !strings.Contains(log, "unknown job type") {
		t.Errorf("missing unknown-type notice:\n%s", log)
	}
}

func TestRunJobReportsPlaybookFailure(t *testing.T) {
	bundle := tarGz(t, map[string]string{"roles/banner/tasks/main.yml": "---\n"})
	client, cfg, captured := setupJobServer(t, bundle)
	stubPlaybook(t, t.TempDir(), 2)

	runJob(context.Background(), cfg, client, applyJob(t, nil))

	if exit, ok := captured.finalExit(); !ok || exit != 2 {
		t.Fatalf("expected finished with exit 2, got %v %v", exit, ok)
	}
}

func TestExtractTarGzRejectsTraversal(t *testing.T) {
	evil := tarGz(t, map[string]string{"../escape.txt": "pwned"})
	err := extractTarGz(bytes.NewReader(evil), t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "escapes destination") {
		t.Fatalf("expected traversal rejection, got %v", err)
	}
}

func TestRenderPlaybookQuotesRoleNames(t *testing.T) {
	playbook, err := renderPlaybook([]string{"normal", "weird: name"})
	if err != nil {
		t.Fatal(err)
	}
	text := string(playbook)
	if !strings.Contains(text, `- "weird: name"`) {
		t.Errorf("role name not quoted:\n%s", text)
	}
	if !strings.Contains(text, "force_handlers: true") {
		t.Errorf("force_handlers not set — a failed task would drop pending restarts:\n%s", text)
	}
}
