# Chiffrement des sauvegardes (audit 01/08/2026, backlog #13)

> Activé par défaut pour toute nouvelle installation Docker Compose depuis
> le 01/08/2026 : `scripts/init-compose.ps1` / `.sh` génèrent automatiquement
> le secret `subnetory_backup_encryption_key`, déjà déclaré dans
> `docker-compose.yml`/`docker-compose.prod.yml`. Pour désactiver
> explicitement cette fonctionnalité, régénérer les secrets avec
> `-WithoutBackupEncryption` (PowerShell) / `--without-backup-encryption`
> (Bash) et retirer le secret des fichiers Compose — voir « Où vit la clé »
> ci-dessous. Aucune sauvegarde existante n'est affectée tant que
> `subnetory.backup.encryption.key` (variable d'environnement
> `SUBNETORY_BACKUP_ENCRYPTION_KEY`) reste vide.

## Ce que ça protège

Sans chiffrement, un fichier `.dump` sur disque (volume Docker, PVC
Kubernetes, ou copie téléchargée) est lisible par quiconque y a accès —
`pg_restore --list` en révèle même le schéma sans mot de passe. Le
chiffrement au repos protège contre l'exfiltration d'un fichier de
sauvegarde (vol du volume, accès disque non autorisé, sauvegarde
téléchargée puis égarée), pas contre un accès direct à la base de données
elle-même ni contre un attaquant qui a déjà les droits d'administration de
l'application.

## Conception

- **AES-256-GCM** pour la confidentialité et une première authentification
  (tag inclus automatiquement dans le flux chiffré).
- **HMAC-SHA256** distinct, calculé sur l'intégralité du fichier produit
  (en-tête, sel, IV et texte chiffré), avec une clé dérivée séparément de la
  clé AES — jamais la même clé pour deux primitives différentes. Permet de
  rejeter un fichier altéré ou une mauvaise clé **avant** toute tentative de
  déchiffrement, sans exposer le moindre octet en clair issu d'un fichier
  non authentifié.
- **PBKDF2WithHmacSHA256**, 210 000 itérations (recommandation OWASP 2023),
  pour dériver les deux clés à partir de la passphrase configurée. Le sel
  est généré aléatoirement **par fichier** (16 octets) et stocké en clair
  dans l'en-tête — ce n'est pas un secret, seule la passphrase l'est.
- Format sur disque (extension `.dump.enc`) :

  ```
  MAGIC "SNBKENC1" (8 octets)
  sel PBKDF2       (16 octets)
  IV GCM           (12 octets)
  texte chiffré AES-256-GCM (tag de 16 octets inclus à la fin)
  HMAC-SHA256 sur tout ce qui précède (32 octets)
  ```

- Chiffrement en flux à l'écriture (jamais le dump entier en mémoire).
  Déchiffrement en deux passes en flux sur le fichier source : vérification
  du HMAC d'abord, déchiffrement ensuite — jamais le texte chiffré entier en
  mémoire non plus.

Voir `dev.subnetory.backup.BackupExecutionService` (méthodes `encryptFile`,
`decryptFile`, `deriveKeys`) pour l'implémentation.

## Où vit la clé

**Jamais en base de données, jamais modifiable depuis l'IHM.** Même
conclusion que l'étude de faisabilité sur la rotation du mot de passe
PostgreSQL (`DB_PASSWORD_ROTATION_FEASIBILITY.md`) : stocker la clé à côté
des fichiers qu'elle protège annulerait l'intérêt du chiffrement, et l'IHM
tournant dans le même conteneur que les secrets read-only ne peut de toute
façon pas persister un secret modifié de façon fiable.

La clé est lue depuis `SUBNETORY_BACKUP_ENCRYPTION_KEY` :

- **Docker Compose** (recommandé) : fichier `./secrets/subnetory_backup_encryption_key`
  monté via le mécanisme `configtree` existant, déclaré par défaut dans
  `docker-compose.yml`/`docker-compose.prod.yml` (dans les `secrets:` du
  service `app` et dans les `secrets:` de premier niveau). Ne jamais
  committer ce fichier. Pour désactiver explicitement cette fonctionnalité :
  régénérer les secrets avec `-WithoutBackupEncryption` /
  `--without-backup-encryption`, puis recommenter les deux blocs
  correspondants dans le(s) fichier(s) Compose utilisés. Génération et
  protection du fichier (permissions restrictives Windows/Linux) : voir
  « Secret généré par défaut : chiffrement des sauvegardes » et « Protection
  des secrets sur le poste » dans `INSTALL_DOCKER_COMPOSE.md` — inclut la
  procédure pour une nouvelle instance qui doit rester capable de lire des
  sauvegardes chiffrées par une autre instance (la valeur doit alors être
  identique, pas régénérée).
