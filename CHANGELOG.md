# Changelog

## Unreleased

- First startup registers the reference Ansible repository
  ([Klackwerk/ansible-svenager](https://github.com/Klackwerk/ansible-svenager))
  as a default source, so a new instance has assignable roles out of the
  box. Override the URL with `SVENAGER_DEFAULT_ANSIBLE_REPO` (empty
  disables seeding); delete it in the UI if unwanted.
- The reference Ansible repository moved to its own repo and is embedded
  as the `ansible/` submodule.

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
