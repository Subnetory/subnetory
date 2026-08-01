#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/init-compose.sh [--force] [--without-backup-encryption] [--with-backup-encryption]

Initialise les secrets locaux Docker Compose dans backend/secrets.
Aucun secret n'est affiche. --force autorise une rotation volontaire.

Par defaut, les 4 secrets sont generes, y compris
subnetory_backup_encryption_key (chiffrement des sauvegardes au repos,
backlog #13, voir backend/docs/BACKUP_ENCRYPTION.md), active par defaut
dans docker-compose.yml/docker-compose.prod.yml.

--without-backup-encryption exclut explicitement
subnetory_backup_encryption_key de la generation, pour un deploiement qui
ne veut vraiment pas de cette fonctionnalite (il faut alors aussi
recommenter/retirer le bloc correspondant dans docker-compose.yml, sinon
Docker Compose echouera au demarrage faute de fichier secret).

--with-backup-encryption reste accepte pour compatibilite ascendante mais
est desormais un no-op : ce secret est deja genere par defaut.
EOF
}

force=0
with_backup_encryption=1

while (($# > 0)); do
    case "$1" in
        --force)
            force=1
            ;;
        --with-backup-encryption)
            with_backup_encryption=1
            ;;
        --without-backup-encryption)
            with_backup_encryption=0
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Option inconnue : %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

umask 077
export LC_ALL=C

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source_root="$(cd -- "$script_dir/.." && pwd -P)"
backend_root="$source_root/backend"
secrets_root="$backend_root/secrets"

if [[ ! -d "$backend_root" ]]; then
    printf 'Repertoire backend introuvable : %s\n' "$backend_root" >&2
    exit 1
fi

secret_names=(
    subnetory_jwt_secret
    subnetory_admin_default_password
    postgres_password
)

if ((with_backup_encryption == 1)); then
    secret_names+=(subnetory_backup_encryption_key)
fi

contains_name() {
    local target="$1"
    shift
    local candidate

    for candidate in "$@"; do
        if [[ "$candidate" == "$target" ]]; then
            return 0
        fi
    done

    return 1
}

existing=()
for name in "${secret_names[@]}"; do
    if [[ -e "$secrets_root/$name" ]]; then
        existing+=("$name")
    fi
done

# Sans --force : on ne (re)cree que les secrets demandes qui n'existent pas
# encore. Un secret deja present reste strictement inchange, meme si
# d'autres secrets sont crees dans le meme appel (ex. relancer ce script
# apres une mise a jour sur une instance qui a deja ses 3 secrets de base :
# seul subnetory_backup_encryption_key sera cree). Avec --force, tous les
# secrets demandes sont regeneres (rotation volontaire explicite).
names_to_write=()
if ((force == 1)); then
    names_to_write=("${secret_names[@]}")
else
    for name in "${secret_names[@]}"; do
        if ! contains_name "$name" "${existing[@]:-}"; then
            names_to_write+=("$name")
        fi
    done
fi

names_left_untouched=()
for name in "${secret_names[@]}"; do
    if ! contains_name "$name" "${names_to_write[@]:-}"; then
        names_left_untouched+=("$name")
    fi
done

