# Subnetory - Strategie de sauvegarde PostgreSQL Docker

> Document : BACKUP_STRATEGY.md
> Version : 1.0
> Sprint : 2.15
> Environnement cible : Docker Desktop Windows

> **Phase 7 (audit 31/07/2026)** : ce document decrit les scripts PowerShell
> historiques, externes a l'application. Depuis la Phase 7, Subnetory
> integre aussi son propre moteur de sauvegarde (`pg_dump`/`pg_restore`
> lance par l'application elle-meme), pilotable via l'IHM `/admin/backup`
> ou l'API `/api/v1/admin/backup` — identique en Docker Compose et
> Kubernetes, sans dependance a PowerShell. Commandes API : voir
> `backend/docs/ADMIN_GUIDE.md` (section "Sauvegardes"). Les deux
> mecanismes sont independants et ne doivent pas etre actives ensemble sur
> la meme base (voir `charts/subnetory/values.yaml`, cles `backup` et
> `backupApp`, dont la combinaison est rejetee par le chart Helm).

---

## Contexte

Subnetory stocke toutes ses donnees dans un volume Docker PostgreSQL nomme `pgdata`.
Ce volume est gere par Docker et persiste entre les redemarrages.

En cas de suppression accidentelle du volume, de corruption, ou de migration vers
un autre serveur, une sauvegarde externe est indispensable.

Ce document decrit la strategie de sauvegarde mise en place, les scripts disponibles,
les procedures de restauration et les bonnes pratiques.

---

## Architecture de sauvegarde

```
conteneur db (backend-db-1)
     |
     | docker exec ... pg_dump
     v
fichier .sql.gz horodate
     |
     v
backups/
  hourly/      retention 7 jours
  daily/       retention 31 jours
  monthly/     retention 12 mois
  quarterly/   retention 4 trimestres
```

La sauvegarde s'appuie sur `pg_dump`, l'outil officiel PostgreSQL.
Le fichier produit est un dump SQL compresse (gzip).
La restauration s'effectue via `psql` dans le meme conteneur.

---

## Scripts disponibles

### backup-postgres.ps1

Effectue une sauvegarde et gere la rotation.

**Emplacement :** `backend/scripts/backup-postgres.ps1`

**Usage :**

```powershell
# Sauvegarde daily (defaut)
.\backup-postgres.ps1

# Sauvegarde hourly
.\backup-postgres.ps1 -Type hourly

# Sauvegarde dans un dossier specifique
.\backup-postgres.ps1 -Type daily -BackupDir D:\subnetory\backups

# Conserver 60 sauvegardes daily au lieu de 31
.\backup-postgres.ps1 -Type daily -Retention 60
```

**Parametres :**

| Parametre | Defaut | Description |
|-----------|--------|-------------|
| -Type | daily | hourly / daily / monthly / quarterly |
| -BackupDir | `<repository>/backups` | Dossier racine des sauvegardes |
| -Retention | voir tableau | Nombre de fichiers a conserver |
| -Container | backend-db-1 | Nom du conteneur Docker PostgreSQL |
| -DbName | subnetory | Nom de la base PostgreSQL |
| -DbUser | subnetory | Utilisateur PostgreSQL |
| -EnvFile | auto-detecte | Chemin vers le fichier .env |

**Retention par defaut :**

| Type | Retention | Duree couverte |
|------|-----------|----------------|
| hourly | 168 fichiers | 7 jours |
| daily | 31 fichiers | 1 mois |
| monthly | 12 fichiers | 1 an |
| quarterly | 4 fichiers | 1 an |

**Prerequis :**

- Docker Desktop demarre
- Conteneur `backend-db-1` actif (via `docker compose up -d`)
- Fichier `.env` present dans le dossier `backend/`
- Variable `POSTGRES_PASSWORD` definie dans `.env`

**Chemin par defaut :**

Le dossier de sauvegarde par defaut est `backups/` a la racine du repository, a cote du dossier `backend/`.

Depuis `backend/scripts/`, cela correspond a `../../backups`.

Le script de controle `check-postgres-backup.ps1` utilise le meme chemin par defaut.

---

### restore-postgres.ps1

Restaure une sauvegarde dans la base de donnees.

**Emplacement :** `backend/scripts/restore-postgres.ps1`

**Usage :**

```powershell
# Restauration avec confirmation interactive
.\restore-postgres.ps1 -BackupFile .\backups\daily\subnetory_2026-06-03.sql.gz

# Restauration avec sauvegarde de securite automatique
.\restore-postgres.ps1 -BackupFile .\backups\daily\subnetory_2026-06-03.sql.gz -BackupBeforeRestore

# Restauration sans confirmation (mode automatique)
.\restore-postgres.ps1 -BackupFile .\backups\daily\subnetory_2026-06-03.sql.gz -Force
```

**Parametres :**

| Parametre | Obligatoire | Description |
|-----------|-------------|-------------|
| -BackupFile | Oui | Chemin du fichier .sql.gz a restaurer |
| -Container | Non | Nom du conteneur Docker (defaut : backend-db-1) |
| -DbName | Non | Nom de la base (defaut : subnetory) |
| -DbUser | Non | Utilisateur PostgreSQL (defaut : subnetory) |
| -EnvFile | Non | Chemin vers .env (auto-detecte par defaut) |
| -BackupBeforeRestore | Non | Cree une sauvegarde avant restauration |
| -Force | Non | Desactive la confirmation interactive |

---

## Structure des dossiers

```
backups/
  hourly/
    subnetory_2026-06-03_14-00.sql.gz
    subnetory_2026-06-03_15-00.sql.gz
    ...
  daily/
    subnetory_2026-06-03.sql.gz
    subnetory_2026-06-04.sql.gz
    ...
  monthly/
    subnetory_2026-06.sql.gz
    ...
  quarterly/
    subnetory_2026-Q2.sql.gz
    ...
```

Le dossier `backups/` est cree automatiquement par le script si absent.

**Important :** le dossier `backups/` doit etre exclu de Git.
S'assurer que `.gitignore` contient la ligne `backups/`.

---

## Procedure de restauration d'urgence

### Cas 1 : restauration sur le meme serveur

```powershell
cd backend

# 1. Demarrer uniquement la base (pas l'app)
docker compose up -d db

# 2. Attendre que la base soit saine
docker compose ps

# 3. Restaurer
.\scripts\restore-postgres.ps1 -BackupFile ..\backups\daily\subnetory_2026-06-03.sql.gz

# 4. Demarrer l'application
docker compose up -d

# 5. Verifier
Invoke-WebRequest http://localhost:8080/actuator/health
```

### Cas 2 : restauration sur un nouveau serveur

```powershell
# 1. Installer Docker Desktop

# 2. Copier le dossier backend/ et le fichier de sauvegarde

# 3. Copier .env.example en .env et configurer les mots de passe

cd backend

# 4. Demarrer la base
docker compose up -d db

# 5. Restaurer
.\scripts\restore-postgres.ps1 -BackupFile <chemin_vers_fichier.sql.gz>

# 6. Demarrer l'application
docker compose up -d

# 7. Verifier
Invoke-WebRequest http://localhost:8080/actuator/health
```

---

## Automatisation via Taches planifiees Windows

Pour automatiser la sauvegarde sans outil externe, utiliser le Planificateur de taches.

### Exemple : sauvegarde daily a 02h00

**Via la ligne de commande :**

```powershell
schtasks /create `
  /tn "Subnetory-Backup-Daily" `
  /tr "pwsh.exe -NonInteractive -ExecutionPolicy Bypass -File C:\subnetory\backend\scripts\backup-postgres.ps1 -Type daily" `
  /sc daily `
  /st 02:00 `
  /ru SYSTEM `
  /f
```

### Exemple : sauvegarde hourly (8h-20h)

```powershell
schtasks /create `
  /tn "Subnetory-Backup-Hourly" `
  /tr "pwsh.exe -NonInteractive -ExecutionPolicy Bypass -File C:\subnetory\backend\scripts\backup-postgres.ps1 -Type hourly" `
  /sc hourly `
  /mo 1 `
  /st 08:00 `
  /et 20:00 `
  /ru SYSTEM `
  /f
```

---

## Recommandations

### Sauvegardes hors site

Les fichiers `.sql.gz` produits peuvent etre copies vers un emplacement externe :
- Un partage reseau (NAS)
- Un stockage cloud (OneDrive, SharePoint, S3-compatible)
- Un autre serveur via SCP ou robocopy (attention, robocopy interdit dans Subnetory)

Exemple avec Copy-Item vers un NAS :

```powershell
$source = ".\backups\daily"
$dest   = "\\nas\subnetory-backups\daily"
Copy-Item -Path "$source\*.sql.gz" -Destination $dest -Force
```

### Verification periodique

Il est recommande de tester la restauration au moins une fois par mois sur un
environnement de test pour s'assurer que les sauvegardes sont exploitables.

### Taille des sauvegardes

Un dump SQL compresse de Subnetory est typiquement tres petit (quelques Ko a quelques Mo
selon le volume d'adresses IP gere). Les sauvegardes ne consomment pas d'espace significatif.

---

## Limites connues

| Limite | Description |
|--------|-------------|
| Pas de backup a chaud transactionnel | pg_dump est coherent mais non PITR |
| Dependance au conteneur actif | Le conteneur db doit etre running pour lancer le backup |
| Pas de chiffrement des fichiers | Les fichiers .sql.gz produits par ces scripts PowerShell ne sont pas chiffres. Le moteur integre (`/admin/backup`, Phase 7) supporte le chiffrement AES-256-GCM + HMAC-SHA256 au repos, active par defaut pour toute nouvelle installation Docker Compose — voir `BACKUP_ENCRYPTION.md` |
| Windows uniquement | Les scripts sont ecrits en PowerShell pour Windows + Docker Desktop |

Pour un environnement Linux, les memes commandes `pg_dump` et `psql` fonctionnent
mais les scripts `.sh` devront etre adaptes (hors scope de ce document).

---

## Historique

| Version | Sprint | Description |
|---------|--------|-------------|
| 1.3 | 2.22 | Ajout du controle d integrite GZip, taille decompressee et hash SHA256 dans check-postgres-backup.ps1 |
| 1.2 | 2.21 | Harmonisation du chemin par defaut entre backup-postgres.ps1 et check-postgres-backup.ps1 |
| 1.1 | 2.20 | Alignement du profil de retention production : hourly 7 jours, daily 31 jours, monthly 12 mois, quarterly 4 trimestres |
| 1.0 | 2.15 | Creation initiale - backup-postgres.ps1, restore-postgres.ps1, rotation |


## Controle operationnel des backups

Le script scripts/check-postgres-backup.ps1 permet de controler rapidement l'etat des sauvegardes PostgreSQL Docker.

Il verifie notamment :

- l'existence du dossier de sauvegarde ;
- la presence d'au moins un fichier .sql.gz ;
- la date du dernier backup ;
- l'age du dernier backup en heures ;
- la taille du dernier fichier ;
- un statut exploitable : OK, WARNING, CRITICAL ou UNKNOWN.

### Commande de controle manuel

Depuis le dossier backend :

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-postgres-backup.ps1

Sans parametre `-BackupRoot`, le controle cherche dans `backups/` a la racine du repository.
C'est le meme emplacement par defaut que `backup-postgres.ps1`.

En sortie JSON :

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-postgres-backup.ps1 -Json

Sur Windows, si la commande powershell est mal resolue par le terminal, utiliser le chemin complet :

    & "C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File ".\scripts\check-postgres-backup.ps1"

### Seuils par defaut

- OK : dernier backup plus recent que 30 heures ;
- WARNING : dernier backup age de 30 heures ou plus ;
- CRITICAL : dernier backup age de 48 heures ou plus ;
- CRITICAL : dossier backup absent ;
- CRITICAL : aucun fichier .sql.gz trouve ;
- CRITICAL : dernier fichier backup vide ;
- UNKNOWN : erreur inattendue pendant l'execution du controle.

Les seuils peuvent etre ajustes :

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-postgres-backup.ps1 -WarningHours 24 -CriticalHours 36

### Codes retour

| Code | Statut | Signification |
|---:|---|---|
| 0 | OK | Backup recent et exploitable |
| 1 | WARNING | Backup trop ancien selon le seuil warning |
| 2 | CRITICAL | Backup absent, vide ou trop ancien selon le seuil critique |
| 3 | UNKNOWN | Erreur inattendue du script |

### Exemple de sortie attendue

    Status               : OK
    Message              : Latest backup is recent.
    Backup root          : C:\...\backend\backups
    Backup count         : 1
    Latest backup        : C:\...\backend\backups\daily\subnetory_2026-06-25.sql.gz
    Latest age hours     : 0.5
    Latest size bytes    : 3815
    Warning threshold    : 30 hours
    Critical threshold   : 48 hours
    Checked at           : 2026-06-25T15:46:49

### Recommandations d'exploitation

- executer ce controle apres chaque backup planifie ;
- executer ce controle manuellement avant une operation importante ;
- verifier regulierement qu'un restore reel reste possible ;
- ne jamais commiter le dossier backups/ ;
- conserver les backups hors du repository Git ;
- documenter toute modification de rotation ou de chemin de sauvegarde.


---

## Planification production-ready des backups

La procedure detaillee Sprint 2.18 est documentee dans :

    backend/docs/BACKUP_SCHEDULING_WINDOWS.md

Elle couvre :

- la creation des taches hourly, daily, monthly et quarterly ;
- la creation d'une tache de controle avec check-postgres-backup.ps1 ;
- l'execution manuelle des taches ;
- la desactivation, la reactivation et la suppression des taches ;
- les points de vigilance lies a Docker Desktop, au compte SYSTEM et aux chemins de sauvegarde hors repository.

Pour une exploitation Windows, utiliser cette procedure plutot que les exemples courts presents dans ce document.


---

## Operations de restauration

Les operations de restauration sont documentees dans :

    backend/docs/RESTORE_OPERATIONS.md

Cette procedure complete la strategie de sauvegarde en precisant :

- les scenarios de restauration supportes ;
- les controles pre-restauration ;
- la creation d'un backup de securite avant restauration ;
- la selection explicite du fichier de backup ;
- la procedure locale ou dev ;
- les controles post-restauration ;
- les regles de securite pour eviter une restauration accidentelle en production.

Toute restauration doit etre consideree comme une operation sensible et doit etre documentee dans un compte rendu d'intervention.

---

## Sprint 2.22 - Controle d integrite GZip

Depuis le Sprint 2.22, le script scripts/check-postgres-backup.ps1 ne se limite plus a verifier la presence, l age et la taille du dernier fichier .sql.gz.

Le controle verifie aussi :

- que le fichier .sql.gz est decompressable avec GZip ;
- que le contenu decompresse n est pas vide ;
- le hash SHA256 du dernier backup ;
- la taille decompressee du dernier backup.

Un backup recent mais corrompu est donc retourne en CRITICAL.

La sortie texte affiche maintenant les champs suivants lorsque le dernier backup existe :

- Latest SHA256 ;
- Decompressed bytes ;
- GZip integrity.

La sortie JSON contient les champs supplementaires suivants :

- latestBackupSha256 ;
- latestBackupDecompressedSizeBytes ;
- latestBackupGzipValid.

Exemple de controle manuel depuis backend :

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-postgres-backup.ps1

Exemple en JSON :

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-postgres-backup.ps1 -Json

