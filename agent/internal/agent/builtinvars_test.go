package agent

import (
	"testing"

	"github.com/klackwerk/svenager/agent/internal/config"
)

func TestWithBuiltinVarsInjectsIdentity(t *testing.T) {
	cfg := &config.Config{ServerURL: "https://svenager.example.org", DeviceID: "dev-1"}

	vars := withBuiltinVars(cfg, nil)
	if vars["svenager_server_url"] != "https://svenager.example.org" {
		t.Errorf("svenager_server_url = %v", vars["svenager_server_url"])
	}
	if vars["svenager_device_id"] != "dev-1" {
		t.Errorf("svenager_device_id = %v", vars["svenager_device_id"])
	}
}

func TestWithBuiltinVarsKeepsServerProvidedValues(t *testing.T) {
	cfg := &config.Config{ServerURL: "https://svenager.example.org", DeviceID: "dev-1"}

	vars := withBuiltinVars(cfg, map[string]any{"svenager_server_url": "https://override"})
	if vars["svenager_server_url"] != "https://override" {
		t.Errorf("server-provided value was overwritten: %v", vars["svenager_server_url"])
	}
	if vars["svenager_device_id"] != "dev-1" {
		t.Errorf("svenager_device_id = %v", vars["svenager_device_id"])
	}
}
