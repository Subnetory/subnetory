#!/usr/bin/env bash
# Prepare an external PostgreSQL server for Subnetory.
#
# Creates (idempotently) the application role and the database, grants the
# minimum rights Subnetory needs, and checks that the application account can
# connect and create tables. It does NOT create any table: Flyway creates and
# migrates the whole schema automatically the first time Subnetory starts.
#
# See backend/docs/EXTERNAL_POSTGRESQL.md for the full procedure.
set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/setup-external-postgres.sh --host HOST [options]

Prepares an external PostgreSQL server (14 or later) for Subnetory.

Required:
  --host HOST                 PostgreSQL server name or IP address.

Options:
  --port PORT                 Server port (default: 5432).
  --admin-user USER           Account used to create the role and database;
                              superuser, or a role with CREATEROLE and
                              CREATEDB (default: postgres).
  --admin-db DB               Maintenance database to connect to first
                              (default: postgres).
  --db-name NAME              Database to create for Subnetory
                              (default: subnetory).
  --app-user NAME             Application role Subnetory connects as
                              (default: subnetory).
  --app-password-file FILE    File holding the application role password
                              (default: backend/secrets/postgres_password).
  --generate-password         Create the password file with a random password
                              if it does not exist yet.
  --update-password           Also set the password of an application role
                              that already exists (default: leave it alone,
                              so a running instance is never broken).
  --timezone TZ               Default time zone of the Subnetory database,
                              e.g. Europe/Paris. Affects this database only.
  --sslmode MODE              libpq sslmode for all connections: disable,
                              allow, prefer, require, verify-ca, verify-full
                              (default: prefer).
  --dry-run                   Print the plan and exit without connecting.
  -h, --help                  Show this help.

The administrator password is never taken from the command line. Provide it
with the PGPASSWORD environment variable, a ~/.pgpass file (PGPASSFILE), or
type it when prompted.
EOF
}

die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

info() { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/.." && pwd -P)"

host=""
port="5432"
admin_user="postgres"
admin_db="postgres"
db_name="subnetory"
app_user="subnetory"
password_file="$repo_root/backend/secrets/postgres_password"
generate_password=0
update_password=0
timezone=""
sslmode="prefer"
dry_run=0

while (($# > 0)); do
    case "$1" in
        --host) host="${2:?--host needs a value}"; shift ;;
        --port) port="${2:?--port needs a value}"; shift ;;
        --admin-user) admin_user="${2:?--admin-user needs a value}"; shift ;;
        --admin-db) admin_db="${2:?--admin-db needs a value}"; shift ;;
        --db-name) db_name="${2:?--db-name needs a value}"; shift ;;
        --app-user) app_user="${2:?--app-user needs a value}"; shift ;;
        --app-password-file) password_file="${2:?--app-password-file needs a value}"; shift ;;
        --generate-password) generate_password=1 ;;
        --update-password) update_password=1 ;;
        --timezone) timezone="${2:?--timezone needs a value}"; shift ;;
        --sslmode) sslmode="${2:?--sslmode needs a value}"; shift ;;
        --dry-run) dry_run=1 ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

[[ -n "$host" ]] || { usage >&2; die "--host is required."; }

ident_re='^[a-z_][a-z0-9_]{0,62}$'
[[ "$db_name" =~ $ident_re ]] || die "--db-name must match $ident_re (lowercase letters, digits, underscore)."
[[ "$app_user" =~ $ident_re ]] || die "--app-user must match $ident_re (lowercase letters, digits, underscore)."
[[ "$port" =~ ^[0-9]{1,5}$ ]] || die "--port must be a number."
[[ "$host" =~ ^[A-Za-z0-9._:-]+$ ]] || die "--host contains unexpected characters."
[[ "$admin_user" =~ ^[A-Za-z0-9_.@-]+$ ]] || die "--admin-user contains unexpected characters."
[[ "$admin_db" =~ ^[A-Za-z0-9_.-]+$ ]] || die "--admin-db contains unexpected characters."
case "$sslmode" in
    disable|allow|prefer|require|verify-ca|verify-full) ;;
    *) die "--sslmode must be one of: disable, allow, prefer, require, verify-ca, verify-full." ;;
