# Task tracker - design record

Design decided 2026-08-24, revised 2026-08-25. Not yet built. Task manager with
first-class analytics over the task history.

Mirrored from the PatoBytes knowledge base (`infra/self-hosted/task-tracker.md`),
which remains the source of truth for the lab-service side. Deployment and
infrastructure changes are recorded there first.

## Allocations

Fixed 2026-08-24, node and VMID revised 2026-08-25.

| Item | Value |
|---|---|
| Code repo | `https://github.com/sustagantos/task-management-app` (personal account) |
| Entra app registration | `task-management-sso`, single tenant |
| Redirect URI (prod) | `https://tasks.lab.patobytes.com/login/oauth2/code/entra` |
| Redirect URI (dev) | `http://localhost:8080/login/oauth2/code/entra` |
| CT VMID | 217 |
| IP | `192.168.99.217/24`, gw `192.168.99.1`, DNS `192.168.99.20` |
| Hostname | `tasks` |
| Node | pve3 |
| LXC features | `nesting = true`, `keyctl = true`, unprivileged (required to run Docker inside) |

**VMID/IP convention:** the last octet equals the VMID for the 2xx containers
(203 npmplus/.203, 204 glance/.204, 205 tailscale-router/.205, 206
mediaserver/.206, 207 windocker/.207, 210 links/.210, 211 semaphore/.211, 213
llm/.213, 214 uptime/.214). 217 continues it and was confirmed free against the
live cluster on 2026-08-25.

**Node choice: pve3**, chosen by Gustavo. Noted for the record that pve3 carries
roughly 57 GB of committed guest memory (llm 30 GB, mediaserver 16 GB, windocker
10 GB) against pve1's ~7.5 GB. The CT is small enough that this is a preference
rather than a constraint, but if pve3 ever comes under memory pressure this is
the first guest that could move.

**Verify before applying.** CT 200 (pegaprox) is documented and holds .200 but
does not appear in `lxc.tf`, so the repo is not a complete picture of VMID
usage. Confirm .217 is both unpinged and outside the DHCP pool.

CT 100 (`docker`) is deliberately not reused: it is on DHCP and
`start_on_boot = false`, so it cannot be a stable reverse-proxy target.

### Entra registration notes

- Platform type is **Web**, not Single-page application. The design is a
  confidential client with a secret and a server-side session; selecting SPA
  switches Entra to a public-client PKCE flow and breaks it.
- The `entra` suffix on the redirect URI is the Spring Security registration id
  and must match `spring.security.oauth2.client.registration.entra` in the app
  config. A mismatch produces a `redirect_uri` error that is tedious to trace.
- **Deliberately not restricted to a single assigned user.** Colleagues in the
  organisation may sign in; each gets their own private list. See "Multi-user
  model" below - this is a data-model decision, not only an Entra setting.
- No API permissions needed: `openid`, `profile` and `email` are implicit and
  require no admin consent.
- Client secret value is shown once. Vaultwarden, and record the expiry - the
  app stops authenticating the day it lapses.

## Multi-user model

Decided 2026-08-25: **private lists on a shared instance.** Anyone in the tenant
may sign in; each person sees only their own tasks and their own analytics.
There is no sharing, no assignment to others, and no cross-user visibility - the
instance is shared, the data is not.

This is why the Entra app registration is deliberately left unrestricted.

Consequences that are cheap now and impossible later:

- `app_user`, provisioned automatically on first successful sign-in from the ID
  token claims. No admin screen, no invitation flow.
- `task.owner_id not null`. It cannot be backfilled - once rows exist without an
  owner, who created what is unknowable.
- **Every** query is scoped by `owner_id`, and that scoping lives in the
  repository layer, not the controller. The realistic failure mode for this app
  is IDOR: `GET /api/tasks/{id}` returning someone else's task because one
  handler forgot a filter. A repository that cannot express an unscoped read
  makes that mistake unavailable rather than merely discouraged.
- Every analytics report is per-user. None of the 13 aggregate across owners.

## Goal

Capture work and personal tasks in under two seconds, close them, and get
honest answers out of the history about throughput, cycle time and what is
quietly rotting. The analytics are a first-class requirement, not a reporting
afterthought - the data model is shaped around them.

