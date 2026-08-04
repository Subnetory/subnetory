# Restore operations - PostgreSQL Docker

> **Phase 7 (audit 31/07/2026, complete le 01/08/2026)** : une interface de
> gestion des sauvegardes integree existe desormais dans l'application
> (`/admin/backup`, et `/api/v1/admin/backup` en API), avec historique
> complet, verification d'empreinte SHA-256 et sauvegarde de securite
> automatique avant toute restauration. Elle implemente les regles de
> securite ci-dessous directement dans l'IHM (confirmation par saisie du nom
> exact du fichier, jamais de restauration en un clic). Depuis le
> 01/08/2026, elle couvre aussi : import d'un fichier `.dump` externe
> (verifie via `pg_restore --list` avant acceptation, puis restaurable comme
> n'importe quelle autre sauvegarde), purge manuelle explicite de
> l'historique avant une date donnee (menage en masse) ou suppression fine
> d'une seule sauvegarde (`DELETE /api/v1/admin/backup/runs/{id}`, refusee
> si encore referencee par une restauration conservee ou encore en cours ;
> `?cascade=true` supprime aussi explicitement les restaurations liees,
> apres consultation de `GET .../linked-restores`), titre/commentaire
> optionnel par sauvegarde, et reconciliation automatique
> au demarrage de toute operation restee bloquee "En cours" suite a un
> arret brutal de l'application
> (`BackupExecutionService#reconcileOrphanedOperations`). Commandes API
> completes : `backend/docs/ADMIN_GUIDE.md` (section "Sauvegardes"). Ce
> document reste la reference pour les operations manuelles via les scripts
> PowerShell historiques (`backup-postgres.ps1` / `restore-postgres.ps1`),
> utiles en secours si l'application elle-meme est indisponible.
>
> Chiffrement au repos (01/08/2026, active par defaut pour toute nouvelle
> installation Docker Compose) : AES-256-GCM + HMAC-SHA256, voir
> `BACKUP_ENCRYPTION.md`. Quand il est
> actif, un fichier `.dump.enc` importe est dechiffre puis verifie
> (HMAC + tag GCM + `pg_restore --list`) avant acceptation — une
> modification survenue apres l'export est donc bien detectee dans ce cas.
> Sans chiffrement actif, un `.dump` en clair reimporte n'est toujours
> verifie que sur son format, pas cryptographiquement.
>
> **Mode maintenance applicatif et invalidation post-restauration (correctif
> securite MOYENNE, audit 04/08/2026).** Jusqu'ici, "l'arret ou l'absence
> d'ecriture applicative pendant l'operation" (voir "Principe general"
> ci-dessous) n'etait impose que par cette documentation, jamais par le
> logiciel — l'application continuait d'accepter des mutations metier
> pendant un `pg_restore` en cours. Pour le moteur integre uniquement
> (`BackupExecutionService#restore`) : `RestoreMaintenanceGate` rejette
> desormais (HTTP 503, `Retry-After`) toute requete de mutation (methode
> autre que GET/HEAD/OPTIONS, hors authentification) le temps du
> `pg_restore`. Apres un succes, tous les jetons JWT sont invalides et
> toutes les sessions Web sont drainees, pour ne pas laisser un etat restaure
> plus ancien de `user_token_invalidations` (table non exclue du dump,
> contrairement a `backup_runs`/`backup_restores`) reautoriser un jeton
> revoque depuis, ou une session Web deja ouverte garder les autorites
> chargees avant la restauration. Les scripts PowerShell historiques
> (`restore-postgres.ps1`) restent manuels : l'arret de l'application pendant
> leur execution reste une precaution operationnelle a la charge de
> l'operateur, comme decrit plus bas.

## Objectif

Ce document decrit les operations de restauration PostgreSQL Docker pour Subnetory.

L'objectif est de fournir une procedure claire, prudente et reproductible afin de limiter les risques de restauration accidentelle, de perte de donnees ou de confusion entre environnement local, dev et production.

## Principe general

Une restauration remplace l'etat courant de la base par le contenu d'un dump de sauvegarde.

Elle doit donc toujours etre consideree comme une operation sensible.

Avant toute restauration, il faut confirmer :

- l'environnement cible ;
- le fichier de backup utilise ;
- l'etat courant de la base ;
- la disponibilite d'une sauvegarde recente avant restauration ;
- l'arret ou l'absence d'ecriture applicative pendant l'operation.