esac
if [[ -n "$timezone" ]]; then
    [[ "$timezone" =~ ^[A-Za-z0-9_+/-]+$ ]] || die "--timezone contains unexpected characters."
fi

command -v psql >/dev/null 2>&1 || die "psql (PostgreSQL client) is required. Install the postgresql client package and retry."

# --- Application password -------------------------------------------------
if [[ ! -e "$password_file" ]]; then
    if ((generate_password)); then
        if ((dry_run)); then
            info "[dry-run] would generate a random password into $password_file"
        else
            umask 077
            mkdir -p "$(dirname -- "$password_file")"
            generated="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 || true)"
            ((${#generated} == 40)) || die "Could not generate a random password."
            printf '%s' "$generated" >"$password_file"
            unset generated
            info "Generated a new password file: $password_file (not displayed)."
        fi
    else
        die "Password file not found: $password_file. Create it, or add --generate-password."
    fi
fi

if ((dry_run)) && [[ ! -e "$password_file" ]]; then
    :
else
    [[ -r "$password_file" ]] || die "Password file is not readable: $password_file"
    pw_len="$(tr -d '\r\n' <"$password_file" | wc -c | tr -d ' ')"
    ((pw_len >= 12)) || die "The application password must be at least 12 characters (file: $password_file)."
    if LC_ALL=C grep -q '[^[:print:]]' < <(tr -d '\r\n' <"$password_file"); then
        die "The application password contains non-printable characters."
    fi
fi

jdbc_host="$host"
[[ "$host" == *:* ]] && jdbc_host="[$host]"
jdbc_url="jdbc:postgresql://${jdbc_host}:${port}/${db_name}?stringtype=unspecified"
[[ "$sslmode" != "prefer" ]] && jdbc_url="${jdbc_url}&sslmode=${sslmode}"

step "Plan"
info "Server            : $host:$port (sslmode=$sslmode)"
info "Administrator     : $admin_user (maintenance database: $admin_db)"
info "Application role  : $app_user (password from $password_file)"
info "Database          : $db_name (owner: $app_user)${timezone:+, time zone: $timezone}"
if ((dry_run)); then
    info ""
    info "[dry-run] Nothing was executed. Connection string that would be used by Subnetory:"
    info "  SPRING_DATASOURCE_URL=$jdbc_url"
    info "  SPRING_DATASOURCE_USERNAME=$app_user"
    exit 0
fi

# --- Connection settings --------------------------------------------------
export PGHOST="$host" PGPORT="$port" PGSSLMODE="$sslmode" PGCONNECT_TIMEOUT=10
export PGAPPNAME="subnetory-setup"
export SUBNETORY_PASSWORD_FILE="$password_file"

if [[ -z "${PGPASSWORD:-}" && -z "${PGPASSFILE:-}" && ! -f "${HOME:-/nonexistent}/.pgpass" ]]; then
    if [[ -t 0 ]]; then
        read -r -s -p "Password for $admin_user@$host: " PGPASSWORD
        printf '\n'
        export PGPASSWORD
    else
        warn "No administrator password available (PGPASSWORD, PGPASSFILE or ~/.pgpass); trying without."
    fi
fi

psql_admin() {
    PGUSER="$admin_user" PGDATABASE="${1:-$admin_db}" \
        psql -X -q -v ON_ERROR_STOP=1 -v "app_user=$app_user" -v "db_name=$db_name" \
        -v "update_pw=$( ((update_password)) && echo true || echo false )" \
        -v "tz=$timezone" -f -
}

# Reads SQL on stdin (variables such as :'db_name' are only interpolated there).
psql_admin_value() {
    PGUSER="$admin_user" PGDATABASE="${PSQL_DB:-$admin_db}" \
        psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -v "app_user=$app_user" \
        -v "db_name=$db_name" -v "tz=$timezone" -f -
}

# --- 1. Connectivity and capabilities ---------------------------------------
step "Checking the administrator connection"
if ! facts="$(psql_admin_value 2>&1 <<'SQL'
SELECT current_setting('server_version_num')::int,
       (SELECT rolsuper FROM pg_roles WHERE rolname = current_user),
       (SELECT rolcreaterole FROM pg_roles WHERE rolname = current_user),
       (SELECT rolcreatedb FROM pg_roles WHERE rolname = current_user);
SQL
)"; then
    printf '%s\n' "$facts" >&2
    die "Cannot connect as $admin_user to $host:$port/$admin_db. Check the host, port, credentials, the server's listen_addresses and pg_hba.conf, and any firewall."
fi
IFS='|' read -r server_version is_super can_createrole can_createdb <<<"$facts"

((server_version >= 140000)) || die "PostgreSQL 14 or later is required (server reports version number $server_version)."
info "Connected. Server version number: $server_version."

admin_is_super=0
if [[ "$is_super" == "t" ]]; then
    admin_is_super=1
elif [[ "$can_createrole" != "t" || "$can_createdb" != "t" ]]; then
    die "$admin_user must be a superuser, or have both CREATEROLE and CREATEDB, to prepare the database."
fi
if ((admin_is_super)); then
    info "$admin_user is a superuser."
else
    info "$admin_user is not a superuser (CREATEROLE and CREATEDB are present); membership in the new role will be granted to it."
    warn "Non-superuser administrator: the CREATE/ALTER ROLE statement carries the password. If the server logs all statements (log_statement = 'all'), it will appear once in the server log - rotate it afterwards if that matters."
fi

if [[ -n "$timezone" ]]; then
    tz_ok="$(psql_admin_value <<'SQL'
SELECT 1 FROM pg_timezone_names WHERE name = :'tz' LIMIT 1;
SQL
)"
    [[ "$tz_ok" == "1" ]] || die "Time zone '$timezone' is unknown to the server (see SELECT name FROM pg_timezone_names)."
fi

# --- 2. Role, database, rights -------------------------------------------
existing_owner="$(psql_admin_value <<'SQL'
SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_database WHERE datname = :'db_name';
SQL
)"
if [[ -n "$existing_owner" && "$existing_owner" != "$app_user" ]]; then
    die "Database $db_name already exists but is owned by $existing_owner, not $app_user. Pick another --db-name, or run: ALTER DATABASE $db_name OWNER TO $app_user;"
