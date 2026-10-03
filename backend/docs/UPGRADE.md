# Upgrading Subnetory (Docker Compose)

This procedure upgrades an existing Docker Compose installation **without losing data**. Your data lives in PostgreSQL (the `pgdata` volume, or your external server) and in `backend/secrets/`; an upgrade replaces only the application image.

Commands below assume you run them from the `backend/` directory (as root or with `sudo` if that directory is root-only) and use a shell variable for your compose files:

```sh
cd /opt/subnetory/app/backend          # adapt to your installation
COMPOSE="-f docker-compose.yml -f docker-compose.image.yml -f docker-compose.https.yml"   # adapt, see below
```

## What the script does

`scripts/upgrade-compose.sh` runs these steps and stops at the first problem:

1. Checks Docker Compose, the `secrets/` directory and the configuration, and compares the `-f` file list you gave with the one the running `app` container was created with (see [Always use the same compose files](#always-use-the-same-compose-files)).
2. Takes a `pg_dump -Fc` backup and verifies it with `pg_restore --list` (embedded database).
3. Optionally checks out a release tag (`--ref`).
4. Builds the new image from sources (default) or pulls it (`--pull`).
5. Restarts **only** the `app` service (`up -d --no-deps app`). Flyway applies any new schema migration at startup.
6. Waits until the container is healthy and prints the recent logs.

It never runs `down -v`, never touches volumes, `secrets/` or `.env`, and never pushes anything.

## Always use the same compose files

Pass to the script **exactly the same `-f` list, in the same order,** as the one your installation was started with (base file, image overlay, HTTPS overlay, certificate overlay, ...). `app` is recreated on every upgrade; a missing overlay silently changes its configuration. A typical symptom is a missing `docker-compose.https.yml` behind Caddy: the application no longer trusts the proxy, so it redirects to `http://`, every client appears with the proxy's IP in the audit log, and sign-in or sign-out sometimes ends on a 403 page after several clicks.

Find the list used by the running containers:

```sh
docker inspect <project>-app-1 --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
docker inspect <project>-caddy-1 --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
```

(`<project>` is the Compose project name, `backend` by default: the container is then `backend-app-1`.) If the two lists differ (for example Caddy was created with an extra certificate overlay), use the **longest** one for `app` too, then check that the trusted-proxy settings are present:

```sh
docker inspect <project>-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep TRUSTED
```

You should see `SUBNETORY_SECURITY_TRUSTED_PROXY=true` and `SUBNETORY_SECURITY_TRUSTED_PROXY_CIDRS=...` when a reverse proxy is in front. The script warns when the selected files differ from the running `app` container's, and when other services of the same Compose project (for example `caddy`) are not covered. With `--yes`, a changed file list is refused unless you add `--allow-file-set-change`.

## Before you start

- Read the [CHANGELOG](../../CHANGELOG.md) entries between your version and the target one. Some migrations stop on purpose when existing data needs a human decision (for example V22 refuses a VLAN 0).
- Make sure `backend/secrets/` and your non-secret `.env` are backed up outside the repository.
- Plan a short maintenance window: the application restarts (about one minute).
- Record the current state, so that you can come back to it:

```sh
docker compose $COMPOSE ps
docker inspect <project>-app-1 --format '{{.Config.Image}}'
cp docker-compose.image.yml docker-compose.image.yml.bak-<current-version>   # if you use an image overlay
```

## Standard upgrade (build from sources)

```sh
git fetch --tags
scripts/upgrade-compose.sh $COMPOSE --ref v0.9.0
```

Omit `--ref` if you already updated the sources yourself (for example by extracting a new archive). Use `--dry-run` first to see every command without running it.

## Upgrade with a published image

When your Compose files reference a published image (for example `ghcr.io/subnetory/subnetory:v0.9.0`), pull it, note its digest, and edit the `image:` line of your overlay (keep the tag and `@sha256:` digest in sync if you pin one):

```sh
docker pull ghcr.io/subnetory/subnetory:v0.9.0
docker image inspect ghcr.io/subnetory/subnetory:v0.9.0 --format '{{index .RepoDigests 0}}'
# edit the image: line of docker-compose.image.yml with that digest, then:
scripts/upgrade-compose.sh $COMPOSE --pull --dry-run
scripts/upgrade-compose.sh $COMPOSE --pull -y
```

(Adapt the file list to your installation; see the previous section. If the script is not executable by your user, run it with `sudo bash -c 'cd /opt/subnetory/app/backend && /opt/subnetory/app/scripts/upgrade-compose.sh ...'`.)

## External PostgreSQL

The script cannot dump a server it does not manage. Take a verified backup yourself, then pass `--skip-backup`:

```sh
pg_dump -h DBHOST -U subnetory -Fc subnetory > subnetory-pre-upgrade.dump
pg_restore --list subnetory-pre-upgrade.dump > /dev/null && echo "backup OK"
scripts/upgrade-compose.sh -f docker-compose.prod.yml --skip-backup
```

## Options

| Option | Purpose |
| --- | --- |
| `-f, --compose-file FILE` | Compose file(s), repeatable (default `docker-compose.yml`). Use the same list as your installation, including overlays such as `docker-compose.https.yml`. |
| `--allow-file-set-change` | Accept a `-f` list that differs from the running `app` container's (required together with `--yes`). |
| `--ref TAG` | `git fetch` and check out a tag or branch (clean working tree required). |
| `--pull` | Pull instead of build. |
| `--backup-dir DIR` | Dump location (default `backend/upgrade-backups/`, git-ignored, mode 700). |
| `--skip-backup` | Required for an external database; otherwise only with your own verified backup. |
| `--timeout SECONDS` | Health wait (default 240). |
| `--dry-run` | Print the plan only. |
| `-y, --yes` | No confirmation prompt. |

## After the upgrade: verify

```sh
docker compose $COMPOSE ps                                   # app healthy; db and caddy still "Up" for a long time
docker inspect <project>-app-1 --format '{{.Image}}'          # the digest you expect
docker compose $COMPOSE logs --tail 100 app                  # Spring Boot start, Flyway "up to date" or applied migrations
docker inspect <project>-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep TRUSTED   # behind a proxy
```

Then, in a private browser window (one tab): sign in once with a non-admin account, sign out, sign in again, and check that Administration → Audit log shows the real client IP. Open the dashboard and compare the counters with what you expect. Keep the pre-upgrade dump for a few days, and copy it off the host.

## When something goes wrong

First rule: **never run `docker compose down -v`** (it deletes the database volume). Everything below keeps your data.

### The script prints a WARNING

The script ends every warning with a `WHAT TO DO` line and, when the prompt follows a warning, recommends answering `N`.

| Warning | Meaning | What to do |
|---|---|---|
| `The selected compose files differ from the ones the running app container was created with` | Your `-f` list is not the one used at install (an overlay is missing or extra). | Answer `N`. Re-run the exact command printed under `WHAT TO DO` (it contains the running file list). Use `--allow-file-set-change` only if you really want to change the file set. |
| `Services of this project are not covered by the selected files` | A running service (for example `caddy`) comes from an overlay you did not pass. | Answer `N`. Re-run with the full list of files you normally use. |
| `The app container is not running` | Stopped or first start. | Safe to continue (`y`) if you expect it. |
| `Backup skipped on request` | You used `--skip-backup`. | Make sure you have a verified backup of your own. |

With `--yes` the script cannot ask: a changed file list is refused (nothing is changed) unless `--allow-file-set-change` is also given.

### The script stops before changing anything

It prints the reason: invalid Compose configuration, missing `secrets/`, `db` not running, backup failed, changed file list with `--yes`. Nothing was changed; fix the cause and run it again.

### The app does not become healthy

```sh
docker compose $COMPOSE ps
docker compose $COMPOSE logs --tail 200 app
docker compose $COMPOSE logs app 2>&1 | grep -i -E "flyway|migrat|error|exception|caused by" | tail -40
```

- The log shows a **Flyway migration failure**: do not mark migrations as successful by hand; go to [Rollback with a migration](#rollback-when-a-migration-ran-or-you-are-unsure).
- No migration was applied (Flyway says the schema is up to date): go to [Rollback without a migration](#rollback-when-no-migration-ran).
- A configuration error (missing secret, wrong database URL): fix it, then `docker compose $COMPOSE up -d --no-deps app`.

### Sign-in needs several clicks, 403 page, redirects to `http://`, proxy IP in the audit log

The application does not trust the reverse proxy: the HTTPS overlay is missing from the `app` container.

```sh
docker inspect <project>-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep TRUSTED     # empty = problem
docker inspect <project>-caddy-1 --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
COMPOSE="<the full list printed above, as -f options>"
docker compose $COMPOSE up -d --no-deps app
docker inspect <project>-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep TRUSTED     # now present
```

Only `app` is recreated (about 25 seconds of downtime); `db`, `caddy` and the volumes are untouched. Then close old tabs and clear the site's cookies: after any restart, pages left open hold CSRF tokens that no longer exist and answer 403 on their first form submission. See also [HTTPS_REVERSE_PROXY.md](HTTPS_REVERSE_PROXY.md).

### 502 / connection refused through Caddy

```sh
docker compose $COMPOSE ps
docker compose $COMPOSE logs --tail 80 caddy
docker compose $COMPOSE up -d caddy        # starts Caddy once app is healthy
```

### Restart everything without losing data

Use this for odd behaviour (stale sessions, sign-out that does not complete). Data lives in volumes and is not affected; all web sessions are reset.

```sh
docker exec <project>-db-1 sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > /root/subnetory-before-restart.dump   # optional safety dump
docker restart <project>-db-1 && sleep 15
docker restart <project>-app-1
docker restart <project>-caddy-1
docker compose $COMPOSE ps
```

Wait for `db` to be healthy before restarting `app`. Then use a new private window.

### Rollback when no migration ran

Flyway says the schema was already up to date. Put the previous image back and run the script again:

```sh
cp docker-compose.image.yml.bak-<previous-version> docker-compose.image.yml
scripts/upgrade-compose.sh $COMPOSE --pull --skip-backup -y
```

### Rollback when a migration ran or you are unsure

Flyway migrations are forward-only. Switching the image back is **not safe**, because the old code would run on a newer schema. Restore the pre-upgrade dump into an **empty** database, then start the previous version.

```sh
# 1. check the dump can be read
docker compose $COMPOSE exec -T db pg_restore --list < upgrade-backups/subnetory-pre-upgrade-<timestamp>.dump > /dev/null && echo "dump OK"

# 2. stop the application only
docker compose $COMPOSE stop app

# 3. recreate an empty database and restore
docker compose $COMPOSE exec -T db sh -c 'dropdb -U "$POSTGRES_USER" --force --if-exists "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
docker compose $COMPOSE exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' < upgrade-backups/subnetory-pre-upgrade-<timestamp>.dump

# 4. put the previous image back (overlay backup or git checkout of the previous tag), then start it
cp docker-compose.image.yml.bak-<previous-version> docker-compose.image.yml
docker compose $COMPOSE up -d --no-deps app
docker compose $COMPOSE logs --tail 50 app
```

Data entered after the backup is lost with this rollback; that is why the maintenance window should be short.

### Collect diagnostics for support

```sh
docker compose $COMPOSE ps
docker inspect <project>-app-1 --format '{{.Config.Image}}'
docker inspect <project>-app-1 --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
docker compose $COMPOSE logs --since 30m app 2>&1 | tail -200
```

Do not paste secrets: `.env` values and files under `secrets/` stay on the host.

## Troubleshooting summary

- **The script stops at "Compose configuration is invalid"**: nothing was changed; fix the file or `.env` it points to.
- **The script warns that the compose files differ or that services are not covered**: you probably omitted an overlay. Cancel and re-run with the list it suggests.
- **The app stays unhealthy**: read `docker compose $COMPOSE logs app`; a Flyway message names the failing migration.
- **"refuses a non-empty schema"** at first start on a new external database: the database already contains tables but no Flyway history. See [EXTERNAL_POSTGRESQL.md](EXTERNAL_POSTGRESQL.md).
