# Upgrading Subnetory (Docker Compose)

This procedure upgrades an existing Docker Compose installation **without losing data**. Your data lives in PostgreSQL (the `pgdata` volume, or your external server) and in `backend/secrets/`; an upgrade replaces only the application image.

## What the script does

`scripts/upgrade-compose.sh` runs these steps and stops at the first problem:

1. Checks Docker Compose, the `secrets/` directory and the configuration.
2. Takes a `pg_dump -Fc` backup and verifies it with `pg_restore --list` (embedded database).
3. Optionally checks out a release tag (`--ref`).
4. Builds the new image from sources (default) or pulls it (`--pull`).
5. Restarts **only** the `app` service (`up -d --no-deps app`). Flyway applies any new schema migration at startup.
6. Waits until the container is healthy and prints the recent logs.

It never runs `down -v`, never touches volumes, `secrets/` or `.env`, and never pushes anything.

## Before you start

- Read the [CHANGELOG](../../CHANGELOG.md) entries between your version and the target one. Some migrations stop on purpose when existing data needs a human decision (for example V22 refuses a VLAN 0).
- Make sure `backend/secrets/` and your non-secret `.env` are backed up outside the repository.
- Plan a short maintenance window: the application restarts (about one minute).

## Standard upgrade (build from sources)

```sh
git fetch --tags
scripts/upgrade-compose.sh --ref v0.8.13
```

Omit `--ref` if you already updated the sources yourself (for example by extracting a new archive). Use `--dry-run` first to see every command without running it.

## Upgrade with a published image

When your Compose files reference a published image (for example `ghcr.io/subnetory/subnetory:v0.8.13`), edit the `image:` line to the new tag (keep the `@sha256:` digest in sync if you pin one), then:

```sh
scripts/upgrade-compose.sh -f docker-compose.prod.yml --pull
```

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
| `-f, --compose-file FILE` | Compose file(s), repeatable (default `docker-compose.yml`). Add overlays such as `docker-compose.https.yml`. |
| `--ref TAG` | `git fetch` and check out a tag or branch (clean working tree required). |
| `--pull` | Pull instead of build. |
| `--backup-dir DIR` | Dump location (default `backend/upgrade-backups/`, git-ignored, mode 700). |
| `--skip-backup` | Required for an external database; otherwise only with your own verified backup. |
| `--timeout SECONDS` | Health wait (default 240). |
| `--dry-run` | Print the plan only. |
| `-y, --yes` | No confirmation prompt. |

## After the upgrade

- Open the application, sign in, and check the dashboard counters against what you expect.
- Look at the logs: `docker compose logs --tail 100 app` should show the Spring Boot start and Flyway either applying migrations or reporting the schema as up to date.
- Keep the pre-upgrade dump for a few days, and copy it off the host.

## Rollback

Flyway migrations are forward-only. Choose the rollback according to what happened:

- **No migration ran** (Flyway log says the schema is already up to date): redeploy the previous version (previous tag or image) with the same script, and start it.
- **A migration ran** or you are unsure: switching the image back is **not safe**, because the old code would run on a newer schema. Stop the app, restore the pre-upgrade dump into an **empty** database, then start the previous version:

```sh
docker compose stop app
docker compose exec -T db sh -c 'dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' < backend/upgrade-backups/subnetory-pre-upgrade-<timestamp>.dump
# switch the sources / image back to the previous version, then:
docker compose up -d app
```

Data entered after the backup is lost with this rollback; that is why the window should be short.

## Troubleshooting

- **The script stops at "Compose configuration is invalid"**: nothing was changed; fix the file or `.env` it points to.
- **The app stays unhealthy**: read `docker compose logs app`. A Flyway message names the failing migration. Do not mark migrations as successful by hand; fix the data or restore the backup.
- **"refuses a non-empty schema"** at first start on a new external database: the database already contains tables but no Flyway history. See [EXTERNAL_POSTGRESQL.md](EXTERNAL_POSTGRESQL.md).
