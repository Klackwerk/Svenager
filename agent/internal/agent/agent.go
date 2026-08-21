// Package agent contains the long-running poll loop and the enrollment flow.
package agent

import (
	"context"
	"log/slog"
	"math/rand/v2"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

const (
	defaultPollInterval = 60 * time.Second
	maxBackoff          = 15 * time.Minute
)

// Enroll registers this device with the server and persists the resulting
// identity (device ID + API token) to the configuration directory.
func Enroll(serverURL, enrollmentToken, configPath string) error {
	hostname, _ := os.Hostname()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	resp, err := api.Enroll(ctx, serverURL, api.EnrollRequest{
		EnrollmentToken: enrollmentToken,
		Hostname:        hostname,
		Facts:           collectFacts(),
	})
	if err != nil {
		return err
	}
	cfg := &config.Config{
		ServerURL:   serverURL,
		DeviceID:    resp.DeviceID,
		DeviceToken: resp.DeviceToken,
	}
	return cfg.Save(configPath)
}

// Run is the main loop: check in with jitter, execute at most one job at a
// time, back off exponentially on repeated failures. It returns when the
// process receives SIGINT/SIGTERM.
func Run(cfg *config.Config, client *api.Client) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	interval := defaultPollInterval
	if cfg.PollIntervalSeconds > 0 {
		interval = time.Duration(cfg.PollIntervalSeconds) * time.Second
	}
	failures := 0

	slog.Info("agent started", "device", cfg.DeviceID, "server", cfg.ServerURL)
	sdNotify("READY=1")
	startWatchdog(ctx)
	defer sdNotify("STOPPING=1")
	for {
		resp, err := client.Checkin(ctx, api.CheckinRequest{Facts: collectFacts()})
		switch {
		case err != nil && ctx.Err() != nil:
			slog.Info("agent stopping")
			return nil
		case err != nil:
			failures++
			slog.Warn("check-in failed", "error", err, "consecutiveFailures", failures)
		default:
			failures = 0
			if resp.PollIntervalSeconds > 0 {
				interval = time.Duration(resp.PollIntervalSeconds) * time.Second
			}
			if resp.Job != nil {
				runJob(ctx, cfg, client, resp.Job)
				// Check in again right away: report freshness and pick up
				// any follow-up job without waiting a full interval.
				continue
			}
		}

		select {
		case <-ctx.Done():
			slog.Info("agent stopping")
			return nil
		case <-time.After(nextDelay(interval, failures)):
		}
	}
}

// nextDelay applies ±10% jitter so a fleet never checks in in lockstep, and
// exponential backoff (capped) after consecutive failures.
func nextDelay(base time.Duration, failures int) time.Duration {
	d := base
	for i := 0; i < failures && d < maxBackoff; i++ {
		d *= 2
	}
	if d > maxBackoff {
		d = maxBackoff
	}
	jitter := 0.9 + rand.Float64()*0.2
	return time.Duration(float64(d) * jitter)
}

func collectFacts() map[string]string {
	facts := map[string]string{
		"os":   runtime.GOOS,
		"arch": runtime.GOARCH,
	}
	if hostname, err := os.Hostname(); err == nil {
		facts["hostname"] = hostname
	}
	if data, err := os.ReadFile("/etc/os-release"); err == nil && len(data) < 8192 {
		facts["os_release"] = string(data)
	}
	return facts
}
