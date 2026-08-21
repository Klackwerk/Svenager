#!/bin/sh
# Builds the agent for all supported device platforms into server/agent-dist/,
# where the dev server serves them for the one-step enrollment command
# (GET /install.sh | sudo sh …).
set -eu
cd "$(dirname "$0")/../agent"
mkdir -p ../server/agent-dist
VERSION="${1:-dev}"

build() {
  GOOS=linux GOARCH="$1" GOARM="$2" go build \
    -ldflags "-X main.version=$VERSION" \
    -o "../server/agent-dist/svenager-agent-linux-$3" ./cmd/svenager-agent
  echo "built agent-dist/svenager-agent-linux-$3"
}

build amd64 "" amd64
build arm64 "" arm64
build arm 7 armv7
build arm 6 armv6