## Environnements supportes

### Local ou developpement

La restauration locale/dev est supportee pour :

- tester un backup ;
- valider une procedure de reprise ;
- rejouer un scenario de restauration ;
- restaurer une base de travail locale.

### Production

La restauration production est possible mais doit rester exceptionnelle.

Elle ne doit jamais etre lancee sans validation explicite, sans sauvegarde de securite prealable et sans fenetre d'intervention identifiee.

## Regles de securite

- Ne jamais restaurer un dump sans verifier son chemin complet.
- Ne jamais restaurer depuis un dossier backups/ ajoute au repository.
- Ne jamais commiter de dump SQL, SQL.GZ ou archive de sauvegarde.
- Ne jamais commiter de fichier .env reel.
- Ne jamais executer une restauration production depuis un terminal ambigu.
- Ne jamais restaurer sans avoir identifie le conteneur PostgreSQL cible.
- Ne jamais restaurer sans backup de securite pre-restauration.

## Checklist pre-restauration

Avant de restaurer, verifier :

- branche Git courante ;
- environnement cible ;
- conteneurs Docker actifs ;
- nom du service PostgreSQL Docker ;
- nom de la base cible ;
- utilisateur PostgreSQL ;
- chemin complet du fichier de backup ;
- date du backup ;
- presence d'une sauvegarde de securite recente ;
- absence d'ecriture applicative pendant l'operation.

Commandes utiles :

git status
docker ps
docker compose ps

## Backup de securite avant restauration

Avant toute restauration sensible, creer un backup de securite de l'etat courant.

Utiliser le script de backup existant du projet lorsque possible.

Exemple depuis backend :

.\scripts\backup-postgres.ps1

Le fichier genere doit etre conserve hors repository.

## Selection du fichier de backup

Le fichier de backup doit etre choisi explicitement.

Verifier :

- son nom ;
- son extension ;
- sa date ;
- sa taille ;
- son chemin complet ;
- qu'il ne se trouve pas dans le repository Git.

Commandes utiles :

Get-Item "C:\chemin\vers\backup.sql.gz"
Get-FileHash "C:\chemin\vers\backup.sql.gz" -Algorithm SHA256

## Restauration locale ou dev

Procedure recommandee :

1. Se placer dans le dossier backend.
2. Verifier les conteneurs Docker.
3. Identifier le fichier de backup.
4. Creer un backup de securite de l'etat courant.
5. Executer le script de restauration existant.
6. Redemarrer l'application si necessaire.
7. Lancer les controles post-restauration.

Exemple :

cd backend
docker compose ps
.\scripts\backup-postgres.ps1
.\scripts\restore-postgres.ps1 -BackupFile "C:\chemin\vers\backup.sql.gz"

## Controles post-restauration

Apres restauration, verifier :

- demarrage correct de PostgreSQL ;
- demarrage correct de l'application ;
- absence d'erreur Docker evidente ;
- acces a l'interface web ;
- authentification ;
- presence des donnees attendues ;
- coherence des subnets, VLANs et adresses IP ;
- execution des tests Maven.

Commandes utiles :

docker compose ps
docker compose logs --tail 100
cd backend
.\mvnw.cmd test

## Validation Maven

La validation finale attendue apres ce sprint reste :

Tests run: 486
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS

## Cas d'echec

Si la restauration echoue :

- ne pas relancer en boucle sans comprendre l'erreur ;
- conserver les logs ;
- verifier le fichier de backup ;
- verifier les identifiants PostgreSQL ;
- verifier que le conteneur cible est le bon ;
- verifier l'espace disque ;
- restaurer le backup de securite pre-restauration si necessaire.

## Notes d'exploitation

Une restauration doit etre documentee dans un compte rendu d'intervention avec :

- date et heure ;
- environnement cible ;
- backup utilise ;
- hash SHA256 du backup si disponible ;
- raison de la restauration ;
- resultat ;
- controles post-restauration realises.

## Limites

Ce document ne modifie pas les scripts existants.

Il complete les procedures de backup, monitoring, restore drill et scheduling deja documentees dans les sprints precedents.

## Fichiers lies

- backend/docs/BACKUP_STRATEGY.md
- backend/docs/BACKUP_SCHEDULING_WINDOWS.md
