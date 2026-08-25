# Svenager — Architecture

## Context

Greenfield, open-source client–server fleet management for Linux devices (Debian/Raspbian), e.g. kiosk/self-service terminals. One core server (Grails 7 REST API + Vite/React/Bootstrap frontend) manages devices that enroll via an API endpoint and then **pull** periodically for work. Configuration is applied through **Ansible repositories** maintained by professionals in their own git repos, while day-to-day device/group management is done by non-professionals in a friendly UI. Includes group dashboards (last contact / last execution), and browser-based VNC access to devices.

Key design decisions:
- **Job execution:** pull model — agent checks out the Ansible repo and runs `ansible-playbook -c local` on the device, reports results. Outbound HTTPS only, NAT-friendly.
- **Agent:** Go static binary, systemd service, shipped as `.deb` (amd64, arm64, armv7/v6).
- **Remote view:** noVNC in the browser + agent-initiated reverse WebSocket tunnel (VNC server bound to localhost on devices).
- **Stack:** Apache Grails 7 (scaffolded with 7.2.2; Grails 8 is still in milestones), Java 17+, PostgreSQL in development and production (H2 only in tests).

## Architecture overview

```
┌────────────── Browser ──────────────┐
│ React SPA (Bootstrap 5, noVNC)      │
└──────┬──────────────────────────────┘
       │ HTTPS (JSON) + WSS (VNC)
┌──────▼──────────────────────────────┐        ┌─────────────────┐
│ Svenager Server (Grails 7 REST API) │◀──git──│ Ansible repo(s)  │
│ • enrollment, devices, groups       │  clone │ (owned by pros,  │
│ • repo analyzer (roles, arg specs)  │        │ own CI/molecule) │
│ • job composer + queue              │        └─────────────────┘
│ • stats, audit, tunnel broker       │
│ PostgreSQL                          │
└──────▲──────────────────────────────┘
       │ HTTPS poll + WSS tunnel (outbound only)
┌──────┴──────────────────────────────┐
│ Device: svenager-agent (Go, systemd)│
│ • enroll, heartbeat, poll jobs      │
│ • git checkout → ansible-playbook   │
│   -c local, streams logs back       │
│ • VNC server on 127.0.0.1 (base     │
│   role), reverse tunnel on demand   │
└─────────────────────────────────────┘
```

## Monorepo layout

```
Svenager/
  server/        # Grails 7 REST API (grails create-app … --profile rest-api)
  frontend/      # Vite + React + TS + react-bootstrap
  agent/         # Go agent (goreleaser + nfpm for .deb)
  ansible/       # Reference/example Ansible repo: base role (agent config, VNC),
                 #   demo roles — independently testable (ansible-lint + molecule)
  deploy/        # docker-compose (postgres, server, frontend/nginx, reverse proxy)
  docs/          # architecture, agent protocol, ansible repo convention, ops guide
  .github/workflows/
  LICENSE (AGPL-3.0-or-later), README, CONTRIBUTING, SECURITY.md, CODE_OF_CONDUCT
```

The `ansible/` reference repo doubles as the documented **convention**: any external repo following it is analyzable by the server and testable on its own (molecule against a Debian container) with zero dependency on Svenager.

## Server (Grails 7)

### Domain model
- `User`, `Role` (Spring Security Core; `ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_VIEWER`)
- `EnrollmentToken` — label, hashed token, maxUses/usedCount, expiresAt, target group(s)
- `Device` — uuid, hostname, arch, os info, agentVersion, hashed device API token, status, `lastContactAt`, `lastJobAt`, facts (jsonb)
- `DeviceGroup` + m2m membership
- `AnsibleRepository` — name, git URL, branch, `authType` (`NONE`, `SSH_KEY`, `HTTPS_TOKEN`) with the matching credentials (deploy key or username/token, encrypted at rest), lastSyncedAt, lastCommit, syncStatus
- `DiscoveredRole` — repo, name, path, friendly description, argument spec (jsonb), defaults
- `GroupRoleAssignment` — group ⇄ role, execution order, enabled flag
- `ConfigVariable` — scope (group|device), key, typed value (jsonb), `secret` flag (AES-GCM encrypted)
- `Job` — device, type (`APPLY_CONFIG`, `PING`, `OPEN_TUNNEL`, `AGENT_UPDATE`), payload (jsonb), status (`PENDING→DELIVERED→RUNNING→SUCCEEDED|FAILED|TIMED_OUT`), timestamps, exitCode
- `JobLogChunk`, `RemoteSession`, `AuditLogEntry`

### API (versioned `/api/v1`)
Agent-facing (Bearer device token):
- `POST /enroll` — enrollment token + host facts → `{deviceId, deviceToken}` (token returned once, stored hashed)
- `POST /agent/checkin` — heartbeat + fact deltas; response carries next pending job (if any) and server-tunable poll interval → updates `lastContactAt`
- `POST /agent/jobs/{id}/events` — status transitions + log chunks (idempotent, sequenced)
- `WSS /agent/tunnel/{sessionId}` — reverse tunnel endpoint

UI-facing (session auth, RBAC, CSRF-safe):
- CRUD for devices, groups, repos, role assignments, variables, enrollment tokens, users
- `POST /repos/{id}/sync`, `GET /repos/{id}/roles`
- `POST /groups/{id}/apply` and `POST /devices/{id}/apply` — enqueue `APPLY_CONFIG`
- `GET /dashboard` — per-group aggregates
- `POST /devices/{id}/remote-session` + `WSS /ui/vnc/{sessionId}` (noVNC side)

