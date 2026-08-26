package agent

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

// ansiblePlaybookCommand is a variable so tests can substitute a stub.
var ansiblePlaybookCommand = "ansible-playbook"

type play struct {
	RepoID   int64    `json:"repoId"`
	RepoName string   `json:"repoName"`
	Commit   string   `json:"commit"`
	Roles    []string `json:"roles"`
}

type applyPayload struct {
	TimeoutSeconds int            `json:"timeoutSeconds"`
	Plays          []play         `json:"plays"`
	ExtraVars      map[string]any `json:"extraVars"`
}

// runJob executes one job and reports status and logs to the server.
// It never returns an error — all failures are reported as job events.
func runJob(ctx context.Context, cfg *config.Config, client *api.Client, job *api.Job) {
	logger := slog.With("job", job.ID, "type", job.Type)
	logger.Info("job started")
	reporter := &eventReporter{ctx: ctx, client: client, jobID: job.ID}
	reporter.send(api.JobEvent{Event: "started"})

	if job.Type == "OPEN_TUNNEL" {
		// Tunnels are I/O-bound and long-lived: serve them in the background
		// so heartbeats and other jobs continue while an operator watches.
		go func() {
			exitCode := runTunnel(ctx, cfg, client, job, reporter)
			reporter.send(api.JobEvent{Event: "finished", ExitCode: &exitCode})
			logger.Info("job finished", "exitCode", exitCode)
		}()
		return
	}

	if job.Type == "AGENT_UPDATE" {
		exitCode := runSelfUpdate(ctx, cfg, client, job, reporter)
		reporter.send(api.JobEvent{Event: "finished", ExitCode: &exitCode})
		logger.Info("job finished", "exitCode", exitCode)
		if exitCode == 0 {
			// Replace the process in place; systemd keeps the MAINPID and
			// the fresh binary re-announces READY itself.
			if target, err := updateTargetPath(); err == nil {
				if err := execRestart(target, os.Args, os.Environ()); err != nil {
					slog.Error("exec of updated binary failed", "error", err)
				}
			}
		}
		return
	}

	exitCode := 1
	switch job.Type {
	case "APPLY_CONFIG":
		exitCode = runApply(ctx, cfg, client, job, reporter, false)
	case "CHECK_CONFIG":
		reporter.log("check mode: previewing changes, nothing is modified\n")
		exitCode = runApply(ctx, cfg, client, job, reporter, true)
	case "PING":
		reporter.log("pong\n")
		exitCode = 0
	default:
		reporter.log(fmt.Sprintf("unknown job type %q\n", job.Type))
	}

	reporter.send(api.JobEvent{Event: "finished", ExitCode: &exitCode})
	logger.Info("job finished", "exitCode", exitCode)
}

func runApply(ctx context.Context, cfg *config.Config, client *api.Client, job *api.Job, reporter *eventReporter, checkMode bool) int {
	var payload applyPayload
	if err := json.Unmarshal(job.Payload, &payload); err != nil {
		reporter.log("invalid job payload: " + err.Error() + "\n")
		return 1
	}
	if payload.TimeoutSeconds > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(payload.TimeoutSeconds)*time.Second)
		defer cancel()
	}

	payload.ExtraVars = withBuiltinVars(cfg, payload.ExtraVars)

	workspace := filepath.Join(cfg.StateDir, "jobs", job.ID)
	if err := os.RemoveAll(workspace); err != nil {
		reporter.log("cannot clean workspace: " + err.Error() + "\n")
		return 1
	}
	defer os.RemoveAll(workspace)

	for _, p := range payload.Plays {
		reporter.log(fmt.Sprintf("=== %s @ %.10s: roles %s\n", p.RepoName, p.Commit, strings.Join(p.Roles, ", ")))
		playDir := filepath.Join(workspace, fmt.Sprintf("repo-%d", p.RepoID))
		if err := fetchAndPrepare(ctx, client, job.ID, p, playDir, payload.ExtraVars); err != nil {
			reporter.log("preparation failed: " + err.Error() + "\n")
			return 1
		}
		if code := runPlaybook(ctx, playDir, reporter, checkMode); code != 0 {
			return code
		}
	}
	return 0
}

// withBuiltinVars adds facts only this device knows for sure — the server
// URL it actually reaches and its identity — so roles can reference them
// (e.g. the kiosk demo URL). Server-provided values win.
func withBuiltinVars(cfg *config.Config, extraVars map[string]any) map[string]any {
	if extraVars == nil {
		extraVars = map[string]any{}
	}
	if _, ok := extraVars["svenager_server_url"]; !ok {
		extraVars["svenager_server_url"] = cfg.ServerURL
	}
	if _, ok := extraVars["svenager_device_id"]; !ok {
		extraVars["svenager_device_id"] = cfg.DeviceID
	}
	return extraVars
}

