#!/bin/sh
set -e
systemctl disable --now svenager-agent >/dev/null 2>&1 || true
