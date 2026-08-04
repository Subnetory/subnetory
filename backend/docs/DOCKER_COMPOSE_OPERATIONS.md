# Exploitation Docker Compose

## Objet

Ce document décrit l'exploitation quotidienne de Subnetory en mode Docker Compose autonome et en mode application seule avec PostgreSQL externe.

Les commandes autonomes sont exécutées depuis `backend/`.

## Architecture autonome

Services :

- `app` : application Spring Boot, image construite localement, utilisateur non-root `subnetory` ;
- `db` : PostgreSQL 17 Alpine ;
- `pgdata` : volume nommé contenant les données PostgreSQL ;
- trois secrets fichiers montés depuis `backend/secrets/`.

Le service PostgreSQL n'expose aucun port sur l'hôte.

## Démarrage, arrêt et état

Démarrage ou convergence vers la configuration courante :

```sh
docker compose up -d
```

Construction puis démarrage :

```sh
docker compose up -d --build
```

État :

```sh
docker compose ps
```

Arrêt temporaire :

```sh
docker compose stop
```

Redémarrage :

```sh
docker compose restart
```

Suppression des conteneurs et du réseau, données conservées :

```sh
docker compose down
```

Suppression avec destruction du volume PostgreSQL :

```sh
docker compose down -v
```

`down -v` est destructif. Une sauvegarde vérifiée et une validation explicite sont obligatoires avant son utilisation hors environnement jetable.

## Journaux

Dernières lignes :

```sh
docker compose logs --tail 200 app
docker compose logs --tail 200 db
```

Suivi en temps réel :

```sh
docker compose logs --follow app
```

Les journaux ne doivent contenir aucune valeur issue des fichiers de secrets. Ne jamais copier des secrets dans une commande, une variable d'environnement persistante ou une demande de support.

## Probes et diagnostic

Probes publiques :

```text
/actuator/health/liveness
/actuator/health/readiness
```

Contrôles :

```sh
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

Comportement attendu :

- application saine et base disponible : liveness `UP`, readiness `UP` ;
- base indisponible : liveness `UP`, readiness `DOWN` ;
- base revenue : readiness revient automatiquement à `UP`.

Le healthcheck Docker cible la readiness. L'arrêt gracieux est activé avec une phase Spring de 30 secondes et un `stop_grace_period` Compose de 35 secondes.

## Mise à jour de l'application

1. Vérifier l'état et réaliser une sauvegarde.
2. Mettre à jour les sources par fast-forward ou extraire une nouvelle archive dans un emplacement contrôlé.
3. Conserver `backend/secrets/`, `.env` non sensible et les sauvegardes hors dépôt.
4. Valider la configuration.
5. Reconstruire et converger.
6. Contrôler les migrations, les probes et les fonctions principales.

Exemple :

```sh
docker compose ps
docker compose config
docker compose build --pull
docker compose up -d
docker compose ps
docker compose logs --tail 200 app
```

Ne pas utiliser `docker compose down -v` pendant une mise à jour.

Flyway reste la source de vérité du schéma et applique automatiquement les migrations présentes (`backend/src/main/resources/db/migration/`, V1 à V21 au 04/08/2026 — ce nombre augmente à chaque migration ajoutée, se référer au contenu du répertoire plutôt qu'à ce document pour l'état exact courant).

## Sauvegarde PostgreSQL autonome

### Emplacement

Les sauvegardes doivent être écrites hors du dépôt Git, par exemple :

```text
D:\SubnetoryBackups\
/srv/backups/subnetory/
```

Les fichiers `.sql`, `.sql.gz`, dumps et répertoires `backups/` ne doivent jamais être ajoutés aux archives sources.

### Remarque sur les scripts historiques

Les scripts `backend/scripts/backup-postgres.ps1` et `restore-postgres.ps1` proviennent du modèle antérieur où `POSTGRES_PASSWORD` était lu dans `.env`.

Le mode autonome sécurisé du Sprint 2.30 n'enregistre plus ce secret dans `.env`. Tant que ces scripts ne sont pas adaptés aux secrets fichiers, ne pas réintroduire `POSTGRES_PASSWORD` dans `.env` pour les faire fonctionner. Utiliser les procédures ci-dessous.

### Sauvegarde PowerShell

Depuis `backend/` :

```powershell
$BackupRoot = "D:\SubnetoryBackups"
New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null

$BackupFile = Join-Path (
    $BackupRoot
) (
    "subnetory_{0}.sql" -f (Get-Date -Format "yyyyMMdd-HHmmss")
)

