# Subnetory - Guide administrateur

## Journal d'audit d'authentification

Le journal d'audit d'authentification permet a un administrateur de consulter les evenements lies aux connexions, aux changements de mot de passe et aux actions de securite.

Chemin web :

* /admin/audit-log

Evenements actuellement traces :

* LOGIN_SUCCESS : connexion reussie
* LOGIN_FAILURE : echec de connexion
* LOGIN_LOCKED : compte verrouille apres echecs repetes
* PASSWORD_CHANGE : changement de mot de passe par l'utilisateur
* ADMIN_PASSWORD_RESET : reinitialisation de mot de passe par un administrateur
* TOKEN_REVOKED : revocation d'un token JWT API
* TOKENS_INVALIDATED : invalidation globale des tokens JWT API d'un utilisateur
* CONTEXT_ACCESS_DENIED, USER_CREATED, USER_CONTEXTS_UPDATED, MFA_ENABLED,
  MFA_DISABLED, MFA_RECOVERY_CODES_REGENERATED, MFA_CHALLENGE_FAILED,
  MFA_DISABLED_BY_ADMIN
* USER_ROLES_UPDATED, USER_ENABLED, USER_DISABLED : modification des rôles,
  activation/désactivation d'un compte par un administrateur.
* USER_DELETED (03/08/2026) : suppression définitive d'un compte utilisateur
  par un administrateur.

