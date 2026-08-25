# Operations guide

Running Svenager in production. First-time setup lives in
[quickstart.md](quickstart.md), development setup in the README;
architecture in [architecture.md](architecture.md), SSO in [sso.md](sso.md).

## Deploy

```sh
cd deploy
cp .env.example .env        # domain, DB password, encryption key
docker compose up -d
```

This runs the release images from `ghcr.io/klackwerk/svenager` (pin a
version with `SVENAGER_VERSION` in `.env`); `docker compose up -d --build`
builds from source instead.

Containers: `postgres` (data volume), `server` (Grails API, Flyway-managed
schema), `frontend` (static SPA), `caddy` (TLS via Let's Encrypt, routes
`/api`, `/install*`, `/kiosk-demo` and websockets to the server, the rest
to the SPA). The domain in `.env` must resolve to the machine and ports
80/443 must be reachable for certificate issuance.

First start: if `SVENAGER_ADMIN_PASSWORD` was left empty, the generated
admin password is logged **once** — `docker compose logs server | grep
"generated password"`. Change it after signing in.

## What to back up

| What | Why |
|---|---|
| `svenager-db` volume (or `pg_dump svenager`) | all state |
| `SVENAGER_ENCRYPTION_KEY` (from `.env`) | without it, stored secrets and repository credentials (deploy keys, tokens) are unrecoverable |
| agent-update private key (offline, see below) | ability to sign agent updates |

The `svenager-data` volume (repo checkouts, agent binaries) is
reconstructible but faster to restore than to rebuild.

## Upgrades

1. Bump `SVENAGER_VERSION` in `.env` (or leave it on the latest release),
   then `docker compose pull && docker compose up -d`. Flyway applies
   pending schema migrations on startup; the server refuses to start on a
   failed migration rather than running on a half-migrated schema.
2. Agent fleet: see "Agent distribution and self-update" below.

## Agent distribution and self-update

- Build packages: `cd agent && goreleaser release --snapshot --clean`
  (`dist/*.deb` plus raw binaries).
- Host binaries for the enrollment one-liner and self-update: copy them as
  `svenager-agent-linux-<arch>` into the server's `agent-dist` directory
  (the `svenager-data` volume) — `scripts/dev-agent-dist.sh` does this in
  development.
- **Sign them** (self-update refuses unsigned binaries):
  - once: `scripts/agent-update-keys.sh gen` — keep the private key
    offline (never on the Svenager server); put the printed
    `update_public_key` into each device's `/etc/svenager/agent.json`
    (e.g. via the base role).
  - per release: `scripts/agent-update-keys.sh sign <key> <dist-dir>`
    creates the `.sig` files next to the binaries.
- Trigger: device page → "Update agent" (or `POST
  /api/v1/devices/{id}/update-agent`). At its next check-in the agent
  downloads binary + signature, verifies Ed25519, replaces itself and
  re-executes in place; the job log shows every step.

## Monitoring

- `GET /actuator/health` is public (used by the compose healthchecks).
- Alerting page (admin): email/webhook channels for device-offline,
  exhausted apply retries and repo-sync failures.
- Exhausted apply retries (`svenager.jobs.maxAttempts`, default 3) are
  flagged on the device and job pages; the device stays unconverged until
  an operator presses "Apply configuration" / "Re-run" (fresh attempt
  counter) or the composed configuration changes.
- Audit page (admin): sign-ins and every administrative change.
- Server log file: `logs/svenager.log` inside the server container
  (path via `LOG_FILE`/`LOG_PATH`).

## Constraints

- **Single server instance only.** Remote-view tunnel pairings
  (`TunnelBroker`) and rate-limit buckets (`RateLimitService`) are
  in-memory, and user sessions live in the servlet session store. Run one
  `server` container; scale reads by giving PostgreSQL room instead.
  Moving this state to PostgreSQL/Redis is future work if horizontal
  scaling becomes necessary.
- OIDC discovery runs at startup: with SSO enabled, the IdP must be
  reachable when the server boots (see [sso.md](sso.md)).

## Security posture (review summary)

- TLS terminates at Caddy; the server trusts `X-Forwarded-*` only because
  `SVENAGER_TRUST_FORWARDED_FOR=true` is set for exactly this topology.
- Actuator exposes health only; everything else requires ADMIN and is not
  exported.
- Rate limits: public endpoints per address, sign-ins per
  address+username, agent traffic per device id.
- Secrets (variables, repository deploy keys and tokens) are AES-256-GCM
  encrypted at rest; repository credentials are only ever used by the
  server's git subprocess (token via credential helper, key via a
  short-lived 0600 temp file) and never reach devices;
  enrollment/device/API tokens are stored hashed; raw values appear
  exactly once.
- Agent self-update requires a valid Ed25519 signature against a key that
  never leaves the operator's machine; the update endpoint only serves
  files matching a strict platform whitelist.
- VNC binds to 127.0.0.1 on devices; the only path in is the
  authenticated, audited, time-limited tunnel.
