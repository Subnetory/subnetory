# État de préparation Kubernetes et Helm

## Statut

Le Sprint 2.32 étend le chart Helm mono-réplique avec des sauvegardes planifiées et un restore drill non destructif pour le PostgreSQL inclus. Le mode PostgreSQL externe reste pris en charge avec les sauvegardes du chart obligatoirement désactivées.

L'implémentation est validée sur un cluster `kind` Kubernetes 1.34.8, localement et en CI distante (GitHub Actions) : les tests statiques (`helm-static`), les tests Docker hostiles et le smoke test kind (`helm-smoke`) s'exécutent à chaque push depuis la publication de `v0.8.0`.

Ce statut ne vaut pas support de haute disponibilité, de multi-réplique ou de toutes les distributions Kubernetes.

## Capacités livrées

Le chart `charts/subnetory` fournit :

- un Deployment applicatif à une réplique, stratégie `Recreate` ;
- un ServiceAccount sans montage automatique de jeton ;
- un Service applicatif ;
- une ConfigMap limitée aux valeurs non sensibles ;
- trois probes applicatives distinctes ;
- un contexte non-root UID/GID `10001`, seccomp `RuntimeDefault`, capacités supprimées et racine en lecture seule ;
- un `emptyDir` dédié à `/tmp` ;
- deux Secrets Kubernetes préexistants montés en fichiers avec mode `0440` ;
- un Secret bootstrap supprimable après initialisation ;
- un StatefulSet PostgreSQL persistant optionnel ;
- un mode PostgreSQL externe réellement testé ;
- un Ingress optionnel ;
- des NetworkPolicy optionnelles et désactivées par défaut ;
- un test Helm natif ;
- des CronJobs de sauvegarde optionnels pour quatre niveaux de rétention ;
- un PVC de sauvegarde créé ou préexistant, distinct du PVC PostgreSQL ;
- des contrôles gzip, catalogue PostgreSQL et SHA256 avant publication ;
- une rotation isolée par niveau ;
- un restore drill temporaire, sans réseau et sans accès à la base opérationnelle ;
- les scripts manuels historiques de sauvegarde et restauration destructive.

Le schéma `values.schema.json` impose notamment une seule réplique et exactement un mode de base actif.

## Secrets et bootstrap

Les deux objets attendus sont :

```text
subnetory-runtime-secrets
  jwt-secret
  postgres-password
  encryption-key           (optionnel, secrets.encryptionKeyEnabled=true)
  backup-encryption-key    (optionnel, secrets.backupEncryptionKeyEnabled=true)

subnetory-bootstrap-secrets
  admin-default-password
```

Ils sont montés dans deux arborescences :

```text
/run/secrets/runtime/subnetory.jwt.secret
/run/secrets/runtime/spring.datasource.password
/run/secrets/runtime/subnetory.security.encryption-key      (si encryptionKeyEnabled)
/run/secrets/runtime/subnetory.backup.encryption.key        (si backupEncryptionKeyEnabled)
/run/secrets/bootstrap/subnetory.admin.default-password
```

Le volume bootstrap est optionnel au niveau de l'objet Secret complet. Après le premier changement de mot de passe, `subnetory-bootstrap-secrets` peut donc être supprimé sans empêcher un redémarrage. Le Secret runtime reste obligatoire.

Les clés `encryption-key` et `backup-encryption-key` sont désactivées par défaut (voir les commentaires dans `charts/subnetory/values.yaml`) pour ne pas casser un Secret existant qui ne les porte pas encore. `encryption-key` chiffre les secrets stockés en base (bind LDAP, TOTP MFA) ; `backup-encryption-key` chiffre les sauvegardes de l'engine in-app (voir `backend/docs/BACKUP_ENCRYPTION.md`). Dans les deux cas, changer ou perdre la clé après activation rend illisible ce qu'elle protégeait déjà — elle doit être générée une seule fois et conservée hors du cluster (gestionnaire de secrets, coffre-fort).

Le chart ne génère et ne rend aucun secret en clair.

## Modèle de menace des sauvegardes et restaurations

Le système suppose qu'un dump, son fichier `.sha256` et les paramètres fournis à un script peuvent être hostiles. Les contrôles suivants sont bloquants :

- liste fermée de quatre niveaux et format de nom strict ;
- concordance obligatoire entre le niveau et le nom du dump ;
- checksum mono-ligne, deux champs, nom exact et SHA256 hexadécimal ;
- aucune interprétation de commande, aucun SQL arbitraire et aucun choix libre d'exécutable ;
- rejet des namespaces, releases et noms de PVC non conformes aux formats Kubernetes attendus ;
- aucun `PGPASSWORD` ou `POSTGRES_PASSWORD` dans les variables d'environnement des Jobs ;
- mot de passe lu depuis un fichier Secret uniquement par le Job de sauvegarde ;
- aucun Secret opérationnel dans le Job de restore drill ;
- absence de jeton API, de réseau/processus IPC hôte, de `hostPath`, de privilège, de capability et de racine inscriptible ;
- PVC monté en lecture seule pour la restauration ;
- PostgreSQL éphémère sur `emptyDir`, socket Unix local et rôle de restauration non-superutilisateur.

Le restore drill n'accepte comme témoin qu'un identifiant entier et une empreinte SHA256. Il n'expose volontairement aucun paramètre de table, colonne ou requête. Cette restriction empêche de transformer un contrôle de reprise en canal d'injection SQL.

