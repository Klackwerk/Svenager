# Single sign-on (OIDC)

Svenager can authenticate operators against any OpenID Connect provider.
Local accounts keep working — the seeded `admin` stays your break-glass
login.

## How it works

- The login screen shows a "Continue with …" button when SSO is enabled.
  It redirects to `/api/v1/auth/sso/oidc`, from there to your IdP, and the
  callback (`/api/v1/auth/sso/callback/oidc`) establishes the same
  server-side session a local login uses.
- Accounts are provisioned just-in-time on first sign-in (marked "SSO" on
  the Users page). Their username comes from `preferred_username`
  (fallback: `email`, then `sub`).
- The IdP is the source of truth: role and device-group access are
  re-evaluated at **every** sign-in from the group claim. Local role or
  password changes on SSO users are rejected; disabling them locally works
  and blocks sign-in regardless of the IdP.
- A sign-in whose groups map to no role is rejected (unless
  `defaultRole` is set). A local account with the same username is never
  taken over.

## Role and device-group resolution

For each sign-in, Svenager reads the claim named by `roleClaim`
(default `groups`, a list of group names) and resolves:

1. **Admin group** — membership in `svenager.sso.adminGroup` grants ADMIN.
2. **Dynamic mappings** — managed on the Users page (admin-only): each
   mapping grants a role, access to a device group, or both. This is the
   recommended way; changes need no server restart and apply at the next
   sign-in.
3. **Static `roleMapping`** — optional YAML map for config-as-code setups.

The highest granted role wins (ADMIN > OPERATOR > VIEWER). Admins always
see the whole fleet. A non-admin whose groups map to device groups is
scoped to exactly those groups — devices, jobs, rollouts, remote sessions
and the dashboard are filtered accordingly; without any device-group
mapping they stay fleet-wide.

## Server configuration

All via environment variables (or `application.yml` under `svenager.sso`):

| Variable | Meaning | Default |
|---|---|---|
| `SVENAGER_SSO_ENABLED` | Master switch | `false` |
| `SVENAGER_SSO_ISSUER` | OIDC issuer URI (discovery must be reachable at startup) | — |
| `SVENAGER_SSO_CLIENT_ID` | Client id registered at the IdP | `svenager` |
| `SVENAGER_SSO_CLIENT_SECRET` | Client secret | — |
| `SVENAGER_SSO_LABEL` | Text on the login button | `Single sign-on` |
| `SVENAGER_SSO_SCOPES` | Requested scopes | `openid,profile,email` |
| `SVENAGER_SSO_ROLE_CLAIM` | Claim carrying the group names | `groups` |
| `SVENAGER_SSO_ADMIN_GROUP` | IdP group whose members become ADMIN automatically | — |
| `SVENAGER_SSO_DEFAULT_ROLE` | Role for users with no mapped group; empty = reject | — |

Redirect URI to register at the IdP:
`https://<svenager-host>/api/v1/auth/sso/callback/oidc`
(during development with the Vite proxy: `http://localhost:5173/api/v1/auth/sso/callback/oidc`).

## Example: Authentik

1. **Create the provider**: Applications → Providers → Create →
   *OAuth2/OpenID Provider*.
   - Authorization flow: your default (implicit consent recommended).
   - Client type: *Confidential*; note client ID and secret.
   - Redirect URI: `https://svenager.example.org/api/v1/auth/sso/callback/oidc`
   - Scopes: keep the defaults — Authentik's built-in `profile` scope
     already includes the user's group names in the `groups` claim.
2. **Create the application**: Applications → Create, pick the provider.
3. **Groups**: create e.g. `svenager-admins`, `kiosk-operators` and assign
   users.
4. **Svenager**:

```sh
SVENAGER_SSO_ENABLED=true
SVENAGER_SSO_ISSUER=https://auth.example.org/application/o/svenager/
SVENAGER_SSO_CLIENT_ID=<client id>
SVENAGER_SSO_CLIENT_SECRET=<client secret>
SVENAGER_SSO_LABEL=Authentik
SVENAGER_SSO_ADMIN_GROUP=svenager-admins
```

(The issuer is the provider's *OpenID Configuration Issuer* shown on the
provider page — it ends with `/application/o/<app-slug>/`.)

5. In Svenager → Users → *SSO group mappings*, map e.g. `kiosk-operators`
   → role OPERATOR + device group "Kiosks". Members then manage only that
   group.

## Example: Keycloak

Keycloak does not send group names by default — add a mapper once:

1. **Client**: Clients → Create client → OpenID Connect, client ID
   `svenager`, *Client authentication* on; note the secret (Credentials
   tab). Valid redirect URI:
   `https://svenager.example.org/api/v1/auth/sso/callback/oidc`
2. **Groups claim**: Client scopes → create scope `groups` (default,
   protocol openid-connect) → Mappers → Add mapper → *Group Membership*:
   token claim name `groups`, *Full group path* **off**, add to ID token
   and userinfo. Then Clients → svenager → Client scopes → add `groups`
   as a default scope.
3. **Groups**: create `svenager-admins`, `kiosk-operators`; assign members.
4. **Svenager**:

```sh
SVENAGER_SSO_ENABLED=true
SVENAGER_SSO_ISSUER=https://kc.example.org/realms/<realm>
SVENAGER_SSO_CLIENT_ID=svenager
SVENAGER_SSO_CLIENT_SECRET=<client secret>
SVENAGER_SSO_LABEL=Keycloak
SVENAGER_SSO_SCOPES=openid,profile,email,groups
SVENAGER_SSO_ADMIN_GROUP=svenager-admins
```

(Alternative without the mapper: use realm roles and set
`SVENAGER_SSO_ROLE_CLAIM=realm_access.roles` — not supported, the claim
must be top-level; prefer the group mapper above.)

## Troubleshooting

- **"no Svenager role is mapped for this account"** on the login screen:
  the user signed in at the IdP but none of their groups match the admin
  group, a dynamic mapping or `roleMapping`. Add a mapping or set
  `SVENAGER_SSO_DEFAULT_ROLE=VIEWER`.
- **Server fails to start** with SSO enabled: OIDC discovery could not
  reach `SVENAGER_SSO_ISSUER`. The issuer must be up when Svenager starts.
- **Groups missing** (every login rejected): verify the IdP actually puts
  the group list into the `groups` claim of the ID token or userinfo
  response (Keycloak needs the mapper above).
- Sign-ins and rejections appear in the audit log (`login`,
  `login-failed`).
