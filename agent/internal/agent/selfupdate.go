package agent

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"runtime"
	"strings"
	"syscall"

	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

// Variables so tests can substitute the process replacement and target.
var (
	execRestart = func(binary string, args []string, env []string) error {
		return syscall.Exec(binary, args, env)
	}
	updateTargetPath = os.Executable
)

type updatePayload struct {
	Version string `json:"version"`
}

// runSelfUpdate downloads the current agent binary for this architecture
// from the server's distribution directory, verifies its Ed25519 signature
// against the configured public key and replaces the running executable.
// The caller restarts via exec after the finished event went out.
func runSelfUpdate(ctx context.Context, cfg *config.Config, client *api.Client, job *api.Job, reporter *eventReporter) int {
	var payload updatePayload
	_ = json.Unmarshal(job.Payload, &payload)

	if cfg.UpdatePublicKey == "" {
		reporter.log("refusing self-update: no update_public_key configured in agent.json\n")
		return 1
	}
	publicKey, err := base64.StdEncoding.DecodeString(cfg.UpdatePublicKey)
	if err != nil || len(publicKey) != ed25519.PublicKeySize {
		reporter.log("refusing self-update: update_public_key is not a base64 Ed25519 public key\n")
		return 1
	}

	// The endpoint serves distDir's svenager-agent-linux-<arch> under the
	// short platform name (see AgentInstallController).
	file := fmt.Sprintf("linux-%s", runtime.GOARCH)
	reporter.log(fmt.Sprintf("downloading svenager-agent-%s (requested version: %s)\n",
		file, orUnknown(payload.Version)))
	binary, err := client.FetchInstallFile(ctx, file)
	if err != nil {
		reporter.log("download failed: " + err.Error() + "\n")
		return 1
	}
	signatureB64, err := client.FetchInstallFile(ctx, file+".sig")
	if err != nil {
		reporter.log("signature download failed: " + err.Error() + "\n")
		return 1
	}
	signature, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(signatureB64)))
	if err != nil || len(signature) != ed25519.SignatureSize {
		reporter.log("invalid signature file\n")
		return 1
	}
	if !ed25519.Verify(publicKey, binary, signature) {
		reporter.log("SIGNATURE VERIFICATION FAILED — update rejected\n")
		return 1
	}

	target, err := updateTargetPath()
	if err != nil {
		reporter.log("cannot resolve own executable: " + err.Error() + "\n")
		return 1
	}
	// Write next to the target so the rename stays on one filesystem.
	staging := target + ".update"
	if err := os.WriteFile(staging, binary, 0o755); err != nil {
		reporter.log("cannot write new binary: " + err.Error() + "\n")
		return 1
	}
	if err := os.Rename(staging, target); err != nil {
		os.Remove(staging)
		reporter.log("cannot replace binary: " + err.Error() + "\n")
		return 1
	}
	reporter.log(fmt.Sprintf("signature verified, %s replaced — restarting agent\n", target))
	return 0
}

func orUnknown(s string) string {
	if s == "" {
		return "latest available"
	}
	return s
}
