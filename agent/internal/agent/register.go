package agent

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

// RegisterAndWait polls the token-less enrollment endpoint until an admin
// approves this device in the Svenager UI, then persists its identity.
// Meant for pre-configured images: `svenager-agent run --server URL` calls
// this automatically when no configuration exists yet.
func RegisterAndWait(ctx context.Context, serverURL, configPath string, interval time.Duration) error {
	requestID := machineID()
	hostname, _ := os.Hostname()
	slog.Info("requesting enrollment approval", "server", serverURL, "requestId", requestID)
	for {
		resp, err := api.Register(ctx, serverURL, api.RegisterRequest{
			RequestID: requestID,
			Hostname:  hostname,
			Facts:     collectFacts(),
		})
		switch {
		case err != nil && ctx.Err() != nil:
			return ctx.Err()
		case err != nil:
			slog.Warn("registration attempt failed", "error", err)
		case resp.Status == "approved":
			slog.Info("enrollment approved", "device", resp.DeviceID)
			cfg := &config.Config{ServerURL: serverURL, DeviceID: resp.DeviceID, DeviceToken: resp.DeviceToken}
			return cfg.Save(configPath)
		case resp.Status == "denied":
			return fmt.Errorf("enrollment was denied on the server")
		default:
			slog.Info("waiting for enrollment approval")
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(interval):
		}
	}
}

// machineID is the stable identity a cloned image announces itself with.
func machineID() string {
	if data, err := os.ReadFile("/etc/machine-id"); err == nil {
		if id := strings.TrimSpace(string(data)); id != "" {
			return id
		}
	}
	hostname, _ := os.Hostname()
	return "host-" + hostname
}
