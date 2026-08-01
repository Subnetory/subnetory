# Subnetory - Planification Windows des sauvegardes PostgreSQL

> Document d'exploitation - Sprint 2.18
> Environnement cible : Windows + Docker Desktop + PowerShell 7

---

## Objectif

Ce document explique comment planifier automatiquement les sauvegardes PostgreSQL Docker de Subnetory avec le Planificateur de taches Windows.

Les scripts concernes sont :

- scripts/backup-postgres.ps1
- scripts/check-postgres-backup.ps1
- scripts/restore-postgres.ps1

---

## Prerequis

- Docker Desktop installe et demarre.
- Le projet Subnetory est present localement.
- Le fichier backend/.env existe et contient POSTGRES_PASSWORD.
- Le conteneur PostgreSQL est actif.
- PowerShell 7 est installe et accessible via pwsh.exe.
- PowerShell doit etre lance en administrateur pour creer ou modifier les taches planifiees avec /ru SYSTEM.

Verifier le conteneur :

    docker compose ps

Verifier un backup manuel :

    .\scripts\backup-postgres.ps1 -Type daily

Verifier l'etat des backups :

    .\scripts\check-postgres-backup.ps1

---

## Chemins de reference

Adapter les chemins selon l'installation locale.

Exemple de racine projet :

    C:\Users\votre-utilisateur\Downloads\subnetory-github

Exemple de dossier backend :

    C:\Users\votre-utilisateur\Downloads\subnetory-github\backend

Exemple de dossier backup :

    C:\Users\votre-utilisateur\Downloads\subnetory-backups

Le dossier backup peut etre hors repository pour eviter toute erreur de commit.

---

## Creation du dossier de sauvegarde

Depuis PowerShell :

    $backupRoot = "C:\Users\votre-utilisateur\Downloads\subnetory-backups"
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

---

## Tache hourly

Objectif : creer une sauvegarde toutes les heures en periode active.

Retention par defaut du script : 168 fichiers, soit 7 jours si la tache hourly s'execute 24h/24.

Commande :

    schtasks /create `
      /tn "Subnetory-Backup-Hourly" `
      /tr "pwsh.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Users\votre-utilisateur\Downloads\subnetory-github\backend\scripts\backup-postgres.ps1 -Type hourly -BackupDir C:\Users\votre-utilisateur\Downloads\subnetory-backups" `
      /sc hourly `
      /mo 1 `
      /st 08:00 `
      /et 20:00 `
      /ru SYSTEM `
      /f

---

## Tache daily

Objectif : creer une sauvegarde quotidienne.

Retention par defaut du script : 31 fichiers.

Commande :

    schtasks /create `
      /tn "Subnetory-Backup-Daily" `
      /tr "pwsh.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Users\votre-utilisateur\Downloads\subnetory-github\backend\scripts\backup-postgres.ps1 -Type daily -BackupDir C:\Users\votre-utilisateur\Downloads\subnetory-backups" `
      /sc daily `
      /st 02:00 `
      /ru SYSTEM `
      /f

---

## Tache monthly

Objectif : creer une sauvegarde mensuelle le premier jour du mois.

Retention par defaut du script : 12 fichiers.

Commande :

    schtasks /create `
      /tn "Subnetory-Backup-Monthly" `
      /tr "pwsh.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Users\votre-utilisateur\Downloads\subnetory-github\backend\scripts\backup-postgres.ps1 -Type monthly -BackupDir C:\Users\votre-utilisateur\Downloads\subnetory-backups" `
      /sc monthly `
      /d 1 `
      /st 03:00 `
      /ru SYSTEM `
      /f

---

## Tache quarterly

Objectif : creer une sauvegarde trimestrielle.

Retention par defaut du script : 4 fichiers.

Commande :

    schtasks /create `
      /tn "Subnetory-Backup-Quarterly" `
      /tr "pwsh.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Users\votre-utilisateur\Downloads\subnetory-github\backend\scripts\backup-postgres.ps1 -Type quarterly -BackupDir C:\Users\votre-utilisateur\Downloads\subnetory-backups" `
      /sc monthly `
      /mo 3 `
      /d 1 `
      /st 04:00 `
      /ru SYSTEM `
      /f

---

## Tache de controle

Objectif : controler regulierement que des sauvegardes recentes existent.

Commande recommandee : controle quotidien a 07h30.

    schtasks /create `
      /tn "Subnetory-Backup-Check" `
      /tr "pwsh.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Users\votre-utilisateur\Downloads\subnetory-github\backend\scripts\check-postgres-backup.ps1 -BackupRoot C:\Users\votre-utilisateur\Downloads\subnetory-backups -WarningHours 30 -CriticalHours 48" `
      /sc daily `
      /st 07:30 `
      /ru SYSTEM `
      /f

Le script retourne :

- 0 : OK
- 1 : WARNING
- 2 : CRITICAL
- 3 : UNKNOWN

---

## Lister les taches Subnetory

    schtasks /query /tn "Subnetory-Backup-Hourly" /v /fo list
    schtasks /query /tn "Subnetory-Backup-Daily" /v /fo list
    schtasks /query /tn "Subnetory-Backup-Monthly" /v /fo list
    schtasks /query /tn "Subnetory-Backup-Quarterly" /v /fo list
    schtasks /query /tn "Subnetory-Backup-Check" /v /fo list

---

## Executer une tache manuellement

    schtasks /run /tn "Subnetory-Backup-Daily"
    schtasks /run /tn "Subnetory-Backup-Check"

Verifier ensuite :

    Get-ChildItem C:\Users\votre-utilisateur\Downloads\subnetory-backups -Recurse -Filter *.sql.gz | Sort-Object LastWriteTime -Descending | Select-Object -First 10 FullName, Length, LastWriteTime

---

## Desactiver une tache

    schtasks /change /tn "Subnetory-Backup-Hourly" /disable

Reactiver :

    schtasks /change /tn "Subnetory-Backup-Hourly" /enable

---

## Supprimer une tache

    schtasks /delete /tn "Subnetory-Backup-Hourly" /f
    schtasks /delete /tn "Subnetory-Backup-Daily" /f
    schtasks /delete /tn "Subnetory-Backup-Monthly" /f
    schtasks /delete /tn "Subnetory-Backup-Quarterly" /f
    schtasks /delete /tn "Subnetory-Backup-Check" /f

---

## Points de vigilance

- Ne jamais stocker les sauvegardes dans Git.
- Ne jamais commiter backend/.env.
- Ne jamais commiter un dossier backups/.
- Le compte SYSTEM doit pouvoir acceder au chemin du projet et au dossier backup.
- Si le backup est stocke sur un partage reseau, utiliser de preference un compte de service dedie plutot que SYSTEM.
- Docker Desktop doit etre disponible au moment de l'execution.
- Tester regulierement une restauration reelle sur un environnement de test.

---

## Validation minimale apres creation

Executer :

    schtasks /run /tn "Subnetory-Backup-Daily"

Puis verifier :

    .\scripts\check-postgres-backup.ps1 -BackupRoot C:\Users\votre-utilisateur\Downloads\subnetory-backups

Resultat attendu :

    Status               : OK
    Message              : Latest backup is recent.

---

## Historique

| Version | Sprint | Description |
|---|---|---|
| 1.1 | 2.20 | Mise a jour des retentions par defaut : hourly 168, daily 31, monthly 12, quarterly 4 |
| 1.0 | 2.18 | Documentation exploitation Windows des taches planifiees backup |