docker compose exec -T db sh -ec '
export PGPASSWORD="$(cat /run/secrets/postgres_password)"
exec pg_dump \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" \
  --clean \
  --if-exists \
  --no-owner \
  --no-acl
' | Set-Content -LiteralPath $BackupFile -Encoding utf8

if ($LASTEXITCODE -ne 0 -or (Get-Item $BackupFile).Length -eq 0) {
    Remove-Item $BackupFile -Force -ErrorAction SilentlyContinue
    throw "Échec ou fichier de sauvegarde vide."
}

Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256
```

La commande lit le mot de passe à l'intérieur du conteneur et ne l'écrit pas sur l'hôte.

### Sauvegarde Linux

Depuis `backend/` :

```sh
set -eu
backup_root=/srv/backups/subnetory
mkdir -p "$backup_root"
backup_file="$backup_root/subnetory_$(date +%Y%m%d-%H%M%S).sql"

docker compose exec -T db sh -ec '
  export PGPASSWORD="$(cat /run/secrets/postgres_password)"
  exec pg_dump \
    --username="$POSTGRES_USER" \
    --dbname="$POSTGRES_DB" \
    --clean \
    --if-exists \
    --no-owner \
    --no-acl
' > "$backup_file"

test -s "$backup_file"
sha256sum "$backup_file"
```

Tester périodiquement la restauration sur un environnement isolé.

## Restauration PostgreSQL autonome

Une restauration est destructive. Avant de continuer :

- identifier l'environnement cible ;
- arrêter les écritures applicatives ;
- produire une sauvegarde de sécurité ;
- vérifier le chemin, la taille et le SHA256 du dump ;
- conserver les journaux de l'intervention.

Arrêter l'application sans arrêter PostgreSQL :

```sh
docker compose stop app
```

### Restauration PowerShell

```powershell
$BackupFile = "D:\SubnetoryBackups\subnetory_20260717-120000.sql"

if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) {
    throw "Sauvegarde introuvable."
}

Get-Content -LiteralPath $BackupFile -Raw -Encoding utf8 |
    docker compose exec -T db sh -ec '
export PGPASSWORD="$(cat /run/secrets/postgres_password)"
exec psql \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" \
  --quiet \
  --no-password \
  --set=ON_ERROR_STOP=on
'

if ($LASTEXITCODE -ne 0) {
    throw "Échec de la restauration PostgreSQL."
}

docker compose start app
```

### Restauration Linux

```sh
set -eu
backup_file=/srv/backups/subnetory/subnetory_20260717-120000.sql

docker compose exec -T db sh -ec '
  export PGPASSWORD="$(cat /run/secrets/postgres_password)"
  exec psql \
    --username="$POSTGRES_USER" \
    --dbname="$POSTGRES_DB" \
    --quiet \
    --no-password \
    --set=ON_ERROR_STOP=on
' < "$backup_file"

