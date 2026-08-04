# Faisabilité : changer le mot de passe PostgreSQL depuis l'UI (étude, 01/08/2026)

> Ce document répond à une demande de faisabilité : pouvoir changer le mot de passe de la base
> PostgreSQL (utilisateur `subnetory`) depuis l'interface d'administration, réservé à un rôle
> adéquat, avec éventuellement un nouveau rôle dédié aux opérations de sauvegarde.
>
> Conclusion : le changement de rôle applicatif (nouveau rôle `ROLE_BACKUP`) est simple et a été
> **implémenté le 01/08/2026** (voir section dédiée ci-dessous). Le changement du mot de passe
> PostgreSQL lui-même, en un clic et sans intervention manuelle, n'est **pas** raisonnablement
> faisable avec l'architecture actuelle (secrets en fichiers en lecture seule, Docker Compose et
> Kubernetes) et reste à l'état d'étude — une procédure guidée en deux étapes est proposée à la
> place, non implémentée.

## Comment le mot de passe est fourni aujourd'hui

Le mot de passe de connexion (`spring.datasource.password`) n'est jamais codé en dur dans
`application.yml` : il vient d'un `configtree` Spring (`spring.config.import:
optional:configtree:/run/secrets/`), qui lit un fichier monté par l'orchestrateur.

- **Docker Compose** (`backend/docker-compose.yml` et `docker-compose.prod.yml`) : secret Docker
  `postgres_password`, fichier `./secrets/postgres_password` sur l'hôte, monté en lecture seule
  dans le conteneur à `/run/secrets/postgres_password`.
- **Kubernetes** (`charts/subnetory/templates/deployment.yaml`) : `Secret` Kubernetes monté en
  volume à `/run/secrets/runtime/spring.datasource.password`, également en lecture seule.

Ce choix est délibéré et correct du point de vue sécurité (défense en profondeur : même un
conteneur compromis ne peut pas altérer ses propres secrets). C'est précisément ce qui rend un
changement "en un clic" difficile.

## Pourquoi un changement autonome et persistant n'est pas simple

Changer le mot de passe PostgreSQL depuis l'application implique deux choses qui doivent rester
synchronisées :

1. **Le mot de passe réel côté PostgreSQL** (`ALTER ROLE subnetory WITH PASSWORD '...'`) —
   techniquement trivial, l'utilisateur `subnetory` peut changer son propre mot de passe sans
   privilège superutilisateur.
2. **Le secret que l'orchestrateur remonte au conteneur au prochain démarrage** — c'est le point
   bloquant :
   - **Docker Compose** : le fichier source (`./secrets/postgres_password`) est sur l'hôte, hors
     de portée du conteneur. Le rendre inscriptible depuis le conteneur casserait le modèle de
     sécurité des secrets Docker (lecture seule par conception) pour un bénéfice limité.
   - **Kubernetes** : modifier un `Secret` depuis le pod nécessite d'appeler l'API Kubernetes,
     donc d'accorder au `ServiceAccount` de l'application la permission RBAC de patcher ses
     propres secrets. C'est une **élévation de risque réelle** : un conteneur compromis pourrait
     alors réécrire n'importe quel secret qu'il a le droit de modifier. De plus, un volume
     `Secret` monté n'est pas relu instantanément par la JVM (Spring lit le `configtree` une seule
     fois au démarrage) — il faudrait aussi redémarrer le pod pour que le nouveau mot de passe
     soit pris en compte, avec un risque de désynchronisation transitoire (le volume met un
     certain temps à se resynchroniser après une mise à jour du `Secret`).

Dans les deux cas, un changement "atomique et invisible" depuis l'UI est risqué : si la base
accepte le nouveau mot de passe mais que le secret persistant n'est pas mis à jour avant le
prochain redémarrage/rebuild, l'application ne peut plus se reconnecter à sa propre base — un
scénario de panne auto-infligée, difficile à diagnostiquer à distance.

## Recommandation : assistant de rotation guidé, pas un changement automatique

Plutôt que de renoncer complètement, une version pragmatique et sûre est réalisable :

1. L'admin déclenche la rotation depuis l'UI (`ROLE_ADMIN` uniquement, cf. rôles ci-dessous).
2. L'application génère (ou accepte) le nouveau mot de passe, exécute `ALTER ROLE subnetory WITH
   PASSWORD '...'`, et **applique immédiatement le nouveau mot de passe au pool de connexions
   HikariCP en mémoire** (`HikariDataSource` supporte la mise à jour du mot de passe pour les
   connexions futures sans redémarrage de l'application).
3. L'UI affiche ensuite, en clair, la commande exacte que l'admin doit exécuter côté
   infrastructure pour que le changement survive à un redémarrage :
   - Docker Compose : `echo -n '<nouveau mot de passe>' > backend/secrets/postgres_password`
     (et `.env` si `SPRING_DATASOURCE_PASSWORD`/`POSTGRES_PASSWORD` y sont dupliqués, comme
     observé dans l'environnement de dev actuel).
   - Kubernetes : commande `kubectl` pré-remplie pour mettre à jour le `Secret` correspondant.
4. Tant que cette étape manuelle n'est pas faite, un redémarrage de l'application reviendrait à
   l'ancien mot de passe stocké dans le secret — sans casser quoi que ce soit, puisque le mot de
   passe PostgreSQL, lui, aurait déjà changé... ce qui casserait la reconnexion. **Ce point reste
   la vraie limite** : il faut soit accepter ce risque documenté (l'admin doit agir vite), soit ne
   changer le mot de passe PostgreSQL qu'après confirmation explicite que le secret a été mis à
   jour (ordre inversé : préparer le nouveau secret d'abord, redémarrer, puis seulement alors
   `ALTER ROLE`) — plus sûr, mais nécessite un redémarrage de toute façon, donc l'essentiel du
   gain "depuis l'UI, sans y toucher" disparaît.

**Conclusion pragmatique** : cette fonctionnalité a une valeur réelle mais limitée par
l'architecture de secrets déjà en place (qui elle-même est un choix de sécurité correct, à ne pas
affaiblir pour ce cas d'usage). Recommandation : ne pas l'implémenter dans l'immédiat sauf besoin
concret exprimé (rotation de mot de passe périodique imposée par une politique de sécurité) — le
risque de panne auto-infligée dépasse le confort gagné pour une petite équipe qui peut déjà
changer ce mot de passe manuellement via les scripts existants (`backup-postgres.ps1` /
documentation d'installation). Rester en veille : si le besoin devient concret, implémenter
l'assistant en deux étapes décrit ci-dessus, jamais un changement silencieux.

## Nouveau rôle pour les opérations de sauvegarde — implémenté (01/08/2026)

Contrairement au changement de mot de passe, ceci ne dépendait d'aucune contrainte
d'infrastructure : c'est une autorisation purement applicative, du même type que les rôles
existants.

`ROLE_BACKUP` a été ajouté à `dev.subnetory.security.AssignableRoles` (migration Flyway V20). Un
compte avec seulement `ROLE_BACKUP` peut déclencher/purger/supprimer des sauvegardes et consulter
l'historique (`/admin/backup`, `/api/v1/admin/backup`) sans avoir accès au reste de
l'administration (comptes utilisateurs, LDAP, journal d'audit) — utile pour un compte de service
d'automatisation ou un opérateur dédié aux sauvegardes sans lui donner les pleins pouvoirs
`ROLE_ADMIN`.

**Correctif sécurité ÉLEVÉE (audit 04/08/2026) : import et restauration retirés de `ROLE_BACKUP`.**
En pratique, `ROLE_BACKUP` équivalait à un accès administrateur complet aux données : importer un
dump `pg_dump --format=custom` externe n'est validé que par `pg_restore --list` (structure du
format, jamais le contenu), et une restauration écrase l'intégralité de la base applicative. Un
compte `ROLE_BACKUP` seul pouvait donc préparer puis restaurer un dump modifiant utilisateurs,
rôles, associations de contextes ou données métier — bien au-delà de l'intention initiale « accès
limité aux sauvegardes, sans le reste de l'administration ». `POST .../import` et
`POST .../restore` (IHM et API) exigent désormais `ROLE_ADMIN` explicitement, via
`@PreAuthorize("hasRole('ADMIN')")` au niveau de chaque méthode concernée, en plus de la règle
`hasAnyRole('ADMIN', 'BACKUP')` au niveau classe/URL décrite ci-dessous.

Point non anticipé au moment de l'étude, corrigé pendant l'implémentation : la chaîne de sécurité
web (`SecurityConfig`) verrouille `/admin/**` sur `ROLE_ADMIN` **au niveau du filtre HTTP**, avant
même d'atteindre le `@PreAuthorize` du contrôleur — un `@PreAuthorize("hasAnyRole('ADMIN',
'BACKUP')")` seul sur `AdminBackupWebController` n'aurait donc eu aucun effet, la requête étant
déjà rejetée en amont. Corrigé en ajoutant une règle `/admin/backup/**` plus spécifique,
évaluée avant la règle générale `/admin/**`. Le canal REST (`/api/v1/**`) n'a pas ce problème : il
repose uniquement sur `@PreAuthorize`, sans règle de chemin équivalente.

Ce futur rôle **ne doit en aucun cas** donner accès à un éventuel assistant de rotation de mot de
passe PostgreSQL (section précédente) — la rotation de credentials reste un risque d'une autre
nature (accès direct à la base, pas seulement aux données métier via l'application) et doit rester
strictement `ROLE_ADMIN`, ou un futur rôle encore plus restreint dédié (`ROLE_DB_ADMIN`) si le
besoin de séparation des pouvoirs se précise.

## Résumé

| Volet | Faisabilité | Statut |
|---|---|---|
| Nouveau rôle `ROLE_BACKUP` pour les endpoints de sauvegarde | Élevée, aucun obstacle technique | **Implémenté** (01/08/2026) |
| Changement du mot de passe PostgreSQL depuis l'UI, automatique et persistant | Faible — bloqué par le modèle de secrets en lecture seule (Docker Compose et Kubernetes) | Non implémenté ; si le besoin devient concret, assistant guidé en deux étapes (jamais un changement silencieux) |
