package de.klackwerk.svenager

import grails.converters.JSON
import grails.core.GrailsApplication
import jakarta.servlet.http.HttpServletRequest

/**
 * Self-hosted agent distribution: a POSIX install script plus the agent
 * binaries, so a bare Debian is enrolled with a single copyable command —
 * the script also installs git and ansible-core, which the agent shells
 * out to. Both
 * endpoints are public — the enrollment token is the only secret, and it
 * lives solely in the operator's command line.
 */
class AgentInstallController {

    static allowedMethods = [script: 'GET', binary: 'GET']

    static final List<String> ARCHITECTURES = ['amd64', 'arm64', 'armv7', 'armv6'].asImmutable()

    GrailsApplication grailsApplication

    def script() {
        render(text: installScript(instanceUrl(grailsApplication, request)),
                contentType: 'text/x-shellscript', encoding: 'UTF-8')
    }

    def binary(String file) {
        // Binaries plus their detached signatures (agent self-update).
        if (!(file ==~ /linux-(amd64|arm64|armv7|armv6)(\.sig)?/)) {
            response.status = 404
            render([error: "unknown platform; available: ${ARCHITECTURES.collect { 'linux-' + it }}".toString()] as JSON)
            return
        }
        File binary = new File(distDir, "svenager-agent-${file}")
        if (!binary.file) {
            response.status = 404
            render([error: "this instance does not host ${file} agent builds (svenager.agent.distDir)".toString()] as JSON)
            return
        }
        response.contentType = 'application/octet-stream'
        response.setContentLengthLong(binary.length())
        binary.withInputStream { response.outputStream << it }
        response.outputStream.flush()
    }

    /**
     * The instance URL as devices reach it: explicit configuration wins,
     * then a reverse proxy's X-Forwarded-* headers, then the plain request.
     */
    static String instanceUrl(GrailsApplication grailsApplication, HttpServletRequest request) {
        String configured = grailsApplication.config.getProperty('svenager.externalUrl', String, '')
        if (configured) {
            return configured
        }
        String proto = request.getHeader('X-Forwarded-Proto')?.tokenize(',')?.first()?.trim() ?: request.scheme
        String host = request.getHeader('X-Forwarded-Host')?.tokenize(',')?.first()?.trim()
        if (!host) {
            boolean defaultPort = (proto == 'http' && request.serverPort == 80) ||
                    (proto == 'https' && request.serverPort == 443)
            host = request.serverName + (defaultPort ? '' : ":${request.serverPort}")
        }
        "${proto}://${host}"
    }

    private File getDistDir() {
        new File(grailsApplication.config.getProperty('svenager.agent.distDir', String, 'agent-dist'))
    }

    /** One-liner used by the UI: everything from download to first check-in. */
    static String installCommand(String serverUrl, String token) {
        "curl -fsSL ${serverUrl}/install.sh | sudo sh -s -- --token ${token}"
    }

    static String installScript(String serverUrl) {
        '''#!/bin/sh
# Svenager agent installer, served by the Svenager instance itself.
# Usage: curl -fsSL <server>/install.sh | sudo sh -s -- --token <TOKEN>
set -eu

SERVER="__SERVER__"
TOKEN=""
SERVICE=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --token) TOKEN="$2"; shift 2 ;;
    --server) SERVER="$2"; shift 2 ;;
    --no-service) SERVICE=0; shift ;;   # containers/tests without systemd
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$TOKEN" ] || { echo "usage: install.sh --token <enrollment token>" >&2; exit 2; }
[ "$(id -u)" = "0" ] || { echo "please run as root (sudo)" >&2; exit 1; }

case "$(uname -m)" in
  x86_64)  ARCH=amd64 ;;
  aarch64) ARCH=arm64 ;;
  armv7l)  ARCH=armv7 ;;
  armv6l)  ARCH=armv6 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

# The agent shells out to git and ansible-playbook; a fresh Debian has neither.
if ! command -v ansible-playbook >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1; then
  command -v apt-get >/dev/null 2>&1 || {
    echo "git and ansible-playbook are required; install them and re-run" >&2; exit 1; }
  echo "Installing prerequisites (git, ansible-core) ..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  # Older Debian/Raspberry Pi OS releases only ship the ansible metapackage.
  apt-get install -y -qq --no-install-recommends git ansible-core \
    || apt-get install -y -qq --no-install-recommends git ansible
fi

echo "Downloading svenager-agent (linux-$ARCH) from $SERVER ..."
curl -fsSL "$SERVER/install/agent/linux-$ARCH" -o /usr/local/bin/svenager-agent.tmp
install -m 755 /usr/local/bin/svenager-agent.tmp /usr/local/bin/svenager-agent
rm -f /usr/local/bin/svenager-agent.tmp

mkdir -p /etc/svenager /var/lib/svenager

/usr/local/bin/svenager-agent enroll --server "$SERVER" --token "$TOKEN"

if [ "$SERVICE" = "1" ]; then
  cat > /etc/systemd/system/svenager-agent.service <<'UNIT'
[Unit]
Description=Svenager device agent
Documentation=https://github.com/klackwerk/svenager
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/svenager-agent run
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable --now svenager-agent
  echo "Enrolled and started. The device appears in Svenager within a minute."
else
  echo "Enrolled. Start it yourself with: svenager-agent run"
fi
'''.replace('__SERVER__', serverUrl)
    }
}