docker compose start app
```

Après restauration, contrôler :

```sh
docker compose ps
docker compose logs --tail 200 app
curl --fail http://localhost:8080/actuator/health/readiness
```

Valider également l'authentification et la présence des données attendues.

## Rotation du secret JWT

La rotation invalide les jetons JWT existants. Prévoir une reconnexion des clients API.

1. Arrêter l'application.
2. Sauvegarder l'ancien secret dans un coffre sécurisé si une procédure de retour arrière est requise.
3. Générer un nouveau secret de 64 octets.
4. Remplacer atomiquement `backend/secrets/subnetory_jwt_secret`.
5. Recréer l'application.
6. Contrôler readiness et authentification.

PowerShell 7, depuis la racine du projet :

```powershell
$Path = ".\backend\secrets\subnetory_jwt_secret"
$Value = [Convert]::ToHexString(
    [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(64)
).ToLowerInvariant()

[System.IO.File]::WriteAllText(
    $Path,
    $Value,
    [System.Text.UTF8Encoding]::new($false)
)

Remove-Variable Value
```

Puis :

```sh
cd backend
docker compose up -d --force-recreate app
```

Ne pas utiliser `init-compose -Force` pour une rotation JWT seule, car cette option remplace les trois secrets.

## Rotation du mot de passe PostgreSQL

Le fichier `postgres_password` n'est utilisé par l'image PostgreSQL que lors de l'initialisation du volume. Modifier uniquement le fichier ne change pas le mot de passe du rôle déjà présent en base.

La rotation doit être coordonnée :

1. sauvegarde vérifiée ;
2. arrêt de l'application ;
3. génération d'un nouveau mot de passe hexadécimal ;
4. modification du rôle PostgreSQL avec l'ancien secret encore monté ;
5. écriture du nouveau fichier secret ;
6. recréation de l'application ;
7. validation de la connexion et de la readiness.

Exemple PowerShell 7 depuis `backend/` :

```powershell
$NewPassword = [Convert]::ToHexString(
    [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
).ToLowerInvariant()

$Sql = (
    'ALTER ROLE "{0}" WITH PASSWORD ''{1}'';' -f
    "subnetory",
    $NewPassword
)

$Sql | docker compose exec -T db sh -ec '
export PGPASSWORD="$(cat /run/secrets/postgres_password)"
exec psql \
  --username="$POSTGRES_USER" \
  --dbname="$POSTGRES_DB" \
  --quiet \
  --no-password \
  --set=ON_ERROR_STOP=on
'

if ($LASTEXITCODE -ne 0) {
    throw "Le rôle PostgreSQL n'a pas été modifié."
}

[System.IO.File]::WriteAllText(
    ".\secrets\postgres_password",
    $NewPassword,
    [System.Text.UTF8Encoding]::new($false)
)

Remove-Variable Sql
Remove-Variable NewPassword

docker compose up -d --force-recreate app
```

Le mot de passe généré est hexadécimal, ce qui évite les caractères de contrôle et limite les risques de citation SQL dans cette procédure.

Ne pas supprimer le volume et ne pas relancer `init-compose -Force` sans coordination avec le rôle PostgreSQL.

## Secret administrateur bootstrap

Le secret `subnetory_admin_default_password` :

- est requis à chaque démarrage par la configuration actuelle ;
- n'est utilisé pour initialiser le hash que si le compte `admin` n'a pas encore de mot de passe ;
- devient inerte après l'initialisation ;
- ne doit pas être supprimé ;
- ne réinitialise pas le mot de passe lorsqu'il est remplacé sur une base existante.

Une réinitialisation de compte doit être effectuée par les fonctions d'administration prévues, pas par la rotation du fichier bootstrap.

## Nettoyage sans perte de données

Nettoyage sûr :

```sh
docker compose down
docker image prune
```

Avant toute suppression de volume :

```sh
docker volume ls
docker compose config --volumes
```

Ne jamais supprimer un volume dont l'identité ou la sauvegarde n'est pas confirmée.

## Mode PostgreSQL externe

Le fichier `docker-compose.prod.yml` lance uniquement l'application.

Construire l'image :

```sh
cd backend
docker build -t subnetory:latest .
```

Valider :

```sh
docker compose -f docker-compose.prod.yml config
```

Variables nécessaires :

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
```

Le mot de passe peut être fourni par le fichier :

```text
secrets/postgres_password
```

monté sous :

```text
/run/secrets/spring.datasource.password
```

Les variables d'environnement suivantes restent supportées et ont la priorité Spring standard sur les fichiers :

```text
SPRING_DATASOURCE_PASSWORD
SUBNETORY_JWT_SECRET
SUBNETORY_ADMIN_DEFAULT_PASSWORD
```

Pour limiter l'exposition, préférer les secrets fichiers. Les trois fichiers référencés par le Compose production doivent exister, même lorsqu'une variable d'environnement prioritaire est utilisée.

Démarrage :

```sh
docker compose -f docker-compose.prod.yml up -d
```

Le PostgreSQL externe doit être sauvegardé, supervisé et sécurisé par l'exploitant.

## Dépannage

### Readiness DOWN, liveness UP

Vérifier :

```sh
docker compose ps
docker compose logs --tail 200 db
docker compose exec db pg_isready -U subnetory
```

Cette combinaison indique généralement une indisponibilité de la base, pas un crash du processus Java.

### L'application ne démarre plus après suppression d'un secret

Restaurer les trois fichiers attendus dans `backend/secrets/`. Le secret administrateur bootstrap reste requis même après le changement initial du mot de passe.

### Erreur d'authentification PostgreSQL après rotation

Vérifier que le rôle PostgreSQL et `secrets/postgres_password` contiennent la même valeur. Ne pas réinitialiser le volume.

### Scan Nmap incomplet

Le conteneur est non-root, sans `host network`, sans `cap_add` et sans mode privilégié. Les limites sont décrites dans `NMAP_RUNTIME.md`.

## Documents liés

- `backend/docs/INSTALL_DOCKER_COMPOSE.md`
- `backend/docs/KUBERNETES_HELM_READINESS.md`
- `backend/docs/NMAP_RUNTIME.md`
- `backend/docs/BACKUP_STRATEGY.md`
- `backend/docs/RESTORE_OPERATIONS.md`
