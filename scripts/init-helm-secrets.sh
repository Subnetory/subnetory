#!/usr/bin/env bash
set -euo pipefail

namespace="subnetory"
runtime_secret_name="subnetory-runtime-secrets"
bootstrap_secret_name="subnetory-bootstrap-secrets"
kubectl_command="kubectl"
force="false"

usage() {
  cat <<'USAGE'
Usage: init-helm-secrets.sh [options]

Options:
  --namespace NAME          Kubernetes namespace (default: subnetory)
  --runtime-secret NAME     Runtime Secret name
  --bootstrap-secret NAME   Bootstrap Secret name
  --kubectl PATH            kubectl command or path
  --force                   Replace existing Secrets intentionally
  --help                    Show this help
USAGE
}

while (($# > 0)); do
  case "$1" in
    --namespace)
      namespace="$2"
      shift 2
      ;;
    --runtime-secret)
      runtime_secret_name="$2"
      shift 2
      ;;
    --bootstrap-secret)
      bootstrap_secret_name="$2"
      shift 2
      ;;
    --kubectl)
      kubectl_command="$2"
      shift 2
      ;;
    --force)
      force="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

command -v "$kubectl_command" >/dev/null 2>&1 || {
  echo "kubectl not found: $kubectl_command" >&2
  exit 1
}

command -v openssl >/dev/null 2>&1 || {
  echo "openssl is required for cryptographic secret generation." >&2
  exit 1
}

"$kubectl_command" get namespace "$namespace" --output name >/dev/null

secret_exists() {
  local name="$1"
  [[ -n $(
    "$kubectl_command" get secret "$name" \
      --namespace "$namespace" \
      --ignore-not-found \
      --output name
  ) ]]
}

runtime_exists="false"
bootstrap_exists="false"
secret_exists "$runtime_secret_name" && runtime_exists="true"
secret_exists "$bootstrap_secret_name" && bootstrap_exists="true"

if [[ "$force" != "true" ]] && {
  [[ "$runtime_exists" == "true" ]] || [[ "$bootstrap_exists" == "true" ]]
}; then
  echo "Refusing to overwrite an existing Kubernetes Secret. Use --force only for an intentional rotation." >&2
  exit 1
fi

temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/subnetory-helm-secrets.XXXXXXXX")
chmod 700 "$temporary_root"

cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

new_base64url_secret() {
  local byte_count="$1"
  openssl rand -base64 "$byte_count" | tr -d '\n=' | tr '+/' '-_'
}

jwt_path="$temporary_root/jwt-secret"
postgres_path="$temporary_root/postgres-password"
admin_path="$temporary_root/admin-default-password"

new_base64url_secret 64 >"$jwt_path"
new_base64url_secret 32 >"$postgres_path"
new_base64url_secret 24 >"$admin_path"
chmod 600 "$jwt_path" "$postgres_path" "$admin_path"

set_secret_from_files() {
  local name="$1"
  local exists="$2"
  shift 2

  if [[ "$exists" == "true" ]]; then
    "$kubectl_command" create secret generic "$name" \
      --namespace "$namespace" \
      "$@" \
      --dry-run=client \
      --output yaml |
      "$kubectl_command" replace --filename - >/dev/null
  else
    "$kubectl_command" create secret generic "$name" \
      --namespace "$namespace" \
      "$@" >/dev/null
  fi
}

set_secret_from_files \
  "$runtime_secret_name" \
  "$runtime_exists" \
  "--from-file=jwt-secret=$jwt_path" \
  "--from-file=postgres-password=$postgres_path"

set_secret_from_files \
  "$bootstrap_secret_name" \
  "$bootstrap_exists" \
  "--from-file=admin-default-password=$admin_path"

echo "Kubernetes Secrets initialized in namespace '$namespace'."
echo "No secret value was printed."