func fetchAndPrepare(ctx context.Context, client *api.Client, jobID string, p play, playDir string, extraVars map[string]any) error {
	bundle, err := client.DownloadBundle(ctx, jobID, p.RepoID)
	if err != nil {
		return err
	}
	defer bundle.Close()
	if err := extractTarGz(bundle, playDir); err != nil {
		return fmt.Errorf("extract bundle: %w", err)
	}

	playbook, err := renderPlaybook(p.Roles)
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(playDir, "svenager-play.yml"), playbook, 0o644); err != nil {
		return err
	}
	vars, err := json.Marshal(extraVars)
	if err != nil {
		return err
	}
	// 0600: extra-vars may contain secrets.
	return os.WriteFile(filepath.Join(playDir, "svenager-vars.json"), vars, 0o600)
}

// renderPlaybook produces the local-execution playbook for the given roles.
// Role names are embedded as JSON strings, which YAML accepts verbatim.
//
// force_handlers keeps notified handlers running even when a later task in
// the play fails — without it a failed apply drops a pending restart (e.g.
// the kiosk browser after a URL change), and the next, now-idempotent run
// never re-notifies it, so the change silently never takes effect.
func renderPlaybook(roles []string) ([]byte, error) {
	var b strings.Builder
	b.WriteString("- hosts: localhost\n  connection: local\n  become: true\n  force_handlers: true\n  roles:\n")
	for _, role := range roles {
		quoted, err := json.Marshal(role)
		if err != nil {
			return nil, err
		}
		fmt.Fprintf(&b, "    - %s\n", quoted)
	}
	return []byte(b.String()), nil
}

func runPlaybook(ctx context.Context, playDir string, reporter *eventReporter, checkMode bool) int {
	args := []string{"-i", "localhost,", "svenager-play.yml", "-e", "@svenager-vars.json"}
	if checkMode {
		args = append(args, "--check")
	}
	if _, err := exec.LookPath(ansiblePlaybookCommand); err != nil {
		reporter.log("ansible-playbook not found: install it on the device, " +
			"e.g. apt-get install -y git ansible-core (" + err.Error() + ")\n")
		return 1
	}
	cmd := exec.CommandContext(ctx, ansiblePlaybookCommand, args...)
	cmd.Dir = playDir
	cmd.Env = append(os.Environ(), "ANSIBLE_FORCE_COLOR=0", "ANSIBLE_NOCOWS=1")

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		reporter.log("cannot start ansible-playbook: " + err.Error() + "\n")
		return 1
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		reporter.log("cannot start ansible-playbook: " + err.Error() + "\n")
		return 1
	}

	buf := make([]byte, 16*1024)
	for {
		n, readErr := stdout.Read(buf)
		if n > 0 {
			reporter.log(string(buf[:n]))
		}
		if readErr != nil {
			break
		}
	}
	if err := cmd.Wait(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return exitErr.ExitCode()
		}
		reporter.log("ansible-playbook: " + err.Error() + "\n")
		return 1
	}
	return 0
}

// extractTarGz unpacks a tar.gz stream below dest, rejecting path traversal.
func extractTarGz(r io.Reader, dest string) error {
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return err
	}
	gz, err := gzip.NewReader(r)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		header, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		clean := filepath.Clean(header.Name)
		if filepath.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, ".."+string(os.PathSeparator)) {
			return fmt.Errorf("archive entry escapes destination: %s", header.Name)
		}
		target := filepath.Join(dest, clean)
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			file, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(header.Mode)&0o777)
			if err != nil {
				return err
			}
			if _, err := io.Copy(file, tr); err != nil {
				file.Close()
				return err
			}
			file.Close()
		case tar.TypeSymlink, tar.TypeLink:
			// Repository bundles should not need links; skip rather than risk escapes.
			slog.Warn("skipping link in bundle", "name", header.Name)
		}
	}
}

// eventReporter ships sequenced log chunks and status events, tolerating
// transient failures (a lost chunk must not fail the job).
type eventReporter struct {
	ctx    context.Context
	client *api.Client
	jobID  string
	seq    int
}

func (r *eventReporter) log(chunk string) {
	r.send(api.JobEvent{Event: "log", Seq: r.seq, Chunk: chunk})
	r.seq++
}

func (r *eventReporter) send(event api.JobEvent) {
	// Use a context that survives job-timeout cancellation so the final
	// "finished" event still goes out.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.ctx), 30*time.Second)
	defer cancel()
	if err := r.client.PostJobEvent(ctx, r.jobID, event); err != nil {
		slog.Warn("failed to report job event", "job", r.jobID, "event", event.Event, "error", err)
	}
}
