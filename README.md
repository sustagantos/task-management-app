# task-management-app

Task manager with first-class analytics over the task history. Multi-user with
private lists: anyone the Entra app registration admits gets an account and
sees only their own tasks.

Full design record in [docs/spec.md](docs/spec.md).

## Status

**M0** - scaffold. Entra sign-in, user provisioning and the database schema are
in place; there is no task UI yet. `/api/me` proves the whole auth path works
end to end.

| M | Deliverable | State |
|---|---|---|
| M0 | Repo, CI, container build, CT, Entra registration, hello world behind login | in progress |
| M1 | Owner-scoped CRUD, event log, main page, quick add, hierarchy rules | |
| M2 | History page, closed-today boundary incl. midnight rollover | |
| M3 | Tier 1 analytics + weekly review page | |
| M4 | Tier 2 analytics | |
| M5 | Tier 3 analytics | |
| M6 | Backup job, restore drill, Uptime Kuma check | |

## Stack

Java 21 + Spring Boot, Spring Data JPA, Flyway, PostgreSQL 16, React + Vite +
TanStack Query + Recharts. The SPA builds into the jar's static resources, so
one artifact serves both API and UI from one origin.

## Running it locally

You need JDK 21, Node 22 and Docker.

Start Postgres:

```bash
docker run --rm -p 5432:5432 -e POSTGRES_DB=tasks -e POSTGRES_USER=tasks -e POSTGRES_PASSWORD=tasks postgres:16-alpine
```

Export the Entra settings (values from Vaultwarden), then run the backend:

```bash
ENTRA_TENANT_ID=... ENTRA_CLIENT_ID=... ENTRA_CLIENT_SECRET=... mvn spring-boot:run
```

And the frontend in another terminal:

```bash
cd frontend && npm install && npm run dev
```

Open http://localhost:5173. Vite proxies `/api`, `/oauth2`, `/login` and
`/logout` to Spring on 8080, so the browser sees one origin and the session
cookie and Entra redirect behave as they do in production.

The Entra app registration needs `http://localhost:8080/login/oauth2/code/entra`
as a redirect URI for this to work. Plain http is permitted for localhost only.

## Deployment

CT 217 (`192.168.99.217`) on pve3, published as `tasks.lab.patobytes.com` via
NPMPlus, reached off-LAN over Tailscale. GitHub Actions builds and pushes to
GHCR on every push to `main`; the container host pulls.

Copy `.env.example` to `.env` on the host, `chmod 600`, and fill it from
Vaultwarden. Then:

```bash
docker compose pull && docker compose up -d
```

## Conventions

No emojis or non-UTF-8 characters in scripts. Secrets never enter git -
Vaultwarden holds them and this repo references their location only.
