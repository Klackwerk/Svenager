# Changelog

## 0.10.1 — 2026-08-25

- Fix: `SVENAGER_ENCRYPTION_KEY` was never bound to the setting
  `CryptoService` reads, so production instances failed with "No
  encryption key configured" (HTTP 500) as soon as a repository
  credential or secret variable was stored — even with the variable set.
  Affected 0.9.0 and 0.10.0; no data was written with a wrong key.
- The server now verifies the encryption key at startup and refuses to
  boot in production without one, instead of failing on first use.

## 0.10.0 — 2026-08-25

- Private Ansible repositories over HTTPS: a repository can carry a
  username and token (GitLab/GitHub access or deploy tokens), stored
  encrypted and handed to git through a credential helper — never in
  the URL, the process arguments or on disk. Devices keep receiving
  bundles from the server and never see repository credentials.
- SSH repositories accept your own private key in addition to the
  generated deploy key; the matching public key is shown for the
  hosting side. SSH runs in batch mode so a passphrase or unknown key
  fails the sync instead of hanging it.
- Credentials are editable in place ("Edit" on the source card):
  switch between none / HTTPS token / SSH key, rotate the token or
  deploy key, keep the stored secret by leaving the field blank. The
  API exposes `authType`, `authUsername` and `hasCredentials`; the
  secret is never returned.
- Schema change: Flyway `V2__repository_auth.sql` (existing deploy-key
  repositories become `SSH_KEY`, the rest `NONE`).
- First startup registers the reference Ansible repository
  ([Klackwerk/ansible-svenager](https://github.com/Klackwerk/ansible-svenager))
  as a default source, so a new instance has assignable roles out of the
  box. Override the URL with `SVENAGER_DEFAULT_ANSIBLE_REPO` (empty
  disables seeding); delete it in the UI if unwanted.
- The reference Ansible repository moved to its own repo and is embedded
  as the `ansible/` submodule.
- The kiosk role now boots into the browser on a full Debian desktop
  install, not just a console image: it masks a conflicting display
  manager, enables lingering so the compositor gets an
  `XDG_RUNTIME_DIR`, and drops the login session's leaked ambient
  capability so WebKit's sandboxed processes start (the sandbox stays on).
- The enrollment one-liner (`install.sh`) installs `git` and
  `ansible-core` when missing, so a bare Debian can run its first apply;
  the agent `.deb` accepts `ansible-core | ansible`. The agent's job log
  names the missing `ansible-playbook` instead of a raw exec error.
- Exhausted apply retries are visible: jobs report `attempt`,
  `maxAttempts` and `retriesExhausted`; the device page shows a warning
  with an inline "Apply configuration", the job page explains that
  "Re-run" starts a fresh attempt counter, job tables carry a "No more
  retries" badge.

## 0.9.0 — 2026-08-21

First public release of Svenager: fleet management for Linux clients and
servers. Ansible professionals maintain roles in git; operators run the
fleet from a friendly web UI. Devices only make outbound HTTPS connections.

### Devices & enrollment
- Enrollment via tokens: QR code / one-line install command, optional admin
  approval, tokens can target a device group directly.
- Live device status (online/offline), device disable, per-group agent poll
  interval, server-side search and filtering at fleet scale.
- Dynamic-inventory export for use from plain Ansible.

### Configuration & rollouts
- Groups with ordered role assignment; typed variable forms generated from
  the repository's argument specs, with validation and safe merges.
- Jobs pin an exact git commit — "what ran on this device" always has an
  answer — with live log streaming.
- Batch rollouts with roll-up and retry, canary mode, dry-run previews
  (`ansible --check`), bounded auto-retry (3 attempts), scheduled applies,
  blast-radius confirmation before group applies.
- Config-drift detection with auto-apply; scheduled repository sync.

### Remote view
- Browser-based VNC (noVNC) over audited, time-limited, agent-initiated
  reverse tunnels — no inbound connections to the device.
- Remote input forwarding and concurrent viewers.

### Users & access
- Role-based access control (admin / operator / viewer), group-scoped
  operators.
- OIDC single sign-on with just-in-time provisioning, auto-admin IdP group
  and dynamic IdP-group → role/device-group mappings (Authentik and
  Keycloak examples in docs/sso.md).

### Observability
- Alerting via email and webhooks (device offline, job failures) with
  per-group thresholds.
- Audit log with admin UI and file logging; job retention with pruning.

### UI
- Dashboard, global search (Ctrl+K), dark mode, expert-details mode,
  toasts and confirmations throughout.

### Agent & operations
- Go agent as `.deb` (systemd `Type=notify` + watchdog) for amd64/arm64;
  self-update with Ed25519 signature verification.
- Flyway-managed PostgreSQL schema; UUID API identifiers; rate limiting on
  public endpoints and login; actuator locked down to health.
- Production docker compose with automatic TLS; operations guide in
  docs/operations.md.

Known constraint: one server instance per database (in-memory tunnel broker
and rate-limit state).
