# Svenager

**S**creen **v**irtual **en**vironment man**ager** — an open-source client–server
solution for managing fleets of Linux devices (Debian/Raspbian), such as kiosks
and self-service terminals.

Devices enroll against a central server, check in periodically over outbound
HTTPS, and apply configuration by running Ansible locally against professionally
maintained Ansible repositories. Day-to-day management (devices, groups,
variables, dashboards, browser-based VNC) happens in a friendly web UI designed
for non-professionals.

## Components

| Directory   | What it is |
|-------------|------------|
| `server/`   | Core REST API — [Apache Grails 7](https://grails.apache.org) (Java 17+, PostgreSQL) |
| `frontend/` | Web UI — Vite + React + TypeScript + Bootstrap 5 |
| `agent/`    | Device agent — Go static binary, runs as a systemd service |
| `ansible/`  | Reference Ansible repository (documents the repo convention, independently testable) |
| `deploy/`   | Docker Compose and deployment examples |
| `docs/`     | Architecture, agent protocol, Ansible repo convention |

## How it works

```
Browser ── HTTPS/WSS ──▶ Server (Grails API + SPA)
                           ▲   ▲
                           │   └── git: analyzes registered Ansible repos
        outbound HTTPS ────┘        (roles, argument specs → friendly UI)
Device agent: enroll → poll → run ansible-playbook -c local → report
```

- **Pull-based**: devices only make outbound connections — NAT-friendly, no
  inbound SSH.
- **Ansible-native**: professionals keep full control in their own git repos;
  the server analyzes roles and argument specs and turns them into friendly
  forms and role cards.
- **Reproducible**: jobs pin an exact git commit, so "what ran on this device"
  always has an answer.
- **Remote view**: devices run a localhost-bound VNC server; operators connect
  through the browser (noVNC) via an audited, agent-initiated reverse tunnel.

## Quick start (Docker Compose)

Release images are published to `ghcr.io/klackwerk/svenager/{server,frontend}`.
With a domain pointing at a Docker host:

```bash
git clone https://github.com/Klackwerk/Svenager.git
cd Svenager/deploy
cp .env.example .env      # domain + secrets
docker compose up -d
```

Full walkthrough — first sign-in, hosting agent binaries, enrolling the
first device: [docs/quickstart.md](docs/quickstart.md). Backups, upgrades
and monitoring: [docs/operations.md](docs/operations.md).

## Local development

Prerequisites: **Java 17** (Gradle/Grails require it — set `JAVA_HOME`
accordingly), Node 20+, Go 1.26+, git. Docker only if you don't already run
PostgreSQL.

### 1. Database (PostgreSQL)

The server expects PostgreSQL on `localhost:5432` with database/user/password
`svenager` in development. Two options:

- **Existing PostgreSQL** (keeps your current data): nothing to do — the
  server connects to `jdbc:postgresql://localhost:5432/svenager` by default
  and manages the schema with Flyway (an existing pre-Flyway database is
  baselined in place). Different credentials/host? Override with
  `SVENAGER_DB_URL`, `SVENAGER_DB_USER`, `SVENAGER_DB_PASSWORD`.
- **Fresh via Docker**: `cd deploy && docker compose -f docker-compose.dev.yml
  up -d` (skip this if something already listens on 5432).

### 2. API server

```bash
cd server
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS; on Linux point at a JDK 17
# The address DEVICES use to reach this instance (baked into enrollment
# one-liners). Unset = derived from each request, which also works behind
# a reverse proxy — set it in dev so commands are pasteable onto devices:
export SVENAGER_EXTERNAL_URL=http://<your-lan-ip>:8080
./gradlew bootRun
```

First start seeds an `admin`/`admin` user (development/test only).

### 3. Agent binaries for one-step enrollment

The server serves agent builds from `server/agent-dist/` at
`/install/agent/…` so the Enrollment page can offer a copy-paste command
that installs and enrolls a device in one step:

```bash
./scripts/dev-agent-dist.sh          # builds linux amd64/arm64/armv7/armv6
```

### 4. Web UI

```bash
cd frontend && npm install && npm run dev
```

Open http://localhost:5173 and sign in with `admin`/`admin`. The Vite dev
server proxies `/api` (including the remote-view WebSocket) to
`localhost:8080`.

### 5. Ansible repository for testing

Register any git URL under *Ansible sources* — for local development a bare
clone of the bundled reference repo works well:

```bash
git init --bare ~/.svenager-dev/ansible-config.git
git -C ansible init && git -C ansible add -A && git -C ansible commit -m init   # once
git -C ansible push ~/.svenager-dev/ansible-config.git main
```

Register `file:///Users/<you>/.svenager-dev/ansible-config.git` in the UI.
Push further changes there — the scheduled sync plus the check-in drift
detection roll them out to devices automatically.

### 6. Enroll a device

Create a token on the *Enrollment* page and paste the shown one-liner on any
Debian/Raspberry Pi OS machine — it downloads the agent from your instance,
installs the systemd service and enrolls. Pre-imaged devices can instead run
`svenager-agent run --server <url>` and wait for admin approval on the
Enrollment page.

### Tests

```bash
(cd server && ./gradlew test integrationTest)   # Spock unit + HTTP/WS integration
(cd agent && go test ./...)
(cd frontend && npm test && npm run lint)
(cd ansible && ansible-lint && molecule test)   # reference repo, needs Docker
```

See [docs/architecture.md](docs/architecture.md) for the full design and
[docs/roadmap.md](docs/roadmap.md) for milestones.

## License

[AGPL-3.0-or-later](LICENSE)