Les pods de backup possèdent une NetworkPolicy dédiée limitant les sorties au DNS standard et au PostgreSQL inclus. Le restore drill désactive DNS et crée une NetworkPolicy deny-all. L'enforcement au niveau paquet dépend du CNI : sans support NetworkPolicy, les autres barrières restent actives mais la coupure réseau n'est pas démontrée. La production doit vérifier ce comportement avec son CNI réel.

## Probes et panne PostgreSQL

Endpoints :

```text
/actuator/health/liveness
/actuator/health/readiness
```

Le chart utilise :

- une `startupProbe` pour laisser à Java et Flyway le temps de démarrer ;
- une `livenessProbe` indépendante de la disponibilité de la base ;
- une `readinessProbe` incluant PostgreSQL.

Une panne PostgreSQL réelle a été exercée : readiness `DOWN`, liveness `UP`, puis récupération automatique après le retour de la base.

PostgreSQL inclus possède ses propres probes `pg_isready` de startup, liveness et readiness.

## Persistance

Le pod applicatif ne stocke aucune donnée métier persistante. En mode inclus, PostgreSQL utilise un PVC issu du `volumeClaimTemplates`. Les sauvegardes planifiées utilisent un second PVC créé par le chart ou un `existingClaim` explicitement fourni.

`helm uninstall` ne supprime pas le PVC PostgreSQL. La politique par défaut `backup.persistence.resourcePolicy: keep` conserve aussi le PVC de sauvegarde créé par le chart. La purge reste une action distincte, explicite et destructive.

Les dumps planifiés sont publiés atomiquement sous forme custom PostgreSQL transportée dans gzip, avec une empreinte SHA256 associée. Une copie située uniquement dans le même cluster ne constitue cependant pas une sauvegarde hors site.

Pour une production, un PostgreSQL externe, managé ou géré par un opérateur reste recommandé.

## Réplique unique et stratégie de déploiement

Le support reste limité à :

```yaml
replicaCount: 1
```

Le schéma refuse toute autre valeur. La stratégie `Recreate` empêche le chevauchement normal de deux pods mais entraîne une interruption pendant une mise à jour.

Les limites structurelles restent :

- sessions Web conservées localement dans le processus ;
- schedulers non coordonnés ;
- absence de verrouillage distribué ;
- scans et tâches non conçus comme une file distribuée ;
- démarrages Flyway concurrents non qualifiés pour ce produit ;
- absence de tests de charge et de cohérence à plusieurs répliques.

HPA, haute disponibilité applicative, zéro interruption et plusieurs pods ne sont pas supportés.

## Réseau, Ingress et Nmap

Le Service est `ClusterIP` par défaut. L'Ingress est livré mais désactivé ; son contrôleur, ses hôtes, sa terminaison TLS et ses annotations dépendent du cluster cible.

Les NetworkPolicy sont livrées mais désactivées par défaut. Leur activation limite les sorties à DNS, PostgreSQL et aux CIDR de scan explicitement déclarés. Ce choix évite de casser silencieusement la fonction principale de découverte réseau.

Le CNI de `kind` ne démontre pas un enforcement universel. Une validation sur le CNI réel est requise.

Les limites Nmap restent vraies :

- découverte ARP non garantie ;
- ICMP brut non garanti ;
- adresses MAC généralement indisponibles hors segment local ;
- visibilité dépendante du routage, du CNI et des politiques ;
- aucune justification pour `privileged`, `hostNetwork` ou `NET_RAW` sur le pod principal.

Une sonde dédiée sur le réseau concerné reste préférable pour une découverte de niveau 2.

## Ressources

Aucune valeur universelle de requests ou limits n'est imposée. Elles doivent être mesurées selon :

- le temps de démarrage Java et Flyway ;
- la mémoire avec `MaxRAMPercentage=75.0` ;
- les imports CSV/XLSX ;
- les scans Nmap ;
- la charge API et Web ;
- le pool de connexions PostgreSQL.

Des limites trop faibles peuvent causer `OOMKilled`, des probes en échec ou des imports interrompus.

## Compatibilité et image

La matrice précise est tenue dans `backend/docs/KUBERNETES_COMPATIBILITY.md`.

Depuis la release `v0.8.0`, `ghcr.io/subnetory/subnetory:v0.8.0` est une image officielle publique, publiée par `release.yml` et pullable anonymement — c'est le défaut de `image.repository`/`image.tag` dans `values.yaml`. Elle n'est pas signée (voir `KUBERNETES_COMPATIBILITY.md`, section « Non qualifié »). Construire l'image localement et la charger dans `kind`, ou la publier dans un registre privé avec `imagePullSecrets`, reste possible et documenté dans `backend/docs/INSTALL_KUBERNETES.md` (Options B/C) pour un cluster sans accès sortant à `ghcr.io`.

## Conditions restantes avant une offre de production élargie

- qualifier le chart sur les distributions Kubernetes réellement ciblées ;
- mesurer requests et limits ;
- tester l'Ingress et TLS du cluster cible ;
- tester l'enforcement NetworkPolicy avec son CNI ;
- raccorder le PVC ou ses exports à une sauvegarde hors cluster chiffrée et supervisée ;
- qualifier les snapshots, la réplication, les quotas et les alertes du CSI retenu ;
- qualifier le PostgreSQL externe ou l'opérateur retenu ;
- signer et tracer l'image officielle déjà publiée (cosign/sigstore, provenance SLSA) ;
- effectuer les travaux d'architecture nécessaires avant toute multi-réplique.

## Documentation associée

- installation : `backend/docs/INSTALL_KUBERNETES.md` ;
- exploitation : `backend/docs/KUBERNETES_OPERATIONS.md` ;
- compatibilité : `backend/docs/KUBERNETES_COMPATIBILITY.md`.
