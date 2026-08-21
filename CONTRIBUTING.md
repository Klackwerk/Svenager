# Contributing to Svenager

Thanks for your interest! Svenager is a monorepo:

- `server/` — Apache Grails 7 REST API (Java 17+, Groovy, Spock tests)
- `frontend/` — Vite + React + TypeScript + Bootstrap 5
- `agent/` — Go device agent
- `ansible/` — reference Ansible repository (ansible-lint + molecule)

## Development setup

See the quick start in the [README](README.md). Each component builds and
tests independently:

```bash
cd server   && ./gradlew check
cd frontend && npm ci && npm run lint && npm run build
cd agent    && go vet ./... && go test ./... && go build ./...
```

## Pull requests

- Keep changes scoped to one concern; open separate PRs for separate topics.
- Use Conventional Commits (`type(scope): subject`), e.g.
  `feat(server): add enrollment endpoint`.
- Add or update tests for behavior changes; CI must be green.
- UI changes must remain usable on mobile (test at 375 px width) and follow
  the usability checklist in [docs/ux-conventions.md](docs/ux-conventions.md).

## Security issues

Never report security vulnerabilities in public issues — see
[SECURITY.md](SECURITY.md).
