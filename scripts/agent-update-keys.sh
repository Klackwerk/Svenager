#!/usr/bin/env bash
# Ed25519 signing for agent self-update.
#
#   agent-update-keys.sh gen  [keyfile]        # create a keypair, print the
#                                              # base64 public key for
#                                              # agent.json (update_public_key)
#   agent-update-keys.sh sign [keyfile] [dir]  # sign every agent binary in
#                                              # the dist directory (*.sig,
#                                              # base64 raw signature)
#
# Keep the private key OFFLINE — it never belongs on the Svenager server.
# The server only distributes the binaries and their .sig files; agents
# verify against the public key configured on the device.
set -euo pipefail

COMMAND="${1:-}"
KEYFILE="${2:-agent-update-key.pem}"
DIST="${3:-agent-dist}"

case "$COMMAND" in
gen)
    [ -e "$KEYFILE" ] && { echo "refusing to overwrite $KEYFILE" >&2; exit 1; }
    openssl genpkey -algorithm ed25519 -out "$KEYFILE"
    chmod 600 "$KEYFILE"
    echo "private key: $KEYFILE (keep offline!)"
    echo -n "update_public_key: "
    openssl pkey -in "$KEYFILE" -pubout -outform DER | tail -c 32 | base64
    ;;
sign)
    [ -f "$KEYFILE" ] || { echo "key $KEYFILE not found — run 'gen' first" >&2; exit 1; }
    shopt -s nullglob
    signed=0
    for binary in "$DIST"/svenager-agent-linux-*; do
        [[ "$binary" == *.sig ]] && continue
        openssl pkeyutl -sign -inkey "$KEYFILE" -rawin -in "$binary" | base64 > "$binary.sig"
        echo "signed $(basename "$binary")"
        signed=$((signed + 1))
    done
    [ "$signed" -gt 0 ] || { echo "no binaries in $DIST" >&2; exit 1; }
    ;;
*)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