### Job composition ("dynamic inventory", inverted)
On apply, the server composes a per-device **play spec**: repo URL + **pinned commit**, ordered role list from the device's groups (general/base role always first), and merged extra-vars (repo role defaults < group vars < device vars — precedence documented in UI). **Convergence:** at every check-in the server hashes the composed spec (plays + extra-vars) and auto-queues an `APPLY_CONFIG` when it differs from the last delivered one — variable edits, role/group changes and newly synced commits reach the device by its next pull without pressing Apply. A failed apply with an unchanged spec is retried automatically at later check-ins, bounded by `svenager.jobs.maxAttempts` (default 3). Repositories are synced on a schedule (`svenager.repos.syncIntervalSeconds`) and their ref may be a **branch or a tag**. The agent materializes a small playbook including those roles and runs it locally. Commit pinning gives reproducibility and an honest answer to "what exactly ran on this device". A read-only export endpoint additionally serves a standard Ansible dynamic-inventory JSON so professionals can run push-mode ad hoc from their workstation if they ever want to.

### Ansible repo analyzer (the "friendly AWX" part)
Scheduled + on-demand sync per repo: shallow clone into an isolated workdir, then parse — never execute — repo content:
- `roles/*/meta/main.yml` → friendly name, description, tags
- `roles/*/meta/argument_specs.yml` → typed variable forms (name, type, required, description, choices)
- `roles/*/defaults/main.yml` → default values shown in the UI
- Repo-level `svenager.yml` (our convention, optional) → curated display names, which roles are user-assignable vs internal

Non-professionals then see role *cards* with plain descriptions and typed forms instead of YAML; professionals keep full control in git and get a raw YAML view in the UI.

## Agent (Go, `svenager-agent`)

- CLI: `svenager-agent enroll --server URL --token XYZ`, `run`, `status`; config in `/etc/svenager/agent.json`, device token in a root-only 0600 file next to it; workdirs under `/var/lib/svenager/` (repo cache, job workspaces).
- systemd best practice: `Type=notify` with sd_notify + `WatchdogSec`, `Restart=on-failure` with backoff; poll loop with jitter (default 60 s, server-tunable) and exponential backoff on failures; single-flight job execution (lock), per-job timeout, log streaming in sequenced chunks with retry; survives reboot/network loss idempotently.
- Executes `git` + `ansible-playbook` as subprocesses (both from Debian packages, installed by the base role / bootstrap script); verifies the pinned commit before running.
- Reverse tunnel: on `OPEN_TUNNEL` job, dials `wss://server/agent/tunnel/{id}` and pipes to `127.0.0.1:5900`; time-limited, closed server-side when the operator leaves.
- Distribution: goreleaser builds (amd64/arm64/armv7/armv6), nfpm-built `.deb` with systemd unit + postinst; one-line bootstrap script (documented, checksummed) for first install; `AGENT_UPDATE` job type for self-update with signature verification (later milestone).

## Frontend (Vite + React + TS + Bootstrap 5)

Stack: react-bootstrap, react-router, TanStack Query, @novnc/novnc. Vite dev proxy to the Grails API; production build served by nginx behind the same reverse proxy (single origin, no CORS headaches).

Pages: **Dashboard** (group cards: online/offline counts, last contact, last execution, job success rate, drill-down) · **Devices** (list with live status badges; detail: facts, group membership, variables, job history with live log, "Remote view" button) · **Groups** (membership, ordered role assignment, group variables) · **Ansible sources** (repo cards, sync status, discovered role cards with descriptions + variable docs) · **Jobs** (history/filter, live log viewer) · **Enrollment** (token generation with QR/one-liner) · **Users/Settings**.

Usability commitments (Nielsen + Shneiderman, enforced as a UI checklist in [ux-conventions.md](ux-conventions.md)): visible system status everywhere (status badges, job progress, sync spinners, toasts for every action); recognition over recall (role descriptions and typed forms, not YAML — raw view behind an "expert" toggle); error prevention (typed/validated variable inputs from argument specs, confirm dialogs naming the affected device count for destructive actions); user control (cancel pending jobs, no silent bulk changes); consistency via Bootstrap components; shortcuts for power users; informative error messages with next steps. Mobile: responsive grid, offcanvas navigation, dashboard-first layout tested at 375 px.

## Security model

- TLS everywhere (reverse proxy in `deploy/`); agent validates server certificate.
- Enrollment tokens: generated in UI, limited-use + expiring, hashed at rest; optional admin-approval mode for new devices. Per-device API tokens: 256-bit random, hashed (bcrypt/argon2), rotatable, scoped to agent endpoints only.
- Secret variables encrypted at rest (AES-GCM, master key from env/KMS), delivered only inside job payloads.
- VNC bound to 127.0.0.1 and deliberately unauthenticated at the VNC layer: the only path to it is the audited, time-limited tunnel session brokered by the server, which requires an authenticated operator. A VNC password would protect against local users on the device itself only; add one via role variables if that threat matters for a fleet.
- RBAC on all UI endpoints; full audit log of admin actions; rate limiting on enroll/checkin.
- OSS hygiene: AGPL-3.0-or-later, SECURITY.md with disclosure policy, Renovate/Dependabot, CodeQL + OWASP dependency check, ansible-lint/golangci-lint/eslint in CI, no secrets in repo (documented env-based config).

Milestones live in [roadmap.md](roadmap.md).

## Verification

- **Per-milestone e2e:** docker-compose up; a Debian container/VM running the agent enrolls against the dev server, appears in the UI, receives a job, and the reference repo's role applies — verified from the dashboard.
- **Automated:** Spock unit/integration tests for enrollment, token hashing, job composition and analyzer (fixture repos); Go table tests for poll/backoff/state machine + an httptest-based fake server; Vitest + Testing Library for UI logic; molecule + ansible-lint for the reference repo; a GitHub Actions e2e job wiring server + agent container together.
- **Security checks:** CI dependency scanning; manual pass over auth flows before first release (plus `/security-review`).
