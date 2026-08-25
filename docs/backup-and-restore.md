# Backup and restore

Two independent layers, because they fail differently.

| Layer | Protects against | Restores |
|---|---|---|
| **Nightly `pg_dump`** (this document) | dropped table, bad migration, corrupted row, accidental delete | the database, to any Postgres |
| **PVE `vzdump` of CT 217** | container destroyed, disk lost, node rebuilt | the whole container |

A container-level backup alone is not enough: it restores yesterday's container
wholesale, which is the wrong tool for "I deleted a month of tasks". A logical
dump alone is not enough either: it does not bring back a container. Run both.

## What the nightly job does

`scripts/backup.sh`, driven by `tasks-backup.timer` at 03:30 UTC (00:30 in
America/Sao_Paulo):

1. `pg_dump -Fc` into `/var/backups/tasks/tasks-<UTC timestamp>.dump`
2. **Verifies** the archive by reading its table of contents back with
   `pg_restore --list`, and deletes it if that fails
3. Rejects anything under 1 KB
4. Prunes archives older than 14 days

The verification step is the point. A truncated dump is a file of the right
name and the wrong contents, and without this check you find out at restore
time - which is the one moment you cannot afford to.

Custom format (`-Fc`) rather than plain SQL because it allows selective restore
and survives a Postgres major-version upgrade. The dump includes
`flyway_schema_history`, so a restored database is consistent with the migration
state the application expects.

## Installing it on CT 217

From pve3:

```bash
pct exec 217 -- chmod +x /opt/tasks/scripts/backup.sh
```

```bash
pct exec 217 -- cp /opt/tasks/scripts/tasks-backup.service /etc/systemd/system/tasks-backup.service
```

```bash
pct exec 217 -- cp /opt/tasks/scripts/tasks-backup.timer /etc/systemd/system/tasks-backup.timer
```

```bash
pct exec 217 -- systemctl daemon-reload
```

```bash
pct exec 217 -- systemctl enable --now tasks-backup.timer
```

Run it once immediately rather than waiting for 03:30, so a broken job is found
now:

```bash
pct exec 217 -- systemctl start tasks-backup.service
```

```bash
pct exec 217 -- journalctl -u tasks-backup.service --no-pager -n 30
```

```bash
pct exec 217 -- ls -l /var/backups/tasks/
```

Confirm the timer is scheduled:

```bash
pct exec 217 -- systemctl list-timers tasks-backup.timer --no-pager
```

## The restore drill

**An untested backup is not a backup.** This drill restores into a scratch
database, so it proves restorability without touching live data. Run it after
installing, and again whenever the schema changes materially.

```bash
pct enter 217
cd /opt/tasks
DUMP=$(ls -1t /var/backups/tasks/tasks-*.dump | head -1); echo "$DUMP"
docker compose exec -T db psql -U tasks -d postgres -c 'create database tasks_drill owner tasks;'
docker compose exec -T db pg_restore -U tasks -d tasks_drill --no-owner < "$DUMP"
docker compose exec -T db psql -U tasks -d tasks_drill -c 'select count(*) as tasks from task;'
docker compose exec -T db psql -U tasks -d tasks_drill -c 'select count(*) as events from task_event;'
docker compose exec -T db psql -U tasks -d tasks_drill -c 'select count(*) as users from app_user;'
docker compose exec -T db psql -U tasks -d postgres -c 'drop database tasks_drill;'
exit
```

The counts should match live. If `pg_restore` prints errors, the drill failed
even if it exited zero - read the output, do not just check the exit code.

## Restoring for real

Destructive. Only when live data is actually lost.

```bash
pct enter 217
cd /opt/tasks
docker compose stop app
```

Stopping the app first matters: Postgres refuses to drop a database with live
connections, and the connection pool holds several.

```bash
DUMP=/var/backups/tasks/tasks-<CHOOSE ONE>.dump
docker compose exec -T db psql -U tasks -d postgres -c 'drop database tasks;'
docker compose exec -T db psql -U tasks -d postgres -c 'create database tasks owner tasks;'
docker compose exec -T db pg_restore -U tasks -d tasks --no-owner < "$DUMP"
docker compose start app
```

Flyway will find `flyway_schema_history` at the restored version and apply only
migrations newer than it, which is the correct behaviour - do not clear that
table.

## Container-level backup

Separate from anything in this repo: add CT 217 to the PVE backup schedule
(Datacenter -> Backup) alongside the rest of the estate. `/var/backups/tasks`
lives on the container's root disk, so a container backup carries the recent
dumps with it.

Both layers still sit on the same node. Offsite is a wider question than this
service and is not solved here.
