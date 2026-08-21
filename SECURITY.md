# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Instead, report
vulnerabilities privately via GitHub Security Advisories ("Report a
vulnerability" on the repository's Security tab).

We aim to acknowledge reports within 7 days. Please include a description of
the issue, steps to reproduce, and the affected component (`server`,
`frontend`, `agent`, or `ansible`).

## Supported versions

Until 1.0, only the latest release receives security fixes.

## Design notes for reviewers

- Devices authenticate with per-device bearer tokens (256-bit random, stored
  hashed). Enrollment uses limited-use, expiring tokens.
- Devices only make outbound HTTPS/WSS connections; VNC is bound to
  `127.0.0.1` and only reachable through audited, time-limited reverse
  tunnels.
- Secret configuration variables are encrypted at rest (AES-GCM).

See [docs/architecture.md](docs/architecture.md) for the full security model.
