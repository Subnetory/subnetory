# Compatibilité Kubernetes

## Portée

Ce document enregistre les versions réellement utilisées pour valider le chart Helm, initialement pour le Sprint 2.31 le 22 juillet 2026, puis remises à jour au fil des releases (dernière mise à jour : 03/08/2026, release `v0.8.0`). Il ne constitue pas une promesse de compatibilité avec toutes les versions intermédiaires, distributions Kubernetes, architectures ou implémentations CNI/CSI. Le job CI `helm-smoke` (`.github/workflows/ci.yml`) revalide cette matrice à chaque push contre les valeurs par défaut courantes du chart.

## Matrice validée

| Composant | Version ou image validée | Portée |
|---|---|---|
| Chart Subnetory | `0.1.3` | Application `0.8.0` |
| Kubernetes | `1.34.8` | Cluster de smoke test réel |
| Plancher déclaré par le chart | `>=1.34.0-0` | Refus Helm sous ce plancher |
| Helm | `3.21.0` | `lint`, `template`, `install`, `test`, `upgrade`, `rollback`, `uninstall` |
| kind | `0.32.0` | Orchestration du cluster de test |
| Image de nœud kind | `kindest/node:v1.34.8@sha256:02722c2dedddcfc00febf5d27fbeb9b7b2c14294c82109ff4a85d89ac9ba3256` | Digest obligatoire pour reproduire le test |
| kubectl | `1.34.8` | Aligné sur le cluster de test |
| PostgreSQL inclus | `postgres:17.10-alpine3.23` | Tag de patch épinglé |
| Image applicative | `ghcr.io/subnetory/subnetory:v0.8.0` (défaut du chart) ou build local | Image publique publiée par le workflow de release, désormais le défaut de `image.repository`/`image.tag` dans `values.yaml` |
| Java observé dans l'image | Temurin `21.0.11` | Démarrage applicatif du smoke test |
| Architecture testée | `linux/amd64` | Docker Desktop et runner GitHub Actions validé |

## Ce qui a été exercé

Le profil PostgreSQL inclus couvre :

- rendu et validation du schéma Helm ;
- installation et readiness ;
- migrations Flyway versionnées ;
- identités non-root et permissions des fichiers Secret ;
- bootstrap administrateur, changement obligatoire et JWT ;
- `helm test`, upgrade et rollback ;
- redémarrages séparés de l'application et de PostgreSQL ;
- persistance applicative et PostgreSQL ;
- panne de base avec readiness `DOWN` et liveness `UP` ;
- reprise, désinstallation, rétention et purge volontaire du PVC.

Le profil PostgreSQL externe couvre :

- absence de ressources PostgreSQL gérées par le chart ;
- connexion à une base déployée séparément ;
- readiness, migrations Flyway et login bootstrap.

Le script reproductible est `scripts/smoke-test-helm.ps1`. Les versions de Helm, kind et kubectl y sont contrôlées avant toute création de cluster. L'image de nœud kind y est référencée par tag et digest.

## Règles de compatibilité

- `replicaCount` doit rester égal à `1`.
- Une seule architecture de nœud, `linux/amd64`, a été qualifiée.
- Le chart refuse les clusters sous Kubernetes 1.34, mais les versions supérieures ne sont pas automatiquement considérées comme validées.
- Helm 3.21.0 est la version de référence et la plus ancienne version qualifiée dans ce sprint.
- Le tag PostgreSQL ne doit pas être remplacé par un tag flottant comme `17-alpine`.
- En production, un PostgreSQL externe ou géré par un opérateur reste recommandé ; le StatefulSet inclus vise une installation autonome mono-instance.
- Le support des NetworkPolicy dépend du CNI. Le smoke test kind valide le rendu, pas un enforcement universel.
- La persistance dépend du StorageClass et de sa reclaim policy ; seule la StorageClass locale de kind a été exercée de bout en bout.

## Non qualifié

Les éléments suivants ne sont toujours pas validés :

- Kubernetes inférieur à 1.34 ou supérieur à la version testée ;
- Helm 4 ;
- `linux/arm64`, Windows containers ou clusters multi-architecture ;
- plusieurs répliques, HPA ou haute disponibilité applicative ;
- haute disponibilité du PostgreSQL inclus ;
- upgrade majeur PostgreSQL ;
- toutes les implémentations Ingress, Gateway API, CNI et CSI ;
- enforcement complet des NetworkPolicy avec le CNI par défaut de kind ;
- signature (cosign/sigstore) de l'image Subnetory officielle — l'image elle-même est publiée publiquement sur GHCR depuis `v0.8.0`.

Toute extension de cette matrice doit être accompagnée d'un smoke test réel et d'une mise à jour de ce document.
