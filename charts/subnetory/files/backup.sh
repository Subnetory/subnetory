#!/bin/sh

set -eu
if ! set -o pipefail 2>/dev/null; then
  echo "Backup failed: the container shell must support pipefail." >&2
  exit 1
fi

umask 077

fail() {
  printf 'Backup failed: %s\n' "$1" >&2
  exit 1
}

log() {
  printf '%s\n' "$1"
}

escape_pgpass() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/:/\\:/g'
}

: "${BACKUP_LEVEL:?BACKUP_LEVEL is required}"
: "${RETENTION_DAYS:?RETENTION_DAYS is required}"
: "${BACKUP_ROOT:?BACKUP_ROOT is required}"
: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${POSTGRES_PASSWORD_FILE:?POSTGRES_PASSWORD_FILE is required}"

case "$BACKUP_LEVEL" in
  hourly|daily|monthly|quarterly) ;;
  *) fail "unsupported backup level" ;;
esac

case "$RETENTION_DAYS" in
  ''|*[!0-9]*) fail "retention must be a positive integer" ;;
esac
[ "$RETENTION_DAYS" -ge 1 ] || fail "retention must be at least one day"

case "$BACKUP_ROOT" in
  /*) ;;
  *) fail "backup root must be an absolute path" ;;
esac

[ -r "$POSTGRES_PASSWORD_FILE" ] || fail "password file is not readable"
[ -s "$POSTGRES_PASSWORD_FILE" ] || fail "password file is empty"
if LC_ALL=C grep -q '[[:cntrl:]]' "$POSTGRES_PASSWORD_FILE"; then
  fail "password file contains an unsupported control character"
fi

password="$(cat "$POSTGRES_PASSWORD_FILE")"
pgpass_file=""
partial_file=""
checksum_partial=""
published_file=""
backup_published=false

cleanup() {
  [ -z "$pgpass_file" ] || rm -f "$pgpass_file"
  [ -z "$partial_file" ] || rm -f "$partial_file"
  [ -z "$checksum_partial" ] || rm -f "$checksum_partial"
  if [ "$backup_published" != true ] && [ -n "$published_file" ]; then
    rm -f "$published_file" "${published_file}.sha256"
  fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

pgpass_file="$(mktemp /tmp/subnetory-pgpass.XXXXXX)"
chmod 0600 "$pgpass_file"
printf '%s:%s:%s:%s:%s\n' \
  "$(escape_pgpass "$PGHOST")" \
  "$(escape_pgpass "$PGPORT")" \
  "$(escape_pgpass "$PGDATABASE")" \
  "$(escape_pgpass "$PGUSER")" \
  "$(escape_pgpass "$password")" \
  > "$pgpass_file"
password=""
export PGPASSFILE="$pgpass_file"
export PGCONNECT_TIMEOUT=15
export PGAPPNAME=subnetory-kubernetes-backup

level_directory="${BACKUP_ROOT}/${BACKUP_LEVEL}"
mkdir -p "$level_directory"
chmod 0700 "$level_directory"

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
backup_name="subnetory-${BACKUP_LEVEL}-${timestamp}.dump.gz"
published_file="${level_directory}/${backup_name}"
partial_file="${published_file}.partial"
checksum_file="${published_file}.sha256"
checksum_partial="${checksum_file}.partial"

if [ -e "$published_file" ] || [ -e "$checksum_file" ]; then
  fail "a backup already exists for the current timestamp"
fi

log "Starting ${BACKUP_LEVEL} PostgreSQL backup."
if ! pg_dump \
    --host="$PGHOST" \
    --port="$PGPORT" \
    --username="$PGUSER" \
    --dbname="$PGDATABASE" \
    --format=custom \
    --compress=0 \
    --no-owner \
    --no-privileges \
    --no-password \
    | gzip -c > "$partial_file"; then
  fail "pg_dump or gzip failed"
fi

[ -s "$partial_file" ] || fail "compressed dump is empty"
gzip -t "$partial_file" || fail "gzip integrity check failed"
gzip -dc "$partial_file" | pg_restore --list >/dev/null \
  || fail "PostgreSQL archive catalogue check failed"

checksum="$(sha256sum "$partial_file" | awk '{print $1}')"
[ "${#checksum}" -eq 64 ] || fail "invalid SHA-256 length"
case "$checksum" in
  *[!0-9a-f]*) fail "invalid SHA-256 value" ;;
esac
printf '%s  %s\n' "$checksum" "$backup_name" > "$checksum_partial"

mv "$partial_file" "$published_file"
partial_file=""
mv "$checksum_partial" "$checksum_file"
checksum_partial=""

if ! (cd "$level_directory" && sha256sum -c "${backup_name}.sha256" >/dev/null); then
  fail "published SHA-256 verification failed"
fi
backup_published=true

if ! find "$level_directory" \
    -maxdepth 1 \
    -type f \
    -name "subnetory-${BACKUP_LEVEL}-*.dump.gz" \
    -mtime "+${RETENTION_DAYS}" \
    -print \
  | while IFS= read -r expired_dump; do
      expired_checksum="${expired_dump}.sha256"
      if [ ! -f "$expired_checksum" ]; then
        echo "Backup rotation failed: an expired dump has no checksum." >&2
        exit 1
      fi
      rm -f "$expired_dump" "$expired_checksum"
    done; then
  fail "backup rotation failed"
fi

log "Backup completed: ${backup_name}"