## Form factor

Web app, served as a PWA so it installs to the taskbar on Windows and the dock
on Linux and works from a phone. Not a desktop executable: the deciding factor
is that analytics need one authoritative store that outlives any single
machine, and once that exists the client should be a browser.

## Stack

| Layer | Choice | Note |
|---|---|---|
| Backend | Java 21 LTS, Spring Boot 3.x | exact patch versions pinned at implementation |
| Data access | Spring Data JPA + Hibernate | `@Query(nativeQuery=true)` / `JdbcClient` for analytics |
| Migrations | Flyway | versioned SQL, applied on boot |
| Database | PostgreSQL 16 | |
| Frontend | React + Vite + TanStack Query + Recharts | |
| Packaging | React build output into the Spring Boot jar's static resources | one artifact, one origin, no CORS, no second container |
| Auth | Spring Security `oauth2Login` against Entra, server-side session | native OIDC, first preference under `standards/infra-access-baseline.md` rule 6 |

**Prisma is not usable here.** Prisma Client is TypeScript/JavaScript only; the
Go and Python clients are community projects and there is no Java client. The
Prisma MCP server configured in the workspace is not relevant to this project.
Postgres is unaffected by this - only the ORM layer changes.

**Cost of the JVM choice:** roughly 2-3 GB RAM and 2 vCPU on the CT for app
plus Postgres. Go or Node would have run in about a quarter of that. Accepted
deliberately; recorded so it is not rediscovered later.

## Data model

Timestamps are `timestamptz`, stored UTC. The display and bucketing timezone is
a single server-side constant, `America/Sao_Paulo`. Every day-boundary
calculation goes through `at time zone`, never `date(col)` on the raw column.

```sql
create type task_context    as enum ('work','personal');
create type task_status     as enum ('open','done','cancelled');
create type task_event_type as enum (
  'created','closed','reopened','cancelled',
  'priority_changed','reparented','edited'
);

create table app_user (
  id           uuid primary key,
  entra_oid    text not null unique,   -- the 'oid' claim, immutable per user per tenant
  email        text not null,
  display_name text not null,
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz
);

create table task (
  id          uuid primary key,
  owner_id    uuid not null references app_user(id),
  parent_id   uuid,
  title       text not null check (length(btrim(title)) > 0),
  description text,                       -- markdown
  priority    smallint not null default 2 check (priority between 0 and 3),
  context     task_context not null,
  status      task_status  not null default 'open',
  tags        text[] not null default '{}',
  created_at  timestamptz not null default now(),
  closed_at   timestamptz,
  due_at      timestamptz,
  updated_at  timestamptz not null default now(),

  constraint closed_at_matches_status check (
    (status =  'open' and closed_at is null) or
    (status <> 'open' and closed_at is not null)
  ),
  constraint no_self_parent check (parent_id is null or parent_id <> id),

  -- a parent must belong to the same owner, enforced by the database rather
  -- than by remembering to check it in the service layer
  unique (id, owner_id),
  foreign key (parent_id, owner_id) references task (id, owner_id)
);

create index task_open_idx   on task (owner_id, priority, created_at) where status =  'open';
create index task_closed_idx on task (owner_id, closed_at desc)       where status <> 'open';
create index task_parent_idx on task (parent_id);
create index task_tags_idx   on task using gin (tags);

create table task_event (
  id         bigserial primary key,
  task_id    uuid not null references task(id) on delete cascade,
  at         timestamptz not null default now(),
  type       task_event_type not null,
  from_value text,
  to_value   text
);

create index task_event_task_idx on task_event (task_id, at);
create index task_event_type_idx on task_event (type, at);
```

### Why the model looks like this

- **`cancelled` is distinct from `done`.** If abandoned tasks count as
  completions, throughput is inflated and the two can never be separated
  afterwards. One enum value now; unrecoverable if omitted.
- **`closed_at_matches_status` is a DB constraint, not app logic.** The
  analytics are only trustworthy if the storage layer refuses incoherent rows.