fi

step "Creating the role and the database if needed"
if ! psql_admin <<'SQL'
\set pw `tr -d '\r\n' < "$SUBNETORY_PASSWORD_FILE"`

-- Keep the password out of the server log when we are allowed to.
SELECT rolsuper AS is_super FROM pg_roles WHERE rolname = current_user \gset
\if :is_super
  SET log_statement = 'none';
  SET log_min_duration_statement = -1;
\endif

SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') AS role_exists \gset
\if :role_exists
  \echo Role :app_user already exists.
  \if :update_pw
    ALTER ROLE :"app_user" WITH LOGIN PASSWORD :'pw';
    \echo Password of role :app_user updated.
  \else
    \echo Its password was left unchanged (use --update-password to change it).
  \endif
\else
  CREATE ROLE :"app_user" WITH LOGIN PASSWORD :'pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT;
  \echo Role :app_user created.
\endif

-- Needed so that a non-superuser administrator may create a database owned by
-- the application role (managed PostgreSQL services, PostgreSQL 15 and later).
SELECT NOT rolsuper AS needs_membership FROM pg_roles WHERE rolname = current_user \gset
\if :needs_membership
  SELECT current_setting('server_version_num')::int >= 160000 AS pg16 \gset
  \if :pg16
    SELECT NOT pg_has_role(current_user, :'app_user', 'SET') AS must_grant \gset
    \if :must_grant
      GRANT :"app_user" TO CURRENT_USER WITH SET TRUE;
    \endif
  \else
    SELECT NOT pg_has_role(current_user, :'app_user', 'MEMBER') AS must_grant \gset
    \if :must_grant
      GRANT :"app_user" TO CURRENT_USER;
    \endif
  \endif
