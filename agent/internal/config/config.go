// Package config loads and persists the agent configuration.
//
// The configuration file is intentionally minimal: server URL and device
// identity. The device API token is kept in a separate root-only file so the
// config itself can be world-readable for diagnostics.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	// DefaultPath is where the .deb package places the configuration.
	DefaultPath = "/etc/svenager/agent.json"
	// tokenFileName holds the device API token next to the config, mode 0600.
	tokenFileName = "device-token"
)

type Config struct {
	// ServerURL is the base URL of the Svenager server, without trailing slash.
	ServerURL string `json:"server_url"`
	// DeviceID is the UUID assigned by the server at enrollment.
	DeviceID string `json:"device_id"`
	// PollIntervalSeconds is the base check-in interval; the server may
	// override it in check-in responses. 0 means the built-in default.
	PollIntervalSeconds int `json:"poll_interval_seconds,omitempty"`
	// StateDir is the writable working directory (repo cache, job workspaces).
	StateDir string `json:"state_dir,omitempty"`
	// UpdatePublicKey is the base64-encoded raw Ed25519 public key that
	// signed the agent binaries; self-update refuses to run without it.
	UpdatePublicKey string `json:"update_public_key,omitempty"`

	// DeviceToken is loaded from the sibling token file, never from the
	// config file itself.
	DeviceToken string `json:"-"`

	path string
}

func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	cfg := &Config{path: path}
	if err := json.Unmarshal(raw, cfg); err != nil {
		return nil, fmt.Errorf("parse %s: %w", path, err)
	}
	if cfg.ServerURL == "" {
		return nil, fmt.Errorf("%s: server_url is required (run 'svenager-agent enroll' first)", path)
	}
	cfg.ServerURL = strings.TrimRight(cfg.ServerURL, "/")
	if cfg.StateDir == "" {
		cfg.StateDir = "/var/lib/svenager"
	}
	token, err := os.ReadFile(cfg.TokenPath())
	if err != nil {
		return nil, fmt.Errorf("device token: %w (run 'svenager-agent enroll' first)", err)
	}
	cfg.DeviceToken = strings.TrimSpace(string(token))
	return cfg, nil
}

// Save writes the config file and the device token file (mode 0600).
func (c *Config) Save(path string) error {
	c.path = path
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, append(data, '\n'), 0o644); err != nil {
		return err
	}
	if c.DeviceToken != "" {
		if err := os.WriteFile(c.TokenPath(), []byte(c.DeviceToken+"\n"), 0o600); err != nil {
			return err
		}
	}
	return nil
}

func (c *Config) TokenPath() string {
	dir := filepath.Dir(c.path)
	if dir == "" || dir == "." {
		dir = filepath.Dir(DefaultPath)
	}
	return filepath.Join(dir, tokenFileName)
}