if ((${#names_to_write[@]} == 0)); then
    printf 'Tous les secrets demandes existent deja. Aucun fichier n\x27a ete modifie.\n' >&2
    printf 'Utiliser --force uniquement pour une rotation volontaire.\n' >&2
    printf 'Fichiers existants :\n' >&2
    for name in "${existing[@]}"; do
        printf 'backend/secrets/%s\n' "$name" >&2
    done
    exit 1
fi

random_hex() {
    local byte_count="$1"
    local value

    value="$(od -An -N "$byte_count" -tx1 /dev/urandom | tr -d '[:space:]')"

    if ((${#value} != byte_count * 2)) || [[ ! "$value" =~ ^[0-9a-f]+$ ]]; then
        printf 'Generation aleatoire hexadecimale invalide.\n' >&2
        return 1
    fi

    printf '%s' "$value"
}

random_uint8() {
    local value
    value="$(od -An -N 1 -tu1 /dev/urandom | tr -d '[:space:]')"

    if [[ ! "$value" =~ ^[0-9]+$ ]]; then
        printf 'Generation aleatoire numerique invalide.\n' >&2
        return 1
    fi

    printf '%s' "$value"
}

random_char() {
    local alphabet="$1"
    local byte
    local index

    byte="$(random_uint8)"
    index=$((byte % ${#alphabet}))
    printf '%s' "${alphabet:index:1}"
}

new_admin_password() {
    local upper='ABCDEFGHJKLMNPQRSTUVWXYZ'
    local lower='abcdefghijkmnopqrstuvwxyz'
    local digits='23456789'
    local special='!@#$%^&*()-_=+'
    local combined="${upper}${lower}${digits}${special}"
    local password=''
    local i
    local j
    local tmp
    local chars=()

    password+="$(random_char "$upper")"
    password+="$(random_char "$lower")"
    password+="$(random_char "$digits")"
    password+="$(random_char "$special")"

    while ((${#password} < 32)); do
        password+="$(random_char "$combined")"
    done

    for ((i = 0; i < ${#password}; i++)); do
        chars+=("${password:i:1}")
    done

    for ((i = ${#chars[@]} - 1; i > 0; i--)); do
        j=$(($(random_uint8) % (i + 1)))
        tmp="${chars[i]}"
        chars[i]="${chars[j]}"
        chars[j]="$tmp"
    done

    printf '%s' "${chars[*]}" | tr -d ' '
}

assert_secret() {
    local kind="$1"
    local value="$2"

    if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
        printf 'Le secret %s contient une fin de ligne interdite.\n' "$kind" >&2
        return 1
    fi

    case "$kind" in
        jwt)
            ((${#value} >= 128)) && [[ "$value" =~ ^[0-9a-f]+$ ]]
            ;;
        postgres)
            ((${#value} >= 64)) && [[ "$value" =~ ^[0-9a-f]+$ ]]
            ;;
        backup_encryption)
            ((${#value} >= 64)) && [[ "$value" =~ ^[0-9a-f]+$ ]]
            ;;
        admin)
            ((${#value} >= 24 && ${#value} <= 128)) &&
                grep -q '[A-Z]' <<<"$value" &&
                grep -q '[a-z]' <<<"$value" &&
                grep -q '[0-9]' <<<"$value" &&
                grep -q '[!@#$%^&*()_+=-]' <<<"$value"
            ;;
        *)
            printf 'Type de secret inconnu : %s\n' "$kind" >&2
            return 1
            ;;
    esac
}

mkdir -p -- "$secrets_root"
chmod 700 -- "$secrets_root" 2>/dev/null || true

staging_root="$(mktemp -d "$secrets_root/.staging.XXXXXX")"
backup_root="$(mktemp -d "$secrets_root/.backup.XXXXXX")"
installed=()
backed_up=()
completed=0

cleanup() {
    local status=$?
    local name

    if ((completed == 0)); then
        for name in "${installed[@]}"; do
            rm -f -- "$secrets_root/$name"
        done

        for name in "${backed_up[@]}"; do
            if [[ -e "$backup_root/$name" ]]; then
                mv -f -- "$backup_root/$name" "$secrets_root/$name"
            fi
        done
    fi

    rm -rf -- "$staging_root" "$backup_root"
    exit "$status"
}

trap cleanup EXIT INT TERM

declare -A generated_values=()

if contains_name subnetory_jwt_secret "${names_to_write[@]}"; then
    jwt_secret="$(random_hex 64)"
    assert_secret jwt "$jwt_secret"
    printf '%s' "$jwt_secret" >"$staging_root/subnetory_jwt_secret"
    generated_values[subnetory_jwt_secret]="$jwt_secret"
fi

if contains_name subnetory_admin_default_password "${names_to_write[@]}"; then
    admin_password="$(new_admin_password)"
    assert_secret admin "$admin_password"
    printf '%s' "$admin_password" >"$staging_root/subnetory_admin_default_password"
    generated_values[subnetory_admin_default_password]="$admin_password"
fi

if contains_name postgres_password "${names_to_write[@]}"; then
    postgres_password="$(random_hex 32)"
    assert_secret postgres "$postgres_password"
    printf '%s' "$postgres_password" >"$staging_root/postgres_password"
    generated_values[postgres_password]="$postgres_password"
fi

if contains_name subnetory_backup_encryption_key "${names_to_write[@]}"; then
    backup_encryption_key="$(random_hex 32)"
    assert_secret backup_encryption "$backup_encryption_key"
    printf '%s' "$backup_encryption_key" >"$staging_root/subnetory_backup_encryption_key"
    generated_values[subnetory_backup_encryption_key]="$backup_encryption_key"
fi

seen_values=()
for name in "${names_to_write[@]}"; do
    value="${generated_values[$name]}"
    for seen in "${seen_values[@]:-}"; do
        if [[ "$value" == "$seen" ]]; then
            printf 'Les secrets generes doivent tous etre distincts.\n' >&2
            exit 1
        fi
    done
    seen_values+=("$value")
done

chmod 600 -- "$staging_root"/* 2>/dev/null || true

for name in "${names_to_write[@]}"; do
    if [[ -e "$secrets_root/$name" ]]; then
        mv -- "$secrets_root/$name" "$backup_root/$name"
        backed_up+=("$name")
    fi
done

for name in "${names_to_write[@]}"; do
    mv -- "$staging_root/$name" "$secrets_root/$name"
    installed+=("$name")
done

chmod 700 -- "$secrets_root" 2>/dev/null || true
for name in "${names_to_write[@]}"; do
    chmod 600 -- "$secrets_root/$name" 2>/dev/null || true
done
completed=1

declare -A secret_labels=(
    [subnetory_jwt_secret]="Secret JWT"
    [subnetory_admin_default_password]="Mot de passe admin temporaire"
    [postgres_password]="Mot de passe DB"
    [subnetory_backup_encryption_key]="Cle de chiffrement des sauvegardes"
)

printf 'Secrets Docker Compose initialises.\n'
printf 'Repertoire       : %s\n' "$secrets_root"

for name in "${secret_names[@]}"; do
    label="${secret_labels[$name]}"
    if contains_name "$name" "${names_to_write[@]}"; then
        printf '%s : cree, valeur non affichee\n' "$label"
    else
        printf '%s : deja present, non modifie\n' "$label"
    fi
done

if ((${#names_left_untouched[@]} > 0)); then
    printf '\n'
    printf 'Secrets deja existants, laisses strictement inchanges (--force pour une rotation volontaire) :\n'
    for name in "${names_left_untouched[@]}"; do
        printf '  - %s\n' "$name"
    done
fi

printf 'Pour lire le mot de passe admin temporaire :\n'
printf '  cat %q\n' "$secrets_root/subnetory_admin_default_password"
