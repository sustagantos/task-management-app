-- Replace the three Postgres native enum types with text plus check
-- constraints.
--
-- The native enums were the right call on paper and the wrong one in practice.
-- Hibernate maps a named enum by the Java constant's name(), so labels and
-- constants must agree on case, and adding a value later needs ALTER TYPE ...
-- ADD VALUE, which carries transaction restrictions that make it awkward inside
-- a migration. A check constraint enforces exactly the same set of values,
-- maps to @Enumerated(EnumType.STRING) with no ceremony, and is altered like
-- any other constraint.
--
-- Values move to upper case to match the Java constants. Done now because no
-- task rows exist yet; after real data this is a rewrite of every row.

-- Drop what depends on the columns first. The partial indexes carry a
-- status = 'open' predicate that would be wrong once values are upper case.
drop index task_open_idx;
drop index task_closed_idx;
alter table task drop constraint closed_at_matches_status;
alter table task alter column status drop default;

alter table task alter column context type text using upper(context::text);
alter table task alter column status  type text using upper(status::text);
alter table task_event alter column type type text using upper(type::text);

alter table task alter column status set default 'OPEN';

drop type task_context;
drop type task_status;
drop type task_event_type;

alter table task add constraint task_context_valid
  check (context in ('WORK', 'PERSONAL'));

alter table task add constraint task_status_valid
  check (status in ('OPEN', 'DONE', 'CANCELLED'));

-- Unchanged in intent from V1: the reports are only trustworthy if the storage
-- layer refuses a row that is closed without a timestamp, or open with one.
alter table task add constraint closed_at_matches_status check (
  (status =  'OPEN' and closed_at is null) or
  (status <> 'OPEN' and closed_at is not null)
);

alter table task_event add constraint task_event_type_valid check (
  type in ('CREATED', 'CLOSED', 'REOPENED', 'CANCELLED',
           'PRIORITY_CHANGED', 'REPARENTED', 'EDITED')
);

create index task_open_idx   on task (owner_id, priority, created_at) where status =  'OPEN';
create index task_closed_idx on task (owner_id, closed_at desc)       where status <> 'OPEN';
