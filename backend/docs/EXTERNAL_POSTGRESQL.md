# Using an external PostgreSQL server

Subnetory can use a PostgreSQL server you already run (a VM, a managed service, a cluster) instead of the embedded `db` container.

**You do not create any table.** Flyway creates and migrates the whole schema automatically the first time Subnetory starts. You only need a role, an empty database, and the right to create objects in it. `scripts/setup-external-postgres.sh` does exactly that, safely and repeatedly.

## Requirements

- PostgreSQL 14 or later (17 is what the embedded container uses).
- The Docker host can reach the server (network, firewall, `pg_hba.conf` entry for the application role, TLS if you require it).
- `psql` (the client) on the machine that runs the script, and an administrator account: a superuser, or a role with `CREATEROLE` and `CREATEDB`.

## 1. Prepare the server

From the repository root:

```sh
scripts/setup-external-postgres.sh --host db.example.org --admin-user postgres \
    --generate-password --timezone Europe/Paris --sslmode require
```

Use `--dry-run` to see the plan without connecting. The administrator password is read from `PGPASSWORD`, `~/.pgpass` or a prompt, never from the command line.

The script:

- validates names and the password (12 characters or more);
- creates the application role (`subnetory` by default) and a database owned by it, or reuses them;
- leaves the password of an existing role unchanged unless you pass `--update-password`;
- sets the database time zone (`--timezone`; when omitted the server default is kept), because the JDBC driver uses the server-announced zone to read timestamps;
- removes the default `PUBLIC` rights on the `public` schema and grants the application role what Flyway needs;
- refuses a database that already contains tables but has no Flyway history, to avoid mixing Subnetory with other data;
- connects as the application role and checks it can create objects (in a transaction that is rolled back).

It is safe to run again. It never drops anything.

With a non-superuser administrator and `log_statement = 'all'`, the role password appears once in the server log (a superuser avoids this). Rotate it afterwards if that matters to you.

## 2. Configure Subnetory

The script prints the exact lines. In `backend/.env` (non-secret):

```
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.org:5432/subnetory?stringtype=unspecified&sslmode=require
SPRING_DATASOURCE_USERNAME=subnetory
```

The password stays in `backend/secrets/postgres_password` (mounted as a secret by `docker-compose.prod.yml`). Generate the other secrets with `scripts/init-compose.sh` if it is a first install; it does not overwrite existing ones without `--force`.

## 3. Start

```sh
cd backend
docker build -t subnetory:latest .
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs --tail 100 app
```

The logs should show Flyway creating the schema (V1 and following), then the Spring Boot start. Upgrades later use `scripts/upgrade-compose.sh -f docker-compose.prod.yml` (see [UPGRADE.md](UPGRADE.md)).

## Backups

The external server is outside the Subnetory stack: you must back it up (for example `pg_dump -Fc`, verified with `pg_restore --list`, or your platform's snapshots). Subnetory's in-app backup feature stays available but is not a replacement.

## Migrating from the embedded database

1. Stop the app: `docker compose stop app`.
2. Dump: `docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > subnetory.dump`.
3. Run the setup script (step 1), **without** starting Subnetory.
4. Restore into the empty database: `pg_restore -h db.example.org -U subnetory -d subnetory --no-owner subnetory.dump` (the dump already contains the Flyway history).
5. Reuse the same `backend/secrets/` (the encryption keys protect data stored in the database), switch to `docker-compose.prod.yml`, and start.

Keep the embedded `pgdata` volume untouched until you have verified the new setup.

## Troubleshooting

- **Cannot connect as the application role**: add a `pg_hba.conf` entry for it and the Docker host address, reload the server, check the firewall and `sslmode`.
- **"permission denied for schema public"**: re-run the setup script; it re-applies the grants.
- **Timestamps off by one or two hours**: the database time zone differs from the application's. Re-run the script with `--timezone`.
- **Flyway refuses the schema**: the database has tables but no `flyway_schema_history`. Use an empty database, or restore a Subnetory dump.
