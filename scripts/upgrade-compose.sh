#!/usr/bin/env bash
# Upgrade a Docker Compose installation of Subnetory without losing data.
#
# Steps: pre-flight checks -> verified database backup -> (optional) switch to
# a release tag -> build or pull the new image -> restart ONLY the app service
# -> wait until it is healthy. Flyway applies new schema migrations
# automatically at startup. Nothing is ever deleted: volumes and secrets are
# never touched and "docker compose down -v" is never used.
#
# See backend/docs/UPGRADE.md for the full procedure and the rollback rules.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
backend_dir="$(cd "$script_dir/../backend" && pwd)"

usage() {
    cat <<'USAGE'
Usage: scripts/upgrade-compose.sh [options]

Options:
  -f, --compose-file FILE   Compose file to use (repeatable, like docker compose -f).
                            Default: backend/docker-compose.yml.
                            Examples: -f docker-compose.prod.yml (external database),
                            -f docker-compose.yml -f docker-compose.https.yml.
                            Use the SAME list (and order) as your installation: a missing
                            overlay silently changes the app configuration. The script
                            warns when the list differs from the running container's.
      --ref TAG             Check out this git tag/branch before building
                            (git fetch + checkout; the working tree must be clean).
      --pull                Pull the image instead of building it from sources
                            (for compose files that reference a published image).
      --backup-dir DIR      Where to write the pre-upgrade dump
                            (default: backend/upgrade-backups).
      --skip-backup         Do not take a dump. Only when you have a fresh, verified
                            backup of your own (mandatory for an external database
                            unless pg_dump is installed on this host and reachable).
      --timeout SECONDS     How long to wait for the app to become healthy (default 240).
      --allow-file-set-change
                            Accept a -f list that differs from the one the running app
                            container was created with (required together with --yes).
      --dry-run             Print what would be done; change nothing.
  -y, --yes                 Do not ask for confirmation.
  -h, --help                Show this help.

The script never pushes, deletes volumes, or touches secrets/ and .env.
USAGE
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }

compose_files=()
ref=""
do_pull=0
backup_dir=""
skip_backup=0
timeout_s=240
dry_run=0
assume_yes=0
allow_file_set_change=0

