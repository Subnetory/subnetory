#!/bin/sh

set -eu
if ! set -o pipefail 2>/dev/null; then
  echo "Restore drill failed: the container shell must support pipefail." >&2
  exit 1
fi

umask 077

fail() {
  printf 'Restore drill failed: %s\n' "$1" >&2
  exit 1
}

: "${BACKUP_LEVEL:?BACKUP_LEVEL is required}"
: "${BACKUP_FILE:?BACKUP_FILE is required}"
: "${WITNESS_ENABLED:?WITNESS_ENABLED is required}"
: "${WITNESS_ID:?WITNESS_ID is required}"
: "${WITNESS_EXPECTED_SHA256:?WITNESS_EXPECTED_SHA256 is required}"
: "${EXPECTED_FLYWAY_VERSION:?EXPECTED_FLYWAY_VERSION is required}"

case "$BACKUP_LEVEL" in
  hourly|daily|monthly|quarterly) ;;
  *) fail "unsupported backup level" ;;
esac

if ! printf '%s\n' "$BACKUP_FILE" | grep -Eq \
    '^subnetory-(hourly|daily|monthly|quarterly)-[0-9]{8}T[0-9]{6}Z\.dump\.gz$'; then
  fail "backup filename is not allowed"
fi
case "$BACKUP_FILE" in
  "subnetory-${BACKUP_LEVEL}-"*) ;;
  *) fail "backup filename does not match its level" ;;
esac

case "$WITNESS_ENABLED" in
  true|false) ;;
  *) fail "invalid witness mode" ;;
esac
case "$WITNESS_ID" in
  ''|*[!0-9]*) fail "invalid witness identifier" ;;
esac
case "$EXPECTED_FLYWAY_VERSION" in
  ''|*[!0-9]*) fail "invalid expected Flyway version" ;;
esac
if [ "$WITNESS_ENABLED" = true ]; then
  [ "$WITNESS_ID" -ge 1 ] || fail "witness identifier must be positive"
  if ! printf '%s\n' "$WITNESS_EXPECTED_SHA256" | grep -Eq \
      '^[0-9a-f]{64}$'; then
    fail "invalid witness expected SHA-256"
  fi
else
  [ "$WITNESS_ID" -eq 0 ] || fail "witness identifier must be zero when disabled"
  [ "$WITNESS_EXPECTED_SHA256" = disabled ] \
    || fail "witness SHA-256 must be disabled when the check is disabled"
fi

level_directory="/backups/${BACKUP_LEVEL}"
dump_path="${level_directory}/${BACKUP_FILE}"
checksum_path="${dump_path}.sha256"
[ -f "$dump_path" ] || fail "backup file does not exist"
[ -s "$dump_path" ] || fail "backup file is empty"
[ -f "$checksum_path" ] || fail "checksum file does not exist"
[ -s "$checksum_path" ] || fail "checksum file is empty"

checksum_lines="$(awk 'END {print NR}' "$checksum_path")"
checksum_fields="$(awk 'NR == 1 {print NF}' "$checksum_path")"
stored_hash="$(awk 'NR == 1 {print $1}' "$checksum_path")"
stored_name="$(awk 'NR == 1 {print $2}' "$checksum_path")"
[ "$checksum_lines" -eq 1 ] || fail "checksum file must contain exactly one line"
[ "$checksum_fields" -eq 2 ] || fail "checksum file has an invalid format"
[ "$stored_name" = "$BACKUP_FILE" ] || fail "checksum filename does not match"
[ "${#stored_hash}" -eq 64 ] || fail "checksum has an invalid length"
case "$stored_hash" in
  *[!0-9a-f]*) fail "checksum has an invalid value" ;;
esac

actual_hash="$(sha256sum "$dump_path" | awk '{print $1}')"
[ "$actual_hash" = "$stored_hash" ] || fail "SHA-256 verification failed"
gzip -t "$dump_path" || fail "gzip integrity check failed"
gzip -dc "$dump_path" | pg_restore --list >/dev/null \
  || fail "PostgreSQL archive catalogue check failed"

data_directory=/var/lib/postgresql/data/pgdata
socket_directory=/tmp/postgresql
server_started=false

cleanup() {
  if [ "$server_started" = true ]; then
    pg_ctl --pgdata="$data_directory" --mode=fast --wait stop >/dev/null 2>&1 \
      || true
  fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

mkdir -p "$data_directory" "$socket_directory"
chmod 0700 "$data_directory" "$socket_directory"
if ! initdb \
    --pgdata="$data_directory" \
    --username=postgres \
    --auth-local=trust \
    --auth-host=reject \
    --encoding=UTF8 \
    --locale=C \
    >/dev/null 2>&1; then
  fail "temporary PostgreSQL initialization failed"
fi

pg_ctl \
  --pgdata="$data_directory" \
  --options="-c listen_addresses='' -c unix_socket_directories=${socket_directory} -c unix_socket_permissions=0700 -c max_connections=10" \
  --timeout=60 \
  --wait \
  start \
  >/dev/null
server_started=true

export PGHOST="$socket_directory"
export PGUSER=postgres
createuser \
  --no-password \
  --login \
  --no-superuser \
  --no-createdb \
  --no-createrole \
  --no-replication \
  --no-bypassrls \
  subnetory_restore_user
createdb \
  --no-password \
  --maintenance-db=postgres \
  --owner=subnetory_restore_user \
  subnetory_restore
export PGUSER=subnetory_restore_user
export PGDATABASE=subnetory_restore

gzip -dc "$dump_path" | pg_restore \
  --exit-on-error \
  --no-owner \
  --no-privileges \
  --no-password \
  --dbname="$PGDATABASE" \
  >/dev/null

restore_role_flags="$(psql \
  --no-password \
  --tuples-only \
  --no-align \
  --set=ON_ERROR_STOP=1 \
  --command="SELECT rolsuper::text || ':' || rolcreatedb::text || ':' || rolcreaterole::text || ':' || rolreplication::text || ':' || rolbypassrls::text FROM pg_roles WHERE rolname = current_user;")"
[ "$restore_role_flags" = 'false:false:false:false:false' ] \
  || fail "restore role has forbidden privileges"

failed_migrations="$(psql \
  --no-password \
  --tuples-only \
  --no-align \
  --set=ON_ERROR_STOP=1 \
  --command='SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success;')"
[ "$failed_migrations" -eq 0 ] || fail "the restored schema contains a failed migration"

flyway_version="$(psql \
  --no-password \
  --tuples-only \
  --no-align \
  --set=ON_ERROR_STOP=1 \
  --command='SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;')"
[ "$flyway_version" = "$EXPECTED_FLYWAY_VERSION" ] \
  || fail "the restored schema is not at the expected Flyway version"

if [ "$WITNESS_ENABLED" = true ]; then
  witness_value="$(psql \
    --no-password \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --command="SELECT marker FROM public.subnetory_restore_probe WHERE id = ${WITNESS_ID}::integer;")"
  witness_hash="$(printf '%s' "$witness_value" | sha256sum | awk '{print $1}')"
  [ "$witness_hash" = "$WITNESS_EXPECTED_SHA256" ] \
    || fail "witness verification failed"
fi

printf 'Restore drill completed: isolated PostgreSQL, Flyway V%s and witness checks passed.\n' "$EXPECTED_FLYWAY_VERSION"
