-- Task tracker initial schema.
-- Design record: patobytes-context infra/self-hosted/task-tracker.md
--
-- Two things here are load-bearing for the analytics and cannot be added later:
--   * 'cancelled' is a status distinct from 'done', so abandoned work never
--     inflates throughput.
--   * task_event is append-only, and is the only source for reopen rate and
--     priority churn.

create type task_context    as enum ('work', 'personal');
create type task_status     as enum ('open', 'done', 'cancelled');
create type task_event_type as enum (
  'created', 'closed', 'reopened', 'cancelled',
  'priority_changed', 'reparented', 'edited'
);

-- Keyed on the Entra 'oid' claim rather than email or UPN: addresses change,
-- object ids do not, and a changed key would orphan every task the person owns.
create table app_user (
  id           uuid primary key,
  entra_oid    text not null unique,
  email        text not null,
  display_name text not null,
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz
);

create table task (
  id          uuid primary key,
  owner_id    uuid not null references app_user (id),
  parent_id   uuid,
  title       text not null check (length(btrim(title)) > 0),
  description text,
  priority    smallint not null default 2 check (priority between 0 and 3),
  context     task_context not null,
  status      task_status not null default 'open',
  tags        text[] not null default '{}',
  created_at  timestamptz not null default now(),
  closed_at   timestamptz,
  due_at      timestamptz,
  updated_at  timestamptz not null default now(),

  -- The reports are only trustworthy if the storage layer refuses incoherent
  -- rows, so this is a constraint rather than service-layer logic.
  constraint closed_at_matches_status check (
    (status = 'open' and closed_at is null) or
    (status <> 'open' and closed_at is not null)
  ),
  constraint no_self_parent check (parent_id is null or parent_id <> id),

  -- A parent must belong to the same owner. The unique constraint looks
  -- redundant against the primary key; it exists so the composite foreign key
  -- below is expressible, which makes "child of someone else's task"
  -- unrepresentable rather than merely tested for.
  constraint task_id_owner_key unique (id, owner_id),
  constraint task_parent_same_owner
    foreign key (parent_id, owner_id) references task (id, owner_id)
);

create index task_open_idx   on task (owner_id, priority, created_at) where status = 'open';
create index task_closed_idx on task (owner_id, closed_at desc)       where status <> 'open';
create index task_parent_idx on task (parent_id);
create index task_tags_idx   on task using gin (tags);

create table task_event (
  id         bigserial primary key,
  task_id    uuid not null references task (id) on delete cascade,
  at         timestamptz not null default now(),
  type       task_event_type not null,
  from_value text,
  to_value   text
);

create index task_event_task_idx on task_event (task_id, at);
create index task_event_type_idx on task_event (type, at);