while (($# > 0)); do
    case "$1" in
        -f|--compose-file) (($# >= 2)) || die "$1 needs a value"; compose_files+=("$2"); shift ;;
        --ref) (($# >= 2)) || die "$1 needs a value"; ref="$2"; shift ;;
        --pull) do_pull=1 ;;
        --backup-dir) (($# >= 2)) || die "$1 needs a value"; backup_dir="$2"; shift ;;
        --skip-backup) skip_backup=1 ;;
        --timeout) (($# >= 2)) || die "$1 needs a value"; timeout_s="$2"; shift ;;
        --allow-file-set-change) allow_file_set_change=1 ;;
        --dry-run) dry_run=1 ;;
        -y|--yes) assume_yes=1 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "Unknown option: $1" ;;
    esac
    shift
done

[[ "$timeout_s" =~ ^[0-9]+$ ]] || die "--timeout must be a number of seconds"
[[ -z "$ref" || "$ref" =~ ^[A-Za-z0-9._/-]+$ ]] || die "Invalid --ref value"
((${#compose_files[@]})) || compose_files=(docker-compose.yml)
[[ -n "$backup_dir" ]] || backup_dir="$backend_dir/upgrade-backups"

cd "$backend_dir"

dc_args=()
for f in "${compose_files[@]}"; do
    [[ -f "$f" ]] || die "Compose file not found in backend/: $f"
    dc_args+=(-f "$f")
done
dc() { docker compose "${dc_args[@]}" "$@"; }

run() {
    if ((dry_run)); then
        printf '[dry-run] %s\n' "$*"
    else
        "$@"
    fi
}

# ---------------------------------------------------------------- pre-flight
step "Pre-flight checks"
command -v docker >/dev/null 2>&1 || die "docker is not installed or not in PATH."
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 ('docker compose') is required."
[[ -d secrets ]] || die "backend/secrets/ is missing. Run scripts/init-compose.sh first (first install) - an upgrade never creates secrets."
dc config -q || die "The Compose configuration is invalid (see above). Nothing was changed."

services="$(dc config --services)"
grep -qx app <<<"$services" || die "No 'app' service in the selected compose files."
has_db=0
grep -qx db <<<"$services" && has_db=1

info "Compose files : ${compose_files[*]}"
info "Embedded DB   : $([[ $has_db -eq 1 ]] && echo yes || echo 'no (external PostgreSQL)')"
info "Mode          : $([[ $do_pull -eq 1 ]] && echo 'pull image' || echo 'build from sources')"
[[ -z "$ref" ]] || info "Target ref    : $ref"

if [[ -n "$ref" ]]; then
    command -v git >/dev/null 2>&1 || die "git is required for --ref."
    git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "--ref needs a git checkout of Subnetory."
    [[ -z "$(git status --porcelain --untracked-files=no)" ]] || die "The git working tree has local changes; commit or stash them first."
fi

current_cid="$(dc ps -q app 2>/dev/null || true)"
prev_image=""
if [[ -n "$current_cid" ]]; then
    prev_image="$(docker inspect -f '{{.Image}}' "$current_cid" 2>/dev/null || true)"
    info "Running image : ${prev_image:-unknown}"
else
    warn "The app container is not running (first start after install, or stopped)."
fi

# A different -f list than the one the running containers were created with
# silently changes the app configuration (for example a missing
# docker-compose.https.yml drops the trusted-proxy settings: redirects in
# http://, every client seen as the proxy IP, failing logins and logouts).
file_set_changed=0
if [[ -n "$current_cid" ]]; then
    running_files="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$current_cid" 2>/dev/null || true)"
    project_name="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$current_cid" 2>/dev/null || true)"
    if [[ -n "$running_files" ]]; then
        running_names="$(tr ',' '\n' <<<"$running_files" | sed -e 's#.*/##' -e '/^$/d')"
        running_sorted="$(sort <<<"$running_names" | tr '\n' ' ')"
        selected_sorted="$(printf '%s\n' "${compose_files[@]}" | sed 's#.*/##' | sort | tr '\n' ' ')"
        if [[ "$running_sorted" != "$selected_sorted" ]]; then
            file_set_changed=1
            suggested=""
            while IFS= read -r name; do suggested+="-f $name "; done <<<"$running_names"
            warn "The selected compose files differ from the ones the running app container was created with."
            warn "  running : $(tr '\n' ' ' <<<"$running_names")"
            warn "  selected: ${compose_files[*]}"
            warn "Recreating app with another file set changes its configuration. If this is not intended, cancel and re-run with: $suggested"
        fi
    fi
    if [[ -n "$project_name" ]]; then
        outside="$(docker ps -a --filter "label=com.docker.compose.project=$project_name" \
            --format '{{.Label "com.docker.compose.service"}}' 2>/dev/null | sort -u \
            | grep -vxF -f <(printf '%s\n' "$services") || true)"
        if [[ -n "$outside" ]]; then
            warn "Services of this project are not covered by the selected files: $(tr '\n' ' ' <<<"$outside")"
            warn "You probably forgot an overlay (for example -f docker-compose.https.yml). Check the file list you normally use."
        fi
    fi
fi
if ((file_set_changed && assume_yes && !allow_file_set_change && !dry_run)); then
    die "The compose file list changed and --yes was given. Re-run without --yes to confirm interactively, or add --allow-file-set-change if the change is intended. Nothing was changed."
fi

if ((has_db)) && [[ -z "$(dc ps -q db 2>/dev/null || true)" ]]; then
    die "The db service is not running. Start it first: docker compose ${dc_args[*]} up -d db"
fi

if ((!dry_run && !assume_yes)); then
    printf '\nProceed with the upgrade? [y/N] '
    read -r answer </dev/tty || answer=""
    [[ "$answer" =~ ^[Yy]$ ]] || die "Cancelled."
fi

# -------------------------------------------------------------------- backup
dump_file=""
if ((skip_backup)); then
    warn "Backup skipped on request. Make sure you have a verified backup."
elif ((has_db)); then
    step "Backing up the database"
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    dump_file="$backup_dir/subnetory-pre-upgrade-$stamp.dump"
    if ((dry_run)); then
        info "[dry-run] pg_dump -Fc inside the db container -> $dump_file, then pg_restore --list to verify"
    else
        mkdir -p "$backup_dir"
        chmod 700 "$backup_dir"
        # shellcheck disable=SC2016  # variables must expand inside the container
        (umask 077; dc exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$dump_file") \
            || { rm -f "$dump_file"; die "pg_dump failed. Nothing was changed."; }
        [[ -s "$dump_file" ]] || { rm -f "$dump_file"; die "The dump is empty. Nothing was changed."; }
        dc exec -T db pg_restore --list <"$dump_file" >/dev/null \
            || die "The dump cannot be read back by pg_restore ($dump_file). Nothing was changed."
        info "Backup verified: $dump_file ($(wc -c <"$dump_file") bytes)"
    fi
else
    die "External PostgreSQL: this script cannot take the backup for you. Take and verify one (pg_dump -Fc + pg_restore --list), then re-run with --skip-backup."
fi

# ------------------------------------------------------------ new code/image
if [[ -n "$ref" ]]; then
    step "Checking out $ref"
    run git fetch --tags --prune origin
    run git checkout "$ref"
fi

if ((do_pull)); then
    step "Pulling the new image"
    run docker compose "${dc_args[@]}" pull app
else
    step "Building the new image"
    run docker compose "${dc_args[@]}" build --pull app
fi

# ------------------------------------------------------------------- restart
step "Restarting the application (database and volumes are not touched)"
run docker compose "${dc_args[@]}" up -d --no-deps app

if ((dry_run)); then
    info "[dry-run] would wait up to ${timeout_s}s for the app to become healthy"
    exit 0
fi

step "Waiting for the application to become healthy"
cid="$(dc ps -q app)"
[[ -n "$cid" ]] || die "The app container did not start. Check: docker compose ${dc_args[*]} logs app"
deadline=$((SECONDS + timeout_s))
status=""
while ((SECONDS < deadline)); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
    case "$status" in
        healthy|running) break ;;
        unhealthy|exited|dead) break ;;
    esac
    sleep 3
done

if [[ "$status" != healthy && "$status" != running ]]; then
    dc logs --tail 80 app || true
    cat >&2 <<MSG

The application did not become healthy (status: $status).
Do NOT run "down -v". Read the logs above first.
Rollback rules (see backend/docs/UPGRADE.md):
  - If the logs show Flyway applied a migration, restoring the previous image
    alone is NOT safe: restore the dump${dump_file:+ ($dump_file)} into an empty database first.
  - If no migration ran, redeploy the previous version and start it again.
Previous image id: ${prev_image:-unknown}
MSG
    exit 1
fi

step "Upgrade finished"
info "Application status: $status"
info "Recent log lines:"
dc logs --tail 15 app || true
[[ -z "$dump_file" ]] || info "Pre-upgrade backup kept at: $dump_file"
info "Check the application in the browser, then keep the backup for a few days."
