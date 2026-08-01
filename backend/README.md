# Subnetory — Backend

Application IPAM (IP Address Management) moderne, self-hosted, API-first.

---

## Prérequis

- Java 21 (JDK) — pour lancer en JAR standalone
- Maven (ou utiliser le wrapper `mvnw` / `mvnw.cmd` inclus)
- PostgreSQL 17 — serveur externe ou via Docker Compose
- Docker et Docker Compose v2 — pour le mode conteneur

---

## Installation depuis GHCR (images publiées)

Les images Docker sont publiées automatiquement sur GitHub Container Registry à chaque release.

Le dépôt et ses packages GHCR sont privés : le pull nécessite une authentification avec un compte GitHub ayant accès au projet, via un personal access token avec le scope `read:packages`.

```bash
docker login ghcr.io
docker pull ghcr.io/subnetory/subnetory:latest
# ou une version spécifique
docker pull ghcr.io/subnetory/subnetory:v0.5.0
```

Pour utiliser l'image publiée avec `docker-compose.prod.yml`, remplacer `image: subnetory:latest` par `image: ghcr.io/subnetory/subnetory:latest` dans le fichier.

---

## Distribution Windows portable — Sprint 2.29

Le script `scripts/make-jpackage.ps1` génère une distribution Windows x64 autonome avec un runtime Java 21 créé par `jlink`.
Aucun JRE ou JDK séparé n'est requis sur la machine cible.

Prérequis de construction :

- PowerShell 7 ;
- JDK 21 avec `jlink` et `jpackage` ;
- WiX Toolset 3 uniquement pour l'option MSI.

Construction depuis la racine du dépôt :

```powershell
pwsh.exe -File .\scripts\make-jpackage.ps1
```

L'artefact principal est :

```text
dist\windows\subnetory-0.5.0-windows-x64\Subnetory.exe
```

PostgreSQL 17 reste externe. Avant le démarrage, définir :

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/subnetory"
$env:SPRING_DATASOURCE_USERNAME="subnetory"
$env:SPRING_DATASOURCE_PASSWORD="<mot-de-passe-postgresql>"
$env:SUBNETORY_JWT_SECRET="<secret-aleatoire-de-32-caracteres-minimum>"
$env:SUBNETORY_ADMIN_DEFAULT_PASSWORD="<mot-de-passe-bootstrap-temporaire>"
.\dist\windows\subnetory-0.5.0-windows-x64\Subnetory.exe
```

Le compte local `admin` est redirigé vers `/profile/change-password-required` au premier login.
Le dashboard, l'administration et l'émission JWT restent interdits tant que le mot de passe temporaire n'a pas été changé.

Voir [`docs/INSTALL_WINDOWS.md`](docs/INSTALL_WINDOWS.md) pour la procédure détaillée.

---

## Démarrage rapide — Docker Compose (recommandé)

### 1. Générer les secrets

`docker-compose.yml` lit `SUBNETORY_JWT_SECRET`, `SUBNETORY_ADMIN_DEFAULT_PASSWORD` et le mot de passe PostgreSQL via des secrets Docker fichiers (`backend/secrets/`), jamais via `.env`. Générer ces secrets avec le script versionné :

**PowerShell :**
```powershell
pwsh.exe -File ..\scripts\init-compose.ps1
```

**Bash :**
```bash
../scripts/init-compose.sh
```

Le script refuse d'écraser des secrets déjà présents sauf avec `--force` (rotation volontaire), et n'affiche jamais leur valeur. Pour lire le mot de passe admin temporaire généré :

```bash
cat secrets/subnetory_admin_default_password
```

> **Important :**
> - Le secret JWT généré fait toujours au moins 32 caractères (exigé par `SecurityConfig`).
> - `subnetory_admin_default_password` est un mot de passe de bootstrap temporaire. Le premier login Web force son remplacement avant tout accès applicatif ou émission de JWT.
> - `.env` ne sert qu'à des réglages non sensibles (`HOST_PORT`, `SERVER_PORT`, `POSTGRES_USER`) : copier `.env.example` vers `.env` uniquement si l'une de ces valeurs doit changer.

### 2. Démarrer l'application

```bash
docker compose up --build
```

L'application est accessible sur [http://localhost:8080](http://localhost:8080).

PostgreSQL est démarré automatiquement et les migrations Flyway sont appliquées au premier lancement.

### 3. Vérifier le démarrage

```bash
curl http://localhost:8080/actuator/health
# Attendu : {"status":"UP"}
```

### 4. Arrêter

```bash
docker compose down
```

Les données sont conservées dans le volume Docker `pgdata`. Un `docker compose up` redémarre l'application avec les données existantes.

Pour supprimer les données :
```bash
docker compose down -v
```

---

## Changer le port d'écoute

Par défaut l'application écoute sur le port `8080`. Pour utiliser un port différent, définir dans `.env` :

```bash
HOST_PORT=9090    # port exposé sur la machine hôte
SERVER_PORT=9090  # port sur lequel Spring Boot écoute dans le conteneur
```

Les deux variables doivent être identiques pour que le mapping Docker et le healthcheck restent cohérents.

---

## Démarrage en JAR standalone

### 1. Compiler

**PowerShell :**
```powershell
.\mvnw.cmd package -DskipTests
```

**Bash :**
```bash
./mvnw package -DskipTests
```

### 2. Lancer

L'application nécessite un PostgreSQL accessible. Adapter l'URL selon l'environnement.

**PowerShell :**
```powershell
$env:SUBNETORY_JWT_SECRET="votre-secret-32-caracteres-minimum"
$env:SUBNETORY_ADMIN_DEFAULT_PASSWORD="votre-mot-de-passe-admin"
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/subnetory"
$env:SPRING_DATASOURCE_USERNAME="subnetory"
$env:SPRING_DATASOURCE_PASSWORD="votre-mot-de-passe-db"
java -jar target/subnetory-*.jar
```

**Bash :**
```bash
SUBNETORY_JWT_SECRET=votre-secret-32-caracteres-minimum \
SUBNETORY_ADMIN_DEFAULT_PASSWORD=votre-mot-de-passe-admin \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/subnetory \
SPRING_DATASOURCE_USERNAME=subnetory \
SPRING_DATASOURCE_PASSWORD=votre-mot-de-passe-db \
java -jar target/subnetory-*.jar
```

L'application refuse de démarrer si `SUBNETORY_JWT_SECRET` ou `SUBNETORY_ADMIN_DEFAULT_PASSWORD` sont absents.

---

## Déploiement en production (application seule)

Utiliser `docker-compose.prod.yml` quand PostgreSQL est géré séparément.

### 1. Construire l'image localement

```bash
docker build -t subnetory:latest .
```

### 2. Configurer `.env` avec les paramètres de production

En mode production, définir les variables PostgreSQL externes dans `.env` :

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres.example.local:5432/subnetory
SPRING_DATASOURCE_USERNAME=subnetory
SPRING_DATASOURCE_PASSWORD=votre-mot-de-passe-db
```

