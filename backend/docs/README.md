# Subnetory - Documentation technique

Ce repertoire contient les rapports de validation, documents d'exploitation et decisions techniques du projet Subnetory.

## Historique du projet

Subnetory avance par petits increments, chacun documente, teste et valide par la suite Maven avant fusion sur `main`. L'historique detaille (versions, dates, changements fonctionnels et correctifs) est tenu dans [CHANGELOG.md](../../CHANGELOG.md), a la racine du depot.

## Documentation d'exploitation

| Sujet | Document |
|---|---|
| Installation Windows | [INSTALL_WINDOWS.md](INSTALL_WINDOWS.md) |
| Prerequis | [SETUP_PREREQUISITES.md](SETUP_PREREQUISITES.md) |
| Guide administrateur | [ADMIN_GUIDE.md](ADMIN_GUIDE.md) |
| Guide utilisateur MVP | [USER_GUIDE_MVP.md](USER_GUIDE_MVP.md) |
| Import CSV | [CSV_IMPORT_FORMAT.md](CSV_IMPORT_FORMAT.md) |
| Import general | [IMPORT_FORMAT.md](IMPORT_FORMAT.md) |
| Strategie de sauvegarde | [BACKUP_STRATEGY.md](BACKUP_STRATEGY.md) |
| Planification sauvegardes Windows | [BACKUP_SCHEDULING_WINDOWS.md](BACKUP_SCHEDULING_WINDOWS.md) |
| Operations de restauration | [RESTORE_OPERATIONS.md](RESTORE_OPERATIONS.md) |
| Installation Docker Compose | [INSTALL_DOCKER_COMPOSE.md](INSTALL_DOCKER_COMPOSE.md) |
| Operations Docker Compose | [DOCKER_COMPOSE_OPERATIONS.md](DOCKER_COMPOSE_OPERATIONS.md) |
| HTTPS reverse proxy (Caddy, optionnel) | [HTTPS_REVERSE_PROXY.md](HTTPS_REVERSE_PROXY.md) |
| Installation Kubernetes | [INSTALL_KUBERNETES.md](INSTALL_KUBERNETES.md) |
| Operations Kubernetes | [KUBERNETES_OPERATIONS.md](KUBERNETES_OPERATIONS.md) |
| Compatibilite Kubernetes | [KUBERNETES_COMPATIBILITY.md](KUBERNETES_COMPATIBILITY.md) |
| Etat de preparation Kubernetes/Helm | [KUBERNETES_HELM_READINESS.md](KUBERNETES_HELM_READINESS.md) |
| Parite API-first | [API_FIRST_PARITY.md](API_FIRST_PARITY.md) |
| Guide de style UI (formulaires, cartes, boutons) | [UI_STYLE_GUIDE.md](UI_STYLE_GUIDE.md) |

## Regles projet

### Migrations Flyway

- Les migrations Flyway `backend/src/main/resources/db/migration/*.sql` sont du code source.
- Elles doivent toujours etre versionnees et presentes dans les archives de reprise.
- Ne jamais les exclure avec une regle globale `*.sql`.
- Ne jamais modifier une migration deja publiee.
- Toute nouvelle migration doit etre ajoutee avec le prochain numero disponible.

### UI - formulaires, cartes, boutons

- Toujours suivre [UI_STYLE_GUIDE.md](UI_STYLE_GUIDE.md) pour toute page ou modification de template Thymeleaf.
- Page de reference : `network/address-form.html`.
- Ne jamais reintroduire les classes legacy `sn-form-group` / `sn-form-control` / `sn-form-actions`.

### Dumps SQL et backups

- Ne jamais commiter ni archiver de dump SQL (`*.sql.gz`, `*.dump`).
- Ne jamais commiter ni archiver le contenu de `backups/`.
- `.env.example` est versionne ; `.env` et `.env.*` reels sont interdits.

### Tests

PowerShell :

```powershell
cd backend
.\mvnw.cmd test
```

Bash :

```bash
cd backend
./mvnw test
```