- **`task_event` is append-only.** The `task` row stays denormalised so the main
  page is one cheap query; the log is what makes reopen rate, priority churn and
  time-at-priority answerable. Without it those questions are permanently closed.
- **`tags text[]`** exists alongside `context` because work/personal will not
  carry "which client". Retro-tagging a year of history by hand is miserable; an
  empty array costs nothing.
- **`due_at` is optional per task.** Present so report 13 can accumulate
  history - past due dates cannot be reconstructed after the fact.
- **`app_user` keys on the Entra `oid` claim, not email or UPN.** Addresses
  change - renames, marriages, domain moves - and every task the person owned
  would orphan. The object id is immutable for the life of the account.
- **Same-owner parenting is a composite foreign key, not a service-layer
  check.** `unique (id, owner_id)` plus `foreign key (parent_id, owner_id)`
  costs one redundant-looking constraint and makes "child of someone else's
  task" unrepresentable rather than merely tested for.

### Hierarchy rules

`parent_id` is a single self-reference. Two guards, enforced in the service
layer (a DB trigger is possible but not required at this scale):

1. **Max depth 2.** A task that has a parent may not itself be a parent.
   Arbitrary depth makes both the UI and "what is actually open right now"
   materially harder, and depth 5 would never be used.
2. **No cycles.** Rejected on write.

Closing a parent does **not** cascade-close its children. Attempting it while
children are open produces a warning, not a silent mass close. Parents render
their progress as `3/5 done`. A parent still open after its last child closed is
a real failure mode and is reported (see report 10).

## Deferred, with the cost stated

**Recurring tasks are not in v1.** No `recurrence_rule` column. This is a
genuine schema migration in v2 rather than a UI bolt-on - a rule plus generated
instances - and given the MSP workload ("check backups monthly") it will
probably be wanted. Accepted knowingly.

## Pages

### Main page

1. **Quick add** at the top: one input, keyboard shortcut to focus, type title,
   Enter. Defaults to P2 / work, adjustable inline. If capture takes more than
   two seconds, capture stops happening.
2. **Open tasks**, children nested under parents, sorted priority then age.
   Filter chips: All / Work / Personal, plus tag filter.
3. **Closed today**, collapsed with a count, strikethrough, one-click undo.
   Disappears at local midnight.

The closed-today boundary is recomputed on window focus and on a midnight timer,
so a tab left open overnight does not keep showing yesterday's work.

A single line `Yesterday: 7 done ->` links into History. Rationale: an item
closed at 23:50 vanishing ten minutes later feels wrong, and "what did I finish
yesterday" is the most useful thing at 09:00 Monday. The main list rule is
unchanged; the escape hatch is one link.

### History

Date range, full-text search over title and description, filter by context,
status and tag.

### Analytics + weekly review

All 13 reports below. The weekly review page is the one that gets read: closed
last week by context, net backlog change, five oldest open, everything stale.

## Analytics

All of it is plain SQL over a few thousand rows. No warehouse, no ETL, nothing
materialised until something is measurably slow. Reports live in a read-only
analytics package using **native SQL, not JPQL** - JPQL cannot express window
functions, `percentile_cont` or `generate_series` cleanly, and fighting it would
be the single largest waste of effort in the project.

### Tier 1 - flow

| # | Report | Answers | SQL primitive |
|---|---|---|---|
| 1 | Throughput | closures per day and week, split work/personal, trailing 7 and 28 day average | `date_trunc` + window `avg() over` |
| 2 | Cycle time | `closed_at - created_at` as **median and p90, never mean** - one eight-month task destroys an average | `percentile_cont(0.5/0.9)` |
| 3 | Net backlog | created minus closed per week; is the open list growing | two aggregates joined on a `generate_series` week spine |
| 4 | Aging WIP | open tasks bucketed 0-2d / 3-7d / 8-30d / 30d+, **naming the tasks** | `age()` + `case` buckets |

Report 4 is the highest-value chart for a solo operator: it is the one that
surfaces things quietly rotting.

### Tier 2 - behaviour

