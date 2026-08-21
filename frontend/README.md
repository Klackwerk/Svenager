# Svenager frontend

React SPA (Vite, React 19, react-bootstrap, TanStack Query) for the Svenager
fleet management platform. It talks to the Grails server under `/api`, which
the dev server proxies to `http://localhost:8080` (see `vite.config.ts`).

## Development

```sh
npm install
npm run dev        # http://localhost:5173, API proxied to :8080
```

Start the Grails server first (`cd ../server && ./gradlew bootRun`, needs the
dev Postgres from `../deploy/docker-compose.yml`). Default dev login: admin/admin.

## Checks

```sh
npm test           # Vitest (jsdom + testing-library)
npm run lint       # oxlint
npm run build      # type-check (tsc -b) + production bundle
```

All three must pass before committing. Tests live next to their subject as
`*.test.tsx` / `*.test.ts`.

## Conventions

- react-bootstrap components and Bootstrap utility classes only — no extra
  CSS frameworks or state libraries.
- Never show Ansible/YAML jargon to operators; UI copy speaks about devices,
  groups, roles and jobs.
- Accessibility: no color-only status indicators, label icon-only controls,
  keep `aria-sort` / `role="status"` patterns.
- Every mutation must resolve visibly: inline alert or the central toast
  layer (`src/components/ToastProvider.tsx`, wired via `src/api/queryClient.ts`).