Traçabilité étendue (01/08/2026, backlog #27) — mêmes filtres/export/purge
que ci-dessus, colonne `message` en texte libre pour les détails (id,
valeur) faute de champ structuré dédié dans le schéma :

* BACKUP_TRIGGERED, BACKUP_IMPORTED, BACKUP_RESTORED, BACKUP_DELETED,
  BACKUP_PURGED, BACKUP_SETTINGS_UPDATED — toutes les actions de
  `/admin/backup` qui modifient un état (déclenchement, import,
  restauration, suppression simple/cascade, purge, configuration).
* ADDRESS_CREATED, ADDRESS_DELETED, VLAN_CREATED, VLAN_DELETED,
  CONTEXT_CREATED, CONTEXT_DELETED, SITE_CREATED, SITE_DELETED,
  SUBNET_CREATED, SUBNET_DELETED — création/suppression des ressources
  réseau principales.

## Acces

La page est reservee aux utilisateurs ayant le role ADMIN.

Un utilisateur non admin ne doit pas pouvoir acceder a la page d'audit.

## Filtres disponibles

La page permet de filtrer les evenements avec deux criteres :

* Recherche utilisateur : recherche sur username et targetUsername
* Type d'evenement : filtre sur eventType

Si un type d'evenement est selectionne, il est prioritaire sur la recherche texte.

## Pagination

La page /admin/audit-log est paginee.

Parametre :

* page : numero de page, en commencant a 0

Comportement attendu :

* les evenements les plus recents sont affiches en premier
* le tri est fait par createdAt DESC
* une valeur negative de page est normalisee a 0
* la taille de page actuelle est de 50 entrees

Exemples :

* /admin/audit-log
* /admin/audit-log?page=1
* /admin/audit-log?q=admin
* /admin/audit-log?eventType=LOGIN_FAILURE

## Export CSV

Un export CSV est disponible depuis la page d'audit.

Chemin web :

* /admin/audit-log/export.csv

L'export respecte les filtres q et eventType lorsque ces parametres sont fournis.

Exemples :

* /admin/audit-log/export.csv
* /admin/audit-log/export.csv?q=admin
* /admin/audit-log/export.csv?eventType=LOGIN_FAILURE

Colonnes exportees :

* createdAt
* eventType
* username
* targetUsername
* ipAddress
* success
* message
* userAgent

## Securite CSV

L'export CSV protege contre la CSV injection.

Les valeurs commencant par les caracteres suivants sont prefixees par une apostrophe :

- `=`
- `+`
- `-`
- `@`
- tabulation
- retour ligne
- retour chariot

Les guillemets sont doubles et chaque champ est encadre par des guillemets.

## Retention et purge

Le journal d'audit peut etre purge automatiquement selon une duree de retention configurable.

Configuration applicative :

* subnetory.audit.retention.enabled
* subnetory.audit.retention.days
* subnetory.audit.retention.cron

Variables d'environnement equivalentes :

* SUBNETORY_AUDIT_RETENTION_ENABLED
* SUBNETORY_AUDIT_RETENTION_DAYS
* SUBNETORY_AUDIT_RETENTION_CRON

Valeurs par defaut :

* enabled : true
* days : 90
* cron : 0 30 2 * * *

Le scheduler purge les entrees dont createdAt est plus ancien que la date limite calculee.

En profil de test, la retention est desactivee pour eviter les effets de bord dans les tests d'integration.

### Purge manuelle immediate (01/08/2026, backlog #27)

En complement de la purge automatique planifiee ci-dessus, une purge
immediate avant une date donnee est disponible depuis la page
`/admin/audit-log` (bouton "Purger definitivement", meme principe que la
purge d'historique des sauvegardes) et via l'API :

```http
POST /api/v1/admin/audit-log/purge
Content-Type: application/json

{ "beforeDate": "2026-01-01" }
```

Reponse : `{ "deletedCount": 42 }`. Suppression definitive et irreversible,
reservee a ADMIN.

## Bonnes pratiques

* conserver la retention active en production
* augmenter la retention si le besoin d'audit l'exige
* exporter les journaux avant une purge manuelle ou une operation sensible
* ne pas exposer les exports CSV a des utilisateurs non administrateurs
* surveiller la taille de la table auth_audit_log si l'application est fortement utilisee

## Administration des comptes utilisateurs et des roles

L'ecran `/admin/users` liste les comptes utilisateurs et permet de consulter leur fiche detail, de modifier leurs roles, de les activer ou desactiver, et de les supprimer definitivement. Cet ecran est reserve a ROLE_ADMIN, avec CSRF obligatoire sur toute action de mutation.

Pour eviter qu'un administrateur se retrouve accidentellement sans acces a l'administration, les actions suivantes sont refusees et signalees par un message d'erreur explicite (sans detail technique) :

| Action refusee | Condition de refus |
|---|---|
| Desactiver son propre compte | toujours refuse |
| Desactiver le dernier compte ADMIN actif | refuse si c'est le dernier ADMIN actif restant |
| Retirer le role ADMIN au dernier compte ADMIN actif | refuse si c'est le dernier ADMIN actif restant |
| Enregistrer une fiche utilisateur sans aucun role | toujours refuse |
| Supprimer son propre compte | toujours refuse |
| Supprimer le dernier compte ADMIN actif | refuse si c'est le dernier ADMIN actif restant |

Ces controles ne portent que sur la desactivation d'un compte, le retrait de role et la suppression : activer un compte ou lui ajouter un role ne declenche aucune verification particuliere.

Maintenir au moins deux comptes ROLE_ADMIN actifs evite de dependre d'un compte unique et limite le risque de blocage total de l'administration.

### Suppression definitive d'un compte (03/08/2026)

Jusqu'ici, seule la desactivation (`setEnabled`) etait possible : aucune fonctionnalite ne permettait de supprimer definitivement un compte utilisateur. Disponible depuis la fiche compte (`/admin/users/{id}`, section « Zone dangereuse ») ou via `DELETE /api/v1/admin/users/{id}`.

C'est une suppression physique (hard delete), pas une suppression logique :

* les roles attribues (`user_roles`), les contextes autorises (`user_context_access`) et les codes de recuperation MFA (`mfa_recovery_codes`) du compte sont supprimes automatiquement par la base (contrainte `ON DELETE CASCADE`) ;
* le journal d'audit (`auth_audit_log`), l'historique des sauvegardes (`backup_runs`, `backup_restores`) et les jetons revoques (`revoked_tokens`) referencent l'utilisateur par son nom en texte libre, jamais par une cle etrangere vers `users(id)` : ces entrees restent intactes apres la suppression du compte, la tracabilite n'est pas perdue ;
* l'evenement `USER_DELETED` est trace dans le journal d'audit (administrateur, compte cible, IP, user-agent).

Avant de supprimer un compte, envisager la desactivation si l'historique des actions de ce compte doit rester consultable via sa fiche (une fiche supprimee n'est plus consultable, contrairement a un compte desactive).

## Sauvegardes

Deux mecanismes independants existent. Ne pas les activer ensemble sur la meme base.

### Scripts PowerShell historiques (hors application)

Documentes dans un livrable dedie :

* backend/docs/BACKUP_STRATEGY.md
* backend/scripts/backup-postgres.ps1
* backend/scripts/restore-postgres.ps1

Rappel : ne pas utiliser robocopy. Privilegier PowerShell, Copy-Item, pg_dump, pg_restore et le Planificateur de taches Windows.

### Moteur de sauvegarde integre (Phase 7, audit du 31/07/2026)

`pg_dump`/`pg_restore` lances par l'application elle-meme (`BackupExecutionService`), identique en Docker Compose et Kubernetes. IHM : `/admin/backup`. API REST, parite complete avec l'IHM, prefixe `/api/v1/admin/backup`, reserve a `ROLE_ADMIN` ou `ROLE_BACKUP` (jeton JWT Bearer requis pour tous les appels ci-dessous, voir `POST /api/v1/auth/token`). `ROLE_BACKUP` (audit 01/08/2026) donne acces aux sauvegardes sans le reste de l'administration — utile pour un compte de service ou un operateur dedie.

Toute sauvegarde/restauration restee au statut "En cours" suite a un arret brutal de l'application (rebuild, crash) est automatiquement marquee en echec au demarrage suivant — aucune ligne ne reste bloquee indefiniment (`BackupExecutionService#reconcileOrphanedOperations`).

Chiffrement au repos (audit 01/08/2026, active par defaut pour toute nouvelle installation Docker Compose) : AES-256-GCM + HMAC-SHA256, cle fournie via `SUBNETORY_BACKUP_ENCRYPTION_KEY` (jamais en base, jamais modifiable depuis l'IHM), generee automatiquement par `scripts/init-compose.ps1`/`.sh` (option `-WithoutBackupEncryption`/`--without-backup-encryption` pour desactiver explicitement). Toute nouvelle sauvegarde est chiffree automatiquement des que la cle est configuree (badge "Chiffre" dans l'historique, champ `encrypted` dans l'API) ; les sauvegardes existantes non chiffrees restent utilisables normalement. Voir `BACKUP_ENCRYPTION.md` — changer la cle rend illisibles les sauvegardes deja chiffrees, ce n'est pas une rotation.

```http
# Configuration et etat courant (planification, derniere sauvegarde reussie, espace utilise)
GET /api/v1/admin/backup

# Modifier la configuration
PUT /api/v1/admin/backup
Content-Type: application/json

{
  "enabled": true,
  "cronExpression": "0 0 2 * * *",
  "retentionCount": 14
}

# Historique des sauvegardes (pagine)
GET /api/v1/admin/backup/runs?page=0&size=20

# Declencher une sauvegarde immediate, hors planification.
# "label" est optionnel : titre/commentaire libre pour retrouver facilement
# cette sauvegarde dans l'historique.
POST /api/v1/admin/backup/trigger
Content-Type: application/json

{
  "label": "avant migration V19"
}

# Telecharger le fichier d'une sauvegarde
GET /api/v1/admin/backup/runs/{id}/download

# Importer un fichier de sauvegarde externe (.dump, ex: telecharge depuis un
# autre serveur). Le format est verifie (pg_restore --list) avant acceptation ;
# la sauvegarde apparait ensuite dans l'historique et se restaure comme
# n'importe quelle autre (POST /restore ci-dessous, memes protections).
POST /api/v1/admin/backup/import
Content-Type: multipart/form-data

# Historique des restaurations (pagine)
GET /api/v1/admin/backup/restores?page=0&size=20

# Restaurer la base a partir d'une sauvegarde — jamais un simple appel :
# confirmationText doit correspondre exactement au nom du fichier restaure
# (voir RESTORE_OPERATIONS.md). Une sauvegarde de securite automatique est
# prise juste avant.
POST /api/v1/admin/backup/restore
Content-Type: application/json

{
  "backupRunId": 42,
  "confirmationText": "subnetory-20260731-020000.dump"
}

# Purger definitivement l'historique (sauvegardes + restaurations, et leurs
# fichiers .dump) strictement anterieur a une date. Une sauvegarde encore
# referencee par une restauration conservee (plus recente que la coupure)
# n'est jamais supprimee, meme si elle est elle-meme plus ancienne.
POST /api/v1/admin/backup/purge
Content-Type: application/json

{
  "beforeDate": "2026-01-01"
}

# Supprimer une seule sauvegarde de l'historique — granularite plus fine que
# la purge en masse ci-dessus. Refuse (409) si la sauvegarde est encore
# referencee par une restauration conservee, ou encore en cours (RUNNING).
DELETE /api/v1/admin/backup/runs/{id}

# Lister les restaurations encore liees a une sauvegarde (comme source
# restauree ou comme sauvegarde de securite pre-restauration), pour savoir
# precisement ce qu'un DELETE en cascade supprimerait.
GET /api/v1/admin/backup/runs/{id}/linked-restores

# Supprimer une sauvegarde ET les restaurations qui la referencent encore —
# echappatoire explicite au refus ci-dessus, plutot que de devoir passer par
# la purge en masse.
DELETE /api/v1/admin/backup/runs/{id}?cascade=true
```

Exemple `curl` (obtention du jeton puis declenchement d'une sauvegarde) :

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<mot-de-passe>"}' | jq -r .accessToken)

curl -s -X POST http://localhost:8080/api/v1/admin/backup/trigger \
  -H "Authorization: Bearer $TOKEN"
```

Reference complete des endpoints (schemas de requete/reponse) : Swagger UI `/swagger-ui.html` ou spec `/v3/api-docs` (voir aussi `backend/docs/API_FIRST_PARITY.md`).

Deploiement Kubernetes : chart `charts/subnetory`, cle `backupApp` dans `values.yaml` (PVC dediee, ConfigMap). La combinaison `backup.enabled=true` et `backupApp.enabled=true` est rejetee au rendu du chart (`helm template` echoue volontairement).

## Diagnostic d'integrite context_id/site_id (04/08/2026)

Avant les correctifs v0.8.1 (deplacement d'un Site ou d'un VLAN) et v0.8.2 (deplacement d'un Subnet), rien n'empechait de changer le contexte d'un Site, le site d'un VLAN, ou le contexte/site/CIDR d'un Subnet alors que des sous-reseaux ou des adresses existaient encore en dessous. `Subnet` et `Address` stockent leur propre `context_id`/`site_id`, jamais resynchronise automatiquement — ces enfants gardaient alors leurs anciennes valeurs. Depuis ces correctifs, ce type de deplacement est bloque tant que des enfants existent (409), donc aucune nouvelle incoherence ne peut apparaitre ; une instance mise a jour depuis une version anterieure peut neanmoins avoir des incoherences residuelles en base.

`backend/scripts/check-context-integrity.ps1` diagnostique ces six categories (rapport de lecture seule par defaut) :

```powershell
cd backend/scripts
.\check-context-integrity.ps1
```

Sur une instance qui n'a jamais transite par ces bugs, aucune ligne ne doit remonter. `-Fix` corrige automatiquement les categories sans ambiguite (contexte d'un Subnet realigne sur son Site/VLAN, contexte/site d'une Address realigne sur son Subnet — exactement ce que fait deja le code applicatif a chaque ecriture) :

```powershell
.\check-context-integrity.ps1 -Fix
```

Demande confirmation (taper `OUI`) avant d'ecrire ; `-Fix -Force` saute la confirmation. Une sauvegarde prealable (`backup-postgres.ps1`) est recommandee avant toute correction sur une instance de production. Deux categories (incoherence entre un Subnet et son parent, contexte ou containment CIDR) restent affichees mais jamais corrigees automatiquement : l'ambiguite sur laquelle des deux entites est en tort demande un arbitrage manuel. Detail des requetes SQL : `backend/scripts/check-context-integrity.sql` et `fix-context-integrity.sql`.

## Revocation JWT API

Depuis le Sprint 2.26, les tokens JWT de l'API REST sont revocables avant leur expiration naturelle.

Endpoint API :

* POST /api/v1/auth/logout

Utilisation :

```http
POST /api/v1/auth/logout
Authorization: Bearer <access_token>
```

Comportement :

* token valide avec claim `jti` : le token est ajoute a la table `revoked_tokens` et la reponse est `204 No Content` ;
* reutilisation du meme token sur un endpoint API : `401 Unauthorized` ;
* double appel logout avec le meme token : `204 No Content` ;
* token historique sans claim `jti` : `200 OK` avec un corps informatif ; le token expirera naturellement.

La revocation concerne uniquement les tokens JWT de l'API REST. La GUI Web conserve son logout de session classique.

### Audit

Un logout API effectif ajoute un evenement d'audit :

* TOKEN_REVOKED

Les evenements sont visibles dans le journal d'audit d'authentification et exportables via le CSV existant.

### Purge des tokens revoques expires

La table `revoked_tokens` est une denylist temporaire. Une entree n'est plus utile une fois le token expire.

Configuration applicative :

* subnetory.jwt.revocation.purge.enabled
* subnetory.jwt.revocation.purge.cron

Variables d'environnement equivalentes :

* SUBNETORY_JWT_REVOCATION_PURGE_ENABLED
* SUBNETORY_JWT_REVOCATION_PURGE_CRON

Valeurs par defaut :

* enabled : true
* cron : 0 30 3 * * *

Le scheduler purge les entrees dont `expires_at` est passe. Il ne se declenche pas au demarrage ; il s'execute uniquement selon le cron configure.

En profil de test, la purge planifiee est desactivee pour eviter les effets de bord dans les tests d'integration.

## Invalidation globale des tokens API par utilisateur

Depuis le Sprint 2.27, Subnetory permet d'invalider globalement tous les tokens JWT API deja emis pour un utilisateur.

Ce mecanisme complete la revocation unitaire par `jti` ajoutee au Sprint 2.26.

Objectif :

* invalider tous les tokens API existants d'un utilisateur ;
* conserver valides les nouveaux tokens emis apres l'action ;
* couvrir les cas de changement de mot de passe, de reinitialisation administrateur et d'action explicite de securite.

### Principe technique

L'invalidation globale repose sur un seuil `not_before` stocke dans la table :

```text
user_token_invalidations
```

Tout token JWT dont le claim `iat` est anterieur au seuil `not_before` de son sujet est rejete avec :

```text
401 Unauthorized
```

Si une entree d'invalidation existe pour un utilisateur et qu'un token ne contient pas de claim `iat`, le token est rejete en fail-closed.

La table n'a pas de cle etrangere vers `users`, afin de rester compatible avec des sujets JWT externes ou LDAP.

### Actions disponibles

Endpoint API pour l'utilisateur courant :

```http
POST /api/v1/auth/logout-all
Authorization: Bearer <access_token>
```

Effet :

* invalide tous les tokens API JWT du sujet courant ;
* le token utilise pour appeler l'endpoint devient lui-meme invalide ;
* un nouvel appel avec ce meme token retourne `401 Unauthorized`.

Action administrateur GUI :

```text
POST /admin/users/{id}/invalidate-tokens
```

Effet :

* invalide tous les tokens API JWT deja emis pour l'utilisateur cible ;
* les nouveaux tokens emis apres l'action restent valides ;
* l'action necessite le role ADMIN et une requete POST protegee par CSRF.

### Hooks automatiques

L'invalidation globale est egalement declenchee automatiquement lors des operations suivantes :

* changement de mot de passe par l'utilisateur ;
* reinitialisation du mot de passe par un administrateur.

Ces hooks permettent d'eviter qu'un ancien token API reste utilisable apres une modification sensible du secret d'authentification.

### Audit

Chaque invalidation globale ajoute un evenement d'audit :

* TOKENS_INVALIDATED

Les evenements sont visibles dans le journal d'audit d'authentification et exportables via le CSV existant.

### Comportement attendu

* un token emis avant le seuil `not_before` est rejete ;
* un token emis apres le seuil `not_before` reste accepte ;
* un token deja revoque par `jti` et egalement anterieur au seuil `not_before` est rejete proprement avec `401 Unauthorized` ;
* aucune erreur serveur ne doit etre produite dans ce cas combine.

### Limites connues

La precision du claim `iat` est a la seconde.

Un token emis exactement a la meme seconde que le seuil `not_before` peut rester accepte si `iat == not_before`.

Important : cette invalidation concerne uniquement les tokens API JWT. Elle ne deconnecte pas les sessions Web GUI deja ouvertes.

## Authentification a deux facteurs (MFA)

Depuis le Sprint 2.37, chaque compte local peut activer un second facteur TOTP (Google Authenticator, Authy, etc.) depuis sa page de profil (`/profile`). Le MFA est optionnel et jamais impose globalement : chaque utilisateur l'active ou non pour son propre compte. Les comptes LDAP ne sont pas concernes.

A l'activation, 10 codes de recuperation a usage unique sont generes et affiches une seule fois. Chaque code peut remplacer le code TOTP, aussi bien pour la connexion (Web et API) que pour la desactivation du MFA ou la regeneration des codes. Une fois utilise, un code de recuperation est marque et ne peut plus servir.

Le secret TOTP est chiffre en base (AES/GCM, `SecretCipherService`) avec la cle `subnetory.security.encryption-key` (secret Docker/K8s `subnetory_encryption_key`, voir `INSTALL_DOCKER_COMPOSE.md`) — la meme cle chiffre aussi le mot de passe de bind LDAP. Cette cle est deliberement separee du secret JWT (`subnetory.jwt.secret`) depuis l'audit du 02/08/2026 : les deux ont des roles distincts (signature des jetons vs chiffrement au repos), et melanger les deux rendait une rotation JWT inutilement destructrice pour les secrets stockes. Si `subnetory.security.encryption-key` n'est pas configuree, l'application retombe automatiquement sur `subnetory.jwt.secret` (avec un avertissement au demarrage) : fonctionnel mais non recommande.

Cote connexion :

* Web : apres identifiants corrects, redirection vers `/login/mfa` tant que le second facteur n'est pas verifie pour la session ;
* API : `POST /api/v1/auth/token` accepte un champ optionnel `totpCode` ; si le compte a le MFA actif et que le code est absent ou invalide, la reponse est `401` avec un code `MFA_REQUIRED` ou `MFA_INVALID` ;
* les tentatives de code TOTP/recuperation sont soumises au meme rate limiting par IP que le mot de passe (`LoginRateLimiter`) ;
* chaque echec de defi MFA est audite sous l'evenement `MFA_CHALLENGE_FAILED`, distinct de `LOGIN_FAILURE`.

### Recuperation en cas de perte du mot de passe ET du MFA

Si un compte perd l'acces a son mot de passe et a son application d'authentification (et que les codes de recuperation n'ont pas ete conserves), aucune recuperation en libre-service n'est possible : Subnetory n'envoie pas d'email et ne propose pas de lien de reinitialisation par courrier electronique.

Si un autre compte ROLE_ADMIN existe, c'est la voie normale de recuperation, entierement depuis l'interface, en deux etapes :

1. reinitialiser le mot de passe du compte bloque depuis `/admin/users` (fiche du compte, ou `POST /api/v1/admin/users/{id}/reset-password`) ; cela ne desactive pas le MFA ;
2. sur la meme fiche compte (`/admin/users/{id}`), section MFA, utiliser le bouton **« Desactiver le MFA »** (`POST /admin/users/{id}/disable-mfa`). Cette action retire le second facteur du compte sans toucher au mot de passe ; elle est prevue precisement pour ce cas de deblocage et n'affecte que l'utilisateur cible, pas le compte administrateur qui l'execute.

Ces deux etapes suffisent a debloquer entierement le compte, sans aucune intervention en base de donnees.

**Seulement si aucun autre compte admin n'existe**, le recours devient un acces direct a la base PostgreSQL (procedure de secours, a n'utiliser qu'en dernier ressort et par une personne de confiance ayant acces au serveur) :

```sql
-- 1. Desactiver le MFA sur le compte bloque et supprimer ses codes de recuperation
UPDATE users SET mfa_enabled = false, mfa_secret_encrypted = NULL
WHERE username = 'admin';

DELETE FROM mfa_recovery_codes
WHERE user_id = (SELECT id FROM users WHERE username = 'admin');

-- 2. Reinitialiser le mot de passe avec un hash bcrypt (cout 12, voir ci-dessous)
--    et forcer son changement a la prochaine connexion
UPDATE users SET password = '<hash_bcrypt>', must_change_password = true
WHERE username = 'admin';
```

Le hash doit etre genere avec le meme cout que l'application (`BCryptPasswordEncoder(12)`), par exemple avec Python :

```bash
pip install bcrypt
python3 -c "import bcrypt; print(bcrypt.hashpw(b'MotDePasseTemporaire123!', bcrypt.gensalt(rounds=12)).decode())"
```

Une fois le hash colle dans la requete `UPDATE`, le compte peut se reconnecter avec le mot de passe temporaire ; `must_change_password = true` l'oblige a en definir un nouveau immediatement, et le MFA est desactive (a reactiver depuis `/profile` si souhaite).

Bonnes pratiques pour eviter d'en arriver la :

* conserver les 10 codes de recuperation dans un gestionnaire de mots de passe ou un coffre-fort, jamais en clair sur le poste ;
* maintenir au moins deux comptes ROLE_ADMIN actifs, pour ne jamais dependre d'un compte unique ;
* documenter la procedure de secours ci-dessus dans la procedure d'exploitation de l'organisation, avec les acces base de donnees necessaires.

## Changement obligatoire après bootstrap ou réinitialisation

Les comptes locaux peuvent porter l'état `mustChangePassword`. Cet état est activé lors de l'initialisation du mot de passe du compte `admin` et lors d'une réinitialisation administrative d'un compte local.

Tant que cet état est actif :

- l'utilisateur est redirigé vers `/profile/change-password-required` ;
- toute URL applicative directe revient vers cet écran ;
- seuls le formulaire obligatoire, la déconnexion, les ressources statiques et la page d'erreur restent accessibles ;
- l'émission d'un JWT est refusée avec HTTP 403 et le code `PASSWORD_CHANGE_REQUIRED`.

Après un changement réussi :

- le nouveau mot de passe est encodé avec BCrypt ;
- `mustChangePassword` repasse à `false` ;
- les anciens JWT de l'utilisateur sont invalidés ;
- les événements correspondants restent audités.

Les comptes LDAP sont exclus : leur mot de passe continue d'être géré par l'annuaire.