### 3. Lancer

```bash
docker compose -f docker-compose.prod.yml up -d
```

---

## Variables d'environnement

Copier `.env.example` vers `.env` et remplir les valeurs. Le fichier `.env.example` contient la documentation de chaque variable.

| Variable | Obligatoire | Description |
|---|---|---|
| `SUBNETORY_JWT_SECRET` | **Oui** | Secret JWT, minimum 32 caractères |
| `SUBNETORY_ADMIN_DEFAULT_PASSWORD` | **Oui** | Mot de passe admin initial |
| `SPRING_DATASOURCE_PASSWORD` | **Oui** | Mot de passe base de données |
| `POSTGRES_PASSWORD` | **Oui** (compose dev) | Mot de passe PostgreSQL pour le service db |
| `HOST_PORT` | Non | Port hôte, défaut `8080` |
| `SERVER_PORT` | Non | Port conteneur Spring Boot, défaut `8080` |
| `POSTGRES_USER` | Non | Utilisateur PostgreSQL, défaut `subnetory` |
| `SPRING_DATASOURCE_URL` | Prod / JAR | URL PostgreSQL externe |
| `SPRING_DATASOURCE_USERNAME` | Prod / JAR | Utilisateur PostgreSQL externe |
| `SUBNETORY_LDAP_ENABLED` | Non | `false` par défaut |

Voir `.env.example` pour la liste complète incluant les valeurs LDAP de démarrage.

---

## LDAP

L'authentification LDAP est désactivée par défaut. Elle se configure depuis l'administration graphique ou via l'API `/api/v1/admin/ldap`.

Les variables `SUBNETORY_LDAP_*` restent disponibles comme valeurs initiales de démarrage. Dès qu'une configuration est enregistrée dans l'application, elle devient la configuration active ; le mot de passe du compte de service est alors stocké chiffré en base.

Voir [docs/API_FIRST_PARITY.md](docs/API_FIRST_PARITY.md) pour le suivi de parité API-first.

---

## Lancer les tests

**PowerShell :**
```powershell
.\mvnw.cmd clean "-Dtest=*Test,*IT" test
```

**Bash :**
```bash
./mvnw clean test
```

Les tests d'intégration utilisent Testcontainers (PostgreSQL embarqué) — Docker doit être disponible.

---

## Structure du projet

```
backend/
├── Dockerfile
├── docker-compose.yml           ← Développement local (app + PostgreSQL)
├── docker-compose.prod.yml      ← Production (app seule)
├── .env.example                 ← Template de configuration (versionné)
├── .env                         ← Configuration locale (non versionné, à créer)
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/dev/subnetory/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── db/migration/    ← Migrations Flyway (V1 à V4+)
│   │       ├── static/assets/
│   │       └── templates/
│   └── test/
└── docs/                        ← Rapports de validation par sprint
```

---

## Sécurité — rappels

- Ne jamais commiter `.env`
- `SUBNETORY_JWT_SECRET` doit être différent entre environnements (dev, prod)
- Le mot de passe admin initial est temporaire et doit être remplacé lors du premier login Web obligatoire
- PostgreSQL n'est pas exposé vers l'extérieur dans `docker-compose.yml`
- L'image finale tourne avec un utilisateur non-root (`subnetory`)

