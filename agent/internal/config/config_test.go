package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSaveLoadRoundtrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "agent.json")
	original := &Config{
		ServerURL:   "https://svenager.example.org/",
		DeviceID:    "6f0a4d1c-0000-0000-0000-000000000000",
		DeviceToken: "svdt_secret",
	}
	if err := original.Save(path); err != nil {
		t.Fatal(err)
	}

	info, err := os.Stat(filepath.Join(filepath.Dir(path), tokenFileName))
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("token file permissions = %o, want 600", perm)
	}

	loaded, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.ServerURL != "https://svenager.example.org" {
		t.Errorf("trailing slash not trimmed: %q", loaded.ServerURL)
	}
	if loaded.DeviceID != original.DeviceID || loaded.DeviceToken != original.DeviceToken {
		t.Errorf("roundtrip mismatch: %+v", loaded)
	}
	if loaded.StateDir == "" {
		t.Error("StateDir default not applied")
	}
}

func TestLoadRejectsMissingServerURL(t *testing.T) {
	path := filepath.Join(t.TempDir(), "agent.json")
	if err := os.WriteFile(path, []byte(`{"device_id":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("expected error for missing server_url")
	}
}