- **Kubernetes** : même principe que les autres secrets de l'application —
  un `Secret` monté en lecture seule, exposé via `spring.config.import:
  optional:configtree:/run/secrets/`. Câblé dans le chart Helm (clé
  `backup-encryption-key` du Secret `existingSecretRuntime`, activable via
  `secrets.backupEncryptionKeyEnabled: true`), désactivé par défaut pour ne
  pas casser un Secret existant — voir `backend/docs/KUBERNETES_HELM_READINESS.md`.
- **Variable d'environnement brute** (dev/test uniquement, jamais en
  production — visible via `docker inspect`/l'environnement du processus) :
  définir directement `SUBNETORY_BACKUP_ENCRYPTION_KEY`.

## ⚠️ Ce n'est pas une rotation de clé

Changer ou perdre la valeur de `subnetory.backup.encryption.key` rend
**définitivement illisibles** toutes les sauvegardes déjà chiffrées avec
l'ancienne valeur — il n'existe aucun mécanisme de réchiffrement en masse.

Le secret est généré automatiquement (32 octets aléatoires) par
`scripts/init-compose.ps1`/`.sh` au moment de l'installation — ce n'est plus
une passphrase choisie manuellement. **Juste après cette génération**, avant
d'aller plus loin :

- Copier la valeur de `backend/secrets/subnetory_backup_encryption_key`
  dans un coffre personnel ou d'équipe (gestionnaire de mots de passe),
  au même titre que le mot de passe PostgreSQL — voir le point de vigilance
  « sauvegarder ces secrets avant d'aller plus loin » dans
  `INSTALL_DOCKER_COMPOSE.md`. C'est le seul exemplaire : il n'est ni
  committé, ni sauvegardé ailleurs par le projet.
- Ne jamais la régénérer une fois des sauvegardes chiffrées produites, sauf
  à accepter de perdre l'accès à celles déjà sur disque (elles restent
  visibles dans l'historique, mais leur restauration échouera avec une
  erreur explicite de vérification HMAC/GCM).

## Comportement pratique

- **Nouvelles sauvegardes** (planifiées, manuelles, de sécurité
  pré-restauration) : chiffrées automatiquement dès que la clé est
  configurée, sans action supplémentaire. Colonne `encrypted` sur
  `backup_runs`, badge « 🔒 Chiffré » dans l'historique (`/admin/backup`),
  champ `encrypted` dans `GET /api/v1/admin/backup/runs`.
- **Sauvegardes existantes non chiffrées** : restent valides et
  restaurables telles quelles, indéfiniment — le chiffrement ne s'applique
  qu'aux fichiers produits *après* activation, jamais rétroactivement.
- **Restauration** : totalement transparente, qu'une sauvegarde soit
  chiffrée ou non — `BackupExecutionService.restore` déchiffre vers un
  fichier temporaire juste avant `pg_restore`, systématiquement supprimé
  ensuite (y compris en cas d'échec).
- **Import d'un fichier `.dump`** (`POST /admin/backup/import`) : un fichier
  déjà chiffré par une autre instance Subnetory utilisant la **même** clé
  est accepté et validé normalement ; sans la bonne clé configurée, il est
  refusé explicitement (impossible de le valider en aveugle). Un fichier en
  clair importé alors que le chiffrement est actif est automatiquement
  chiffré avant stockage, pour rester cohérent avec le reste de
  l'historique.
- **Téléchargement** (`GET /admin/backup/runs/{id}/download`) : renvoie le
  fichier tel qu'il est sur disque, donc chiffré s'il l'est — utile
  précisément pour transférer une sauvegarde vers un stockage externe sans
  l'exposer en clair. Il ne peut être réimporté/restauré que sur une
  instance connaissant la même clé.

## Limites connues (volontairement hors scope V1)

- Pas de réchiffrement en masse ni de rotation de clé assistée.
- Pas de gestion multi-clé (une seule clé active à la fois).
- Le nom de fichier, la taille et les métadonnées en base (date, statut,
  label) restent en clair — seul le contenu du dump est protégé.
