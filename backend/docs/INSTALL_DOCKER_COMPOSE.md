# Installation Docker Compose autonome

## Objet

Cette procédure installe Subnetory avec Docker Compose, PostgreSQL 17 inclus, secrets locaux montés comme fichiers et application exécutée avec un utilisateur non-root.

Le mode autonome utilise :

- `backend/docker-compose.yml` ;
- une image Subnetory construite localement ;
- PostgreSQL 17 dans le service `db` ;
- un volume Docker nommé pour les données ;
- cinq secrets dans `backend/secrets/` (dont un pour le chiffrement des secrets stockés en base — LDAP/MFA — et un pour le chiffrement des sauvegardes, tous deux activés par défaut) ;
- les probes Actuator de liveness et readiness.

PostgreSQL n'est pas publié sur un port de l'hôte.

## Prérequis

Installer et démarrer :

- Git, ou disposer d'une archive source Subnetory ;
- Docker Engine ou Docker Desktop ;
- Docker Compose v2, accessible avec `docker compose` ;
- PowerShell 7 pour `scripts/init-compose.ps1`, ou Bash pour `scripts/init-compose.sh`.

Contrôles :

```sh
docker version
docker compose version
```

L'hôte doit pouvoir télécharger les images de base et les dépendances Maven lors du premier build.

## 1. Récupérer les sources

Avec Git :

```sh
git clone <URL_DU_DEPOT_SUBNETORY>
cd subnetory
```

Après extraction d'une archive, se placer à la racine contenant `backend/` et `scripts/`.

Ne jamais déployer depuis un répertoire contenant des modifications non maîtrisées.

## 2. Générer les secrets

### PowerShell 7

Depuis la racine du projet :

```powershell
pwsh -File .\scripts\init-compose.ps1
```

### Linux avec Bash

Depuis la racine du projet :

```sh
chmod +x scripts/init-compose.sh
./scripts/init-compose.sh
```

Les fichiers suivants sont créés sans afficher leurs valeurs :

```text
backend/secrets/subnetory_jwt_secret
backend/secrets/subnetory_encryption_key
backend/secrets/subnetory_admin_default_password
backend/secrets/postgres_password
backend/secrets/subnetory_backup_encryption_key
```

Propriétés :

- secret JWT : au moins 64 octets aléatoires, encodés en hexadécimal ;
- clé de chiffrement des secrets stockés en base : au moins 32 octets aléatoires, encodés en hexadécimal ;
- mot de passe PostgreSQL : au moins 32 octets aléatoires, encodés en hexadécimal ;
- mot de passe administrateur temporaire : 32 caractères et politique Subnetory respectée ;
- clé de chiffrement des sauvegardes : au moins 32 octets aléatoires, encodés en hexadécimal ;
- UTF-8 sans BOM et sans fin de ligne ;
- permissions restrictives lorsque le système le permet.

Le script refuse d'écraser un secret existant. L'option `-Force` ou `--force` est réservée à une rotation volontaire et coordonnée. Elle ne doit pas être utilisée comme commande de réparation générique sur une installation existante.

### Secret généré par défaut : chiffrement des secrets stockés en base (LDAP/MFA)