\endif

SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') AS db_exists \gset
\if :db_exists
  \echo Database :db_name already exists and is owned by :app_user.
\else
  SELECT datcollate AS coll, datctype AS ctype FROM pg_database WHERE datname = 'template1' \gset
  CREATE DATABASE :"db_name" OWNER :"app_user" ENCODING 'UTF8'
    LC_COLLATE :'coll' LC_CTYPE :'ctype' TEMPLATE template0;
  \echo Database :db_name created.
\endif

REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";
SQL
then
    die "Role/database preparation failed."
fi

if [[ -n "$timezone" ]]; then
    step "Setting the default time zone of $db_name to $timezone"
    psql_admin <<'SQL'
ALTER DATABASE :"db_name" SET timezone TO :'tz';
SQL
fi

step "Hardening the public schema of $db_name"
psql_admin "$db_name" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
SQL

# --- 3. Existing content check ---------------------------------------------
step "Checking that the database is empty or already managed by Subnetory"
content="$(PSQL_DB="$db_name" psql_admin_value <<'SQL'
SELECT (SELECT count(*) FROM pg_tables WHERE schemaname = 'public'),
       (SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history');
SQL
)"
IFS='|' read -r table_count history_count <<<"$content"
if ((table_count > 0 && history_count == 0)); then
    die "Database $db_name already contains $table_count table(s) but no Flyway history. Subnetory would refuse to start (it never adopts unknown tables). Use an empty database, or restore a Subnetory backup into it."
elif ((history_count > 0)); then
    info "The database already contains a Subnetory schema; Flyway will only apply pending migrations."
else
    info "The database is empty: Flyway will create the full schema on first start."
fi

# --- 4. Verification as the application account -------------------------------
step "Verifying the application account"
app_password="$(tr -d '\r\n' <"$password_file")"
if ! check="$(PGUSER="$app_user" PGPASSWORD="$app_password" PGDATABASE="$db_name" psql -X -q -At -F '|' -v ON_ERROR_STOP=1 <<'SQL' 2>&1
BEGIN;
CREATE TABLE _subnetory_setup_check (id integer);
DROP TABLE _subnetory_setup_check;
ROLLBACK;
SELECT current_user, current_database(), current_setting('TimeZone');
SQL
)"; then
    unset app_password
    printf '%s\n' "$check" >&2
    die "The application account $app_user cannot connect to $db_name or cannot create tables. If the error mentions pg_hba.conf, add a line such as: host $db_name $app_user <subnetory-host-ip>/32 scram-sha-256 and reload PostgreSQL."
fi
unset app_password
last_line="$(printf '%s\n' "$check" | tail -n 1)"
IFS='|' read -r who whichdb tzname <<<"$last_line"
info "OK: connected as '$who' to '$whichdb', can create tables (server time zone for this database: $tzname)."

step "Done"
cat <<EOF
The external database is ready. Subnetory creates its tables itself on first start.

Next steps (backend/ directory, application-only Compose file):

  1. Put these two (non-secret) lines in backend/.env :

       SPRING_DATASOURCE_URL=$jdbc_url
       SPRING_DATASOURCE_USERNAME=$app_user

  2. The password is read from: $password_file
     (mounted by docker-compose.prod.yml as the spring.datasource.password secret).
     Run scripts/init-compose.sh first if the other secrets do not exist yet.

  3. Start the application:

       docker build -t subnetory:latest .
       docker compose -f docker-compose.prod.yml up -d

Back up this PostgreSQL server yourself: it is outside the Subnetory stack.
EOF