| # | Report | Answers |
|---|---|---|
| 5 | Priority discipline | median cycle time by priority. If P3 closes as fast as P0, the priority field is decorative |
| 6 | Work/personal split | of creations and of closures, over time - catches drift either way |
| 7 | Closure heatmap | hour x weekday, so the block where things actually finish can be defended in the calendar |
| 8 | Cancellation rate | by priority and context. High cancel rate at P3 is evidence to stop writing P3s |
| 9 | Reopen rate | tasks declared done prematurely. Requires `task_event` |

### Tier 3 - hierarchy and hygiene

| # | Report | Answers |
|---|---|---|
| 10 | Subtree stats | parents by child count, and **dangling parents** (last child closed N days ago, parent still open) |
| 11 | Stale list | open > 14 days with zero events since creation |
| 12 | Streaks | consecutive days with at least one closure; longest streak |
| 13 | Due-date reliability | share closed on or before `due_at`, median lateness |

Reports 7, 12 and the percentiles in 2 are structurally thin until several weeks
of history exist. Expect sparse charts at launch. This is not a reason to defer
them, but it should not read as a bug.

## API surface

```
GET    /api/tasks?status=open|closed-today|all&context=&tag=
POST   /api/tasks
PATCH  /api/tasks/{id}
POST   /api/tasks/{id}/close
POST   /api/tasks/{id}/reopen
POST   /api/tasks/{id}/cancel
GET    /api/tasks/history?from=&to=&q=
GET    /api/analytics/{report}
GET    /api/review/weekly
```

Every mutation writes its `task_event` row in the same transaction as the `task`
update. A missing event is a corrupt analytics history, so this is not optional
and not asynchronous.

## Infrastructure

Uses the existing lab pattern end to end; nothing new is stood up.

| Concern | Approach |
|---|---|
| Compute | New LXC CT, **declared in `infra/iac/lxc.tf`** and applied from CT 211 - not hand-created |
| Sizing | 2 vCPU, 3 GB RAM, 16 GB disk (JVM + Postgres) |
| Runtime | Docker Compose in the CT: `app` + `postgres` |
| DNS + TLS | `register-service.sh --name tasks --host <ip> --port 8080` on CT 203 -> `tasks.lab.patobytes.com`. Pi-hole wildcard and wildcard LE cert id 3 already cover it; the proxy host is the only missing step |
| Auth | Entra app registration, native OIDC in Spring Security |
| Secrets | Vaultwarden master copy, delivered as a `chmod 600` env file on the CT. Never in git, never in compose |
| Remote access | Tailscale, as the media stack already does. Nothing authenticated exposed publicly |
| Backup | Nightly `pg_dump` to the existing target, plus PBS on the CT. **A restore drill is part of the milestone** - untested backups are not backups |
| Monitoring | Uptime Kuma auto-seeds from NPMPlus once the proxy host exists |

Publishing to `nextlevelinfo.com.br` later is a cPanel A record plus a per-host
LE cert - deliberately kept a one-afternoon change rather than a design
assumption.

### Deploy pipeline

GitHub (personal account) -> Actions builds the jar and the container image ->
GHCR -> a Semaphore task on CT 211 runs `docker compose pull && up -d`.
Semaphore is already the ops button surface, so this adds no new tooling.

If the GHCR package is private, the CT needs a fine-grained PAT with
`read:packages`, stored in Vaultwarden per rule 4. Making the image public
avoids that credential entirely and leaks nothing beyond the binary.

## Milestones

| M | Deliverable |
|---|---|
| M0 | Repo, CI, container build, CT in `lxc.tf`, proxy host, Entra app registration, hello world behind login |
| M1 | Flyway schema, Entra user provisioning, owner-scoped CRUD, event log, main page, quick add, hierarchy rules |
| M2 | History page, closed-today boundary handling incl. midnight rollover |
| M3 | Tier 1 analytics + weekly review page |
| M4 | Tier 2 analytics |
| M5 | Tier 3 analytics |
| M6 | Backup job, **restore drill**, Uptime Kuma check confirmed |

## Explicitly out of scope

Reminders and notifications, attachments, time tracking, native mobile app,
recurring tasks (v2, schema migration), and any cross-user sharing, assignment
or visibility - the instance is shared, the data is not.
