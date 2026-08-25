# Deployment

Push to `main` -> GitHub Actions builds and pushes to GHCR -> CT 217 pulls it
within five minutes. No manual step.

## Why pull and not push

The obvious design is for CI to deploy: Actions finishes, then reaches into the
lab and restarts the container. It was rejected.

A GitHub-hosted runner cannot reach `192.168.99.0/24`, so it would have to join
the tailnet. Then, because `standards/infra-access-baseline.md` rule 2 forbids
sshd in LXC containers, it would have to SSH to **pve3** and use `pct exec` -
meaning an SSH key for root on the hypervisor, stored as a GitHub secret,
usable by any workflow in the repository. For a personal task tracker that is a
wildly disproportionate blast radius: compromise of a CI secret becomes
compromise of the cluster.

Pulling inverts it. CT 217 reaches out to GHCR, which is public and needs no
credential. Nothing reaches in, no key exists to steal, and the access baseline
is untouched. The cost is up to five minutes of latency, which nobody notices.

## What the timer does

`scripts/deploy.sh`, every five minutes:

1. `git pull --ff-only` so `compose.yaml` and these scripts track the image
2. `docker compose pull`
3. `docker compose up -d` - a no-op unless the image digest actually moved
4. If the running image changed: wait for `/actuator/health/readiness`, prune
   the old image, log the deploy
5. If it never becomes ready: dump the last 40 log lines and **exit non-zero**,
   so `systemctl status` shows a failed unit rather than a green timer hiding a
   broken app

It stays silent when nothing changed, so the journal is a record of deploys
rather than a record of the timer having fired.

## Install

From pve3, once:

```bash
pct exec 217 -- bash -c 'cd /opt/tasks && git pull && chmod +x scripts/deploy.sh scripts/backup.sh'
```

```bash
pct exec 217 -- bash -c 'cp /opt/tasks/scripts/tasks-deploy.* /etc/systemd/system/ && systemctl daemon-reload'
```

```bash
pct exec 217 -- systemctl enable --now tasks-deploy.timer
```

Prove it works rather than waiting five minutes to find out:

```bash
pct exec 217 -- systemctl start tasks-deploy.service
```

```bash
pct exec 217 -- journalctl -u tasks-deploy.service --no-pager -n 40
```

## Day to day

Watch what it has done:

```bash
pct exec 217 -- journalctl -u tasks-deploy.service --since today --no-pager
```

Deploy immediately instead of waiting for the timer:

```bash
pct exec 217 -- systemctl start tasks-deploy.service
```

Stop automatic deploys - during an incident, or while debugging:

```bash
pct exec 217 -- systemctl disable --now tasks-deploy.timer
```

Roll back to a specific commit's image. Set `image:` in `compose.yaml` to the
SHA tag that CI published, then `docker compose up -d`. **Disable the timer
first**, or it will pull `latest` back over the rollback within five minutes.

## What this deliberately does not do

**It deploys whatever is tagged `latest`.** CI only publishes that from `main`
and only after the build and tests pass, so a broken commit does not reach the
container - but a commit that compiles and is wrong will deploy itself. There is
no approval gate. That is the right trade for a single-user tool and the wrong
one for anything with customers.

**It does not run migrations separately.** Flyway applies them on boot, inside
the same container start the health check is waiting on. A migration that fails
leaves the app unhealthy and the unit failed, which is the correct outcome - but
the old container is already gone by then. There is no automatic rollback;
`docs/backup-and-restore.md` is the recovery path.
