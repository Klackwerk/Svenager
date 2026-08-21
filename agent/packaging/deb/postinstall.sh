#!/bin/sh
set -e
systemctl daemon-reload >/dev/null 2>&1 || true
if [ ! -f /etc/svenager/agent.json ]; then
    echo "svenager-agent installed. Enroll this device first:"
    echo "  svenager-agent enroll --server https://<svenager-host> --token <enrollment token>"
    echo "then start it:"
    echo "  systemctl enable --now svenager-agent"
else
    # Upgrade of an enrolled device: restart into the new binary.
    systemctl try-restart svenager-agent >/dev/null 2>&1 || true
fi