`subnetory_encryption_key` chiffre le mot de passe de bind LDAP et le secret TOTP MFA stockés en base — auparavant, ces deux valeurs étaient chiffrées avec le secret JWT lui-même (`subnetory_jwt_secret`), ce qui mélangeait deux usages distincts (signature des jetons d'authentification vs chiffrement au repos). Ce nouveau secret est généré automatiquement avec les autres, sans option de désactivation (contrairement au chiffrement des sauvegardes ci-dessous).

**Instance existante mise à jour** : relancer le script d'initialisation sans `-Force`/`--force` ; seul le secret manquant `subnetory_encryption_key` est créé, les autres secrets restent strictement inchangés. Tant que ce fichier est absent, l'application continue de fonctionner en repli sur `subnetory_jwt_secret` (avec un avertissement au démarrage dans les logs) : ce n'est pas bloquant, mais il est recommandé de régénérer ce secret rapidement après une mise à jour.

### Secret généré par défaut : chiffrement des sauvegardes

Depuis le 01/08/2026, le quatrième secret, `subnetory_backup_encryption_key`, est généré automatiquement avec les trois autres et active par défaut le chiffrement au repos des sauvegardes (backlog #13, voir `backend/docs/BACKUP_ENCRYPTION.md`). Il est déjà déclaré dans `backend/docker-compose.yml` et `docker-compose.prod.yml` (dans les `secrets:` du service `app` et dans les `secrets:` de premier niveau) : aucune action supplémentaire n'est nécessaire pour l'activer.

**Pour ne pas générer/activer ce secret** (déploiement qui refuse explicitement cette fonctionnalité) :

```powershell
pwsh -File .\scripts\init-compose.ps1 -WithoutBackupEncryption
```

```sh
./scripts/init-compose.sh --without-backup-encryption
```

Dans ce cas, il faut aussi recommenter les deux blocs `subnetory_backup_encryption_key` dans `backend/docker-compose.yml` (ou `docker-compose.prod.yml`), l'un dans les `secrets:` du service `app`, l'autre dans les `secrets:` de premier niveau en bas du fichier — sans quoi `docker compose up` échoue faute de fichier secret déclaré mais absent — puis reconstruire l'image (`docker compose up -d --build`).

Le flag historique `-WithBackupEncryption` / `--with-backup-encryption` reste accepté pour compatibilité ascendante, mais n'a plus d'effet : ce secret est désormais généré par défaut.

**Nouvelle instance qui doit lire des sauvegardes chiffrées par une autre instance** (migration, restauration sur un nouveau serveur) : ne pas régénérer ce secret. La valeur doit être **strictement identique** à celle de l'instance d'origine — la clé AES et le HMAC en sont dérivés (PBKDF2), une valeur différente rend le déchiffrement impossible (rejet propre, pas de corruption). Copier le fichier `subnetory_backup_encryption_key` depuis l'endroit où il a été sauvegardé en dehors de l'application (gestionnaire de secrets de l'équipe), jamais depuis une autre instance en production directement.

**Instance existante mise à jour depuis une version antérieure au 01/08/2026** : relancer le script d'initialisation sans `-Force`/`--force` ; seul le secret manquant `subnetory_backup_encryption_key` est créé, les secrets existants (JWT, mot de passe admin, mot de passe PostgreSQL) restent strictement inchangés, puis `docker compose up -d --build` pour recharger la configuration.

### ⚠️ Point de vigilance — sauvegarder ces secrets avant d'aller plus loin

Les cinq fichiers de `backend/secrets/` ne sont générés qu'une seule fois, sur ce poste, et ne sont jamais commités (`.gitignore`). Si ce dossier est perdu — disque défaillant, réinstallation, migration vers une nouvelle machine sans l'avoir copié — **il n'existe aucun autre moyen de les récupérer**. Avant de poursuivre l'installation, lire chaque valeur et la ranger dans un coffre personnel ou d'équipe (gestionnaire de mots de passe : KeePassXC, Bitwarden, 1Password, ou équivalent).

Lire les cinq valeurs (PowerShell, depuis `backend/`) :

```powershell
Get-Content -LiteralPath .\secrets\postgres_password -Raw
Get-Content -LiteralPath .\secrets\subnetory_backup_encryption_key -Raw
Get-Content -LiteralPath .\secrets\subnetory_encryption_key -Raw
Get-Content -LiteralPath .\secrets\subnetory_jwt_secret -Raw
Get-Content -LiteralPath .\secrets\subnetory_admin_default_password -Raw
```

Linux/Bash (depuis `backend/`) :

```sh
cat secrets/postgres_password
cat secrets/subnetory_backup_encryption_key
cat secrets/subnetory_encryption_key
cat secrets/subnetory_jwt_secret
cat secrets/subnetory_admin_default_password
```

Ce que chacun coûte réellement si on ne le garde pas ailleurs :

- **Clé de chiffrement des sauvegardes** (`subnetory_backup_encryption_key`) — la plus critique des cinq. Si elle est perdue, **toutes les sauvegardes déjà chiffrées deviennent définitivement illisibles**, sans aucun recours (ni support, ni contournement — voir `BACKUP_ENCRYPTION.md`). C'est aussi la valeur à transporter volontairement lors d'une migration vers une nouvelle instance qui doit continuer à lire d'anciennes sauvegardes chiffrées.
- **Clé de chiffrement des secrets stockés en base** (`subnetory_encryption_key`) — si elle est perdue, le mot de passe de bind LDAP et le secret TOTP MFA déjà stockés en base deviennent illisibles ; il faut alors reconfigurer LDAP et refaire activer le MFA pour les comptes concernés. Impact réel mais rattrapable (contrairement à la clé de sauvegarde ci-dessus).
- **Mot de passe PostgreSQL** (`postgres_password`) — si ce fichier disparaît, l'application ne peut plus se connecter à la base au prochain redémarrage du conteneur, même si le volume de données `pgdata` est intact et sain.
- **Secret JWT** (`subnetory_jwt_secret`) — impact le plus faible : sa perte ou sa rotation ne fait que déconnecter tous les utilisateurs au prochain redémarrage. Peut être régénéré sans risque pour les données.
- **Mot de passe administrateur temporaire** (`subnetory_admin_default_password`) — à lire dès l'étape 7 ci-dessous pour la première connexion. Il devient inerte après le changement de mot de passe imposé au premier login (voir étape 8) : inutile de le conserver après coup. En revanche, **le nouveau mot de passe personnel choisi à ce moment-là doit, lui, être enregistré dans le même coffre.**

Ne pas faire cette sauvegarde externe maintenant, c'est accepter de repartir de zéro (nouveaux secrets, sauvegardes chiffrées existantes perdues) le jour où ce poste ou ce dossier disparaît.

### Protection des secrets sur le poste

`Set-RestrictivePermissions` (PowerShell) et les `chmod`/`umask` du script Bash restreignent l'accès aux fichiers de `backend/secrets/` :

- **Windows** : `icacls` retire l'héritage et n'accorde l'accès qu'au compte courant, à `SYSTEM` et au groupe `Administrateurs` local.
- **Linux/macOS** : `chmod 700` sur le dossier, `chmod 600` sur chaque fichier (accès au seul propriétaire).

Ce que ça protège : un autre compte utilisateur non privilégié sur la même machine ne peut pas lire ces fichiers.

Ce que ça ne protège **pas** :

- un attaquant qui obtient les droits de votre propre compte (session compromise, malware) ou les droits administrateur — le groupe Administrateurs a explicitement accès, nécessaire pour l'exploitation ;
- le vol physique du disque **éteint et non chiffré** — seul un chiffrement de volume (BitLocker, LUKS) protège ce scénario ;
- une synchronisation cloud du dossier contenant `backend/` en clair. Point d'attention spécifique si `Downloads` ou le dossier du dépôt est redirigé/synchronisé par OneDrive (Known Folder Move, fréquent en environnement Microsoft 365) : vérifier `Get-ItemProperty 'HKCU:\Software\Microsoft\OneDrive\Accounts\*' -ErrorAction SilentlyContinue` ou plus simplement l'icône de synchronisation dans l'explorateur de fichiers sur le dossier du dépôt. Si c'est le cas, `backend/secrets/` (bien que git-ignoré) part quand même en clair vers le cloud.

Pour un vrai modèle de menace "compte utilisateur compromis", aucun secret basé fichier ne protège complètement — il faudrait un gestionnaire de secrets externe (Vault, KMS) avec authentification séparée, hors de portée pragmatique pour ce projet (petite équipe, cf. les contraintes de `AGENTS.md`/instructions projet). Le compromis actuel est cohérent avec l'existant (JWT, mot de passe admin, mot de passe PostgreSQL utilisent déjà ce même mécanisme) : la vraie protection reste l'hygiène du poste (compte non-partagé, verrouillage de session, chiffrement de disque, pas de sync cloud du dépôt).

## 3. Configurer les paramètres non sensibles

Le fichier `.env` est facultatif. Il ne doit contenir aucun secret.

Depuis `backend/` :

### PowerShell

```powershell
Copy-Item .env.example .env
```

### Linux

```sh
cp .env.example .env
```

Valeurs disponibles :

```dotenv
HOST_PORT=8080
SERVER_PORT=8080
POSTGRES_USER=subnetory
```

- `HOST_PORT` : port publié sur l'hôte ;
- `SERVER_PORT` : port d'écoute de Spring Boot dans le conteneur ;
- `POSTGRES_USER` : rôle PostgreSQL initial.

Ne jamais ajouter dans `.env` le secret JWT, le mot de passe administrateur ou le mot de passe PostgreSQL.

## 4. Valider la configuration Compose

Depuis `backend/` :

```sh
docker compose config
```

La commande doit se terminer sans erreur.

Vérifier également que PostgreSQL n'est pas publié sur l'hôte :

```sh
docker compose config
```

Le service `db` ne doit contenir aucune section `ports`.

## 5. Construire et démarrer

Depuis `backend/` :

```sh
docker compose up -d --build
```

Suivre l'état :

```sh
docker compose ps
docker compose logs --tail 100 app
docker compose logs --tail 100 db
```

Le premier démarrage peut prendre davantage de temps, car l'image est construite et Flyway applique les migrations versionnées de l'application.

## 6. Contrôler les probes

Avec le port par défaut :

```sh
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

Sous PowerShell :

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

Résultat attendu :

- liveness : `UP` ;
- readiness : `UP` lorsque PostgreSQL est disponible.

La readiness inclut explicitement `readinessState` et `db`. Le fournisseur LDAP optionnel n'en fait pas partie.

## 7. Première connexion

L'utilisateur initial est :

```text
admin
```

Le mot de passe temporaire se trouve dans :

```text
backend/secrets/subnetory_admin_default_password
```

Lire ce fichier uniquement depuis un terminal privé, non enregistré et inaccessible aux autres utilisateurs.

PowerShell :

```powershell
$TemporaryPassword = (
    Get-Content `
        -LiteralPath .\secrets\subnetory_admin_default_password `
        -Raw
).Trim()
```

Linux :

```sh
IFS= read -r TEMPORARY_PASSWORD < secrets/subnetory_admin_default_password
```

Ouvrir ensuite :

```text
http://localhost:8080/
```

Après authentification, Subnetory impose immédiatement le changement du mot de passe. Les pages applicatives et les jetons JWT restent bloqués tant que cette opération n'est pas terminée.

Après utilisation, supprimer la variable du terminal :

```powershell
Remove-Variable TemporaryPassword -ErrorAction SilentlyContinue
```

```sh
unset TEMPORARY_PASSWORD
```

## 8. Cycle de vie du secret administrateur

Le fichier `subnetory_admin_default_password` doit rester présent après le premier changement de mot de passe.

Le code lit cette propriété à chaque démarrage. Sur une base déjà initialisée :

- la valeur du fichier devient inerte ;
- la modifier ne change pas le mot de passe courant de l'administrateur ;
- la supprimer empêche l'application de démarrer ;
- elle ne redevient active que pour une installation neuve dont le volume PostgreSQL a été supprimé volontairement.

Ne jamais supprimer le volume pour tenter de réinitialiser un mot de passe en production.

## 9. Contrôles de sécurité après installation

Vérifier :

```sh
docker compose ps
docker compose exec app id
docker compose exec app nmap --version
```

Résultats attendus :

- application `healthy` ;
- processus exécuté par l'utilisateur `subnetory`, UID différent de zéro ;
- Nmap disponible ;
- PostgreSQL sans port hôte ;
- aucun secret dans `.env`, l'image ou les logs.

Éviter d'exécuter `docker compose config` dans un contexte où des variables d'environnement sensibles auraient été définies, car Compose peut rendre ces valeurs dans sa sortie.

## 10. Arrêt et suppression

Arrêt sans perte de données :

```sh
docker compose stop
```

Arrêt et suppression des conteneurs et du réseau, volume conservé :

```sh
docker compose down
```

Destruction complète, y compris les données PostgreSQL :

```sh
docker compose down -v
```

La dernière commande est destructive et ne doit être utilisée que pour une installation de test ou une réinitialisation explicitement validée.

## 11. Mode PostgreSQL externe

Le mode production application seule utilise `backend/docker-compose.prod.yml`.

Il est documenté dans `DOCKER_COMPOSE_OPERATIONS.md`. Le fichier autonome `docker-compose.yml` reste la référence pour une installation complète sur une seule machine.

## 12. HTTPS optionnel

`backend/docker-compose.https.yml` est un overlay optionnel qui ajoute un reverse proxy Caddy devant l'application, sans modifier le comportement HTTP par défaut de `docker-compose.yml`. Deux modes disponibles : réseau interne (certificat auto-signé) et domaine public (Let's Encrypt automatique).

Procédure complète : [HTTPS_REVERSE_PROXY.md](HTTPS_REVERSE_PROXY.md).

## Documents liés

- `backend/docs/DOCKER_COMPOSE_OPERATIONS.md`
- `backend/docs/HTTPS_REVERSE_PROXY.md`
- `backend/docs/KUBERNETES_HELM_READINESS.md`
- `backend/docs/NMAP_RUNTIME.md`
- `backend/docs/BACKUP_STRATEGY.md`
- `backend/docs/RESTORE_OPERATIONS.md`