Le nombre de tests n'est plus fige ici pour eviter la derive silencieuse entre ce fichier et le README racine : se referer au job `test` de la CI GitHub Actions (declenche a chaque push/PR) pour le compte et le statut a jour.

### Copies et archives Windows

Utiliser `Copy-Item`, `Expand-Archive`, `[System.IO.Compression.ZipFile]` ou le script versionne :

```powershell
pwsh.exe -File scripts/make-reprise-archive.ps1
```

Ne pas utiliser `robocopy`.

Ne pas utiliser `Compress-Archive` pour les archives de reprise finales : le module Archive 1.x peut produire des entrees ZIP avec separateurs `\` non portables sous Linux/macOS. Le script versionne utilise `[System.IO.Compression.ZipFile]` et controle que les entrees ZIP utilisent des separateurs `/`.

### Assets frontend

Tous les assets sont servis localement sous `/assets/`. Pas de CDN, Bootstrap, HTMX, npm ou Node.

### LDAP

LDAP est desactive par defaut.
Il se configure depuis l'administration graphique ou via l'API `/api/v1/admin/ldap`.
Le compte admin LOCAL reste toujours fonctionnel, que LDAP soit active ou non.
Voir [API_FIRST_PARITY.md](API_FIRST_PARITY.md) pour le suivi de parite API-first.

### Actions GitHub tierces (CI/CD)

- Toute Action GitHub tierce (namespace hors `actions/`, `docker/`, `github/`) doit etre reference par **commit SHA complet**, jamais par un tag mutable (`@v1`, `@latest`, `@master`).
- Le SHA epingle doit etre commente en clair avec la version correspondante, par exemple `uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0`.
- Regle nee du Sprint 2.36, suite a la compromission de supply-chain d'`aquasecurity/trivy-action` (GHSA-69fq-xp46-6x23 / CVE-2026-33634, mars 2026) : 76 des 77 tags historiques de ce depot avaient ete force-pousses vers des commits malveillants. Un tag, meme d'apparence propre aujourd'hui, reste par nature reecrivable ; un commit SHA ne l'est pas.
- Toute mise a jour d'un SHA epingle est une decision explicite (revue manuelle de la release visee), jamais une automatisation aveugle. Dependabot peut proposer la mise a jour (PR), mais le merge reste manuel.

### Administration utilisateurs

L'interface d'administration (`/admin/**`) est reservee a `ROLE_ADMIN`.
Voir [ADMIN_GUIDE.md](ADMIN_GUIDE.md) pour le detail des regles anti-lockout.

### Deploiement

L'application est packagee via Docker multi-stage. Voir `backend/README.md` pour les modes de deploiement.

L'URL JDBC dans `docker-compose.yml` doit inclure `?stringtype=unspecified` pour que les colonnes PostgreSQL de type `inet`, `cidr` et `macaddr` soient correctement resolues par le driver JDBC.

### Dashboard

La page d'accueil (`/`) affiche les compteurs globaux et le top des sous-reseaux les plus utilises.
Accessible a tout utilisateur authentifie, quel que soit le role.
Voir [USER_GUIDE_MVP.md](USER_GUIDE_MVP.md) pour le detail du calcul de capacite.

### CI/CD

La CI (`ci.yml`) se declenche a chaque push sur `main`, chaque pull request vers `main`, et manuellement via `workflow_dispatch`. Elle compile le projet et execute la suite de tests (`./mvnw clean "-Dtest=*Test,*IT" test`) ; PostgreSQL est fourni par Testcontainers, sans service `postgres` declare au niveau du workflow.

Chaque tag `v*` declenche le workflow de release (`release.yml`) : build du JAR, normalisation du nom d'image en minuscules, authentification GHCR via `GITHUB_TOKEN`, publication de l'image Docker taguee `vX.Y.Z` et `latest` sur `ghcr.io/subnetory/subnetory`, puis creation d'une GitHub Release avec notes generees et le JAR attache.

## Sprint courant

Le travail en cours vise la stabilisation de la release candidate : sécurité multi-contexte, administration des comptes, cohérence d'import et validation de bout en bout.
