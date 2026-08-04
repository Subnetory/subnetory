# Installation Kubernetes avec Helm

## Objet et périmètre

Cette procédure installe Subnetory avec le chart `charts/subnetory` en mode mono-réplique. Deux modes de base de données sont disponibles :

- PostgreSQL inclus au chart, avec un volume persistant ;
- PostgreSQL externe, fourni et exploité séparément.

Le chart impose `replicaCount: 1`. Il ne fournit ni haute disponibilité applicative, ni opérateur PostgreSQL.

Une image officielle est publiée sur GHCR à chaque tag `v*` (voir [README.md](../../README.md#current-release)). Le dépôt et son package GHCR sont publics depuis la `v0.8.0` : la récupérer ne nécessite aucune authentification. Pour un cluster sans accès sortant à `ghcr.io` (cluster `kind` local isolé, registre d'entreprise fermé), l'image doit être construite et rendue accessible aux nœuds autrement — voir les options ci-dessous.

## Prérequis

Prévoir :

- un cluster Kubernetes conforme à `backend/docs/KUBERNETES_COMPATIBILITY.md` ;
- `kubectl` configuré sur le cluster cible ;
- Helm ;
- PowerShell 7 ou Bash pour initialiser les Secrets ;
- une image Subnetory accessible par les nœuds du cluster.

Contrôles de base :

```powershell
kubectl version
helm version
kubectl cluster-info
```

Toutes les commandes suivantes sont exécutées depuis la racine du dépôt.

## 1. Fournir l'image applicative

### Option A — image officielle GHCR

Si le cluster cible peut atteindre `ghcr.io`, utiliser directement l'image publiée par le workflow de release. Le package étant public, aucun `imagePullSecrets` n'est nécessaire. Ce sont les valeurs par défaut du chart (audit 03/08/2026) — un `helm install` sans surcharge utilise déjà cette image :

```yaml
image:
  repository: ghcr.io/subnetory/subnetory
  tag: v0.8.4
  pullPolicy: IfNotPresent
```

### Option B — cluster kind et build local

Pour un cluster `kind` sans accès sortant à `ghcr.io`, construire puis charger l'image localement, puis surcharger explicitement `image.repository`/`image.tag` (les valeurs par défaut du chart pointent vers GHCR depuis l'Option A ci-dessus) :

```powershell
docker build --tag subnetory:0.8.4 .\backend
kind load docker-image subnetory:0.8.4 --name NOM_DU_CLUSTER
```

```yaml
image:
  repository: subnetory
  tag: "0.8.4"
  pullPolicy: IfNotPresent
```

### Option C — registre privé d'entreprise

Pour un autre cluster, construire l'image, la publier dans le registre privé autorisé par l'organisation, puis fournir des valeurs dédiées :

```yaml
image:
  repository: registry.example.internal/subnetory/subnetory
  tag: 0.8.4
  pullPolicy: IfNotPresent
  imagePullSecrets:
    - name: registry-credentials
```

Le Secret `registry-credentials` doit exister dans le namespace avant l'installation. Ne pas utiliser un tag flottant comme `latest`.

## 2. Créer le namespace et les Secrets

```powershell
kubectl create namespace subnetory
pwsh -NoProfile -File .\scripts\init-helm-secrets.ps1 -Namespace subnetory
```

Sous Linux :

```sh
kubectl create namespace subnetory
chmod +x scripts/init-helm-secrets.sh
./scripts/init-helm-secrets.sh --namespace subnetory
```

Les scripts créent deux objets distincts sans afficher leurs valeurs :

- `subnetory-runtime-secrets`, contenant `jwt-secret` et `postgres-password` ;
- `subnetory-bootstrap-secrets`, contenant `admin-default-password`.

Ils refusent d'écraser un Secret existant. L'option `-Force` ou `--force` est réservée à une rotation volontaire et coordonnée ; elle ne constitue pas une commande de réparation générique.

Le chart ne génère aucun Secret et n'accepte pas de valeur secrète en clair dans `values.yaml`.

## 3A. Installer avec PostgreSQL inclus

Valider puis installer les valeurs par défaut :

```powershell
helm lint .\charts\subnetory --strict
helm upgrade --install subnetory .\charts\subnetory `
  --namespace subnetory `
  --wait `
  --timeout 10m
```

Le déploiement crée notamment :

- un Deployment applicatif en stratégie `Recreate` ;
- un Service applicatif `ClusterIP` ;
- un StatefulSet PostgreSQL ;
- un Service PostgreSQL interne ;
- un PVC PostgreSQL de `8Gi` par défaut.

Pour choisir une StorageClass ou une taille différente, utiliser un fichier de valeurs non sensible :

```yaml
postgresql:
  persistence:
    enabled: true
    size: 20Gi
    storageClass: fast-retain
    accessModes:
      - ReadWriteOnce
```

Puis :

```powershell
helm upgrade --install subnetory .\charts\subnetory `
  --namespace subnetory `
  --values .\values-kubernetes.yaml `
  --wait `
  --timeout 10m
```

### Activer les sauvegardes planifiées

La fonction est volontairement désactivée par défaut et réservée au PostgreSQL inclus. Le schéma Helm refuse son activation avec une base externe ou sans planning actif.

Exemple de fichier `values-backup.yaml` non sensible :

```yaml
backup:
  enabled: true
  timeZone: Etc/UTC
  persistence:
    existingClaim: ""
    size: 20Gi
    storageClass: ""
    accessModes:
      - ReadWriteOnce
    resourcePolicy: keep
  schedules:
    hourly:
      enabled: false
      schedule: "0 * * * *"
      retentionDays: 2
    daily:
      enabled: true
      schedule: "0 2 * * *"
      retentionDays: 14
    monthly:
      enabled: false
      schedule: "0 3 1 * *"
      retentionDays: 365
    quarterly:
      enabled: false
      schedule: "0 4 1 1,4,7,10 *"
      retentionDays: 730
```

Valider le rendu avant toute activation :

```powershell
helm lint .\charts\subnetory --strict
helm template subnetory .\charts\subnetory `
  --namespace subnetory `
  --values .\values-kubernetes.yaml `
  --values .\values-backup.yaml > $null
```

Sur une StorageClass utilisant `WaitForFirstConsumer`, un nouveau PVC de sauvegarde ne devient `Bound` qu'au démarrage du premier Job. Une première activation avec `--wait` peut donc atteindre son délai avant que le CronJob ne s'exécute. Activer la fonction, contrôler explicitement les workloads, puis déclencher le premier Job :

```powershell
helm upgrade --install subnetory .\charts\subnetory `
  --namespace subnetory `
  --values .\values-kubernetes.yaml `
  --values .\values-backup.yaml `
  --timeout 10m

kubectl rollout status deployment/subnetory -n subnetory --timeout=300s
kubectl rollout status statefulset/subnetory-postgresql -n subnetory --timeout=300s

$jobName = "subnetory-backup-initial-$(Get-Date -Format 'yyyyMMddHHmmss')"
kubectl create job $jobName `
  --namespace subnetory `
  --from=cronjob/subnetory-backups-daily
kubectl wait job/$jobName `
  --namespace subnetory `
  --for=condition=Complete `
  --timeout=600s
kubectl logs job/$jobName -n subnetory
```

La confirmation attendue contient uniquement un nom conforme à `subnetory-daily-YYYYMMDDTHHMMSSZ.dump.gz`. Elle ne doit contenir ni mot de passe ni contenu métier.

Pour utiliser un PVC précréé, renseigner seulement :

```yaml
backup:
  enabled: true
  persistence:
    existingClaim: subnetory-backups-retained
```

Le PVC doit exister dans le même namespace, accepter les écritures du groupe `70` et être compatible avec la politique d'accès du cluster. Le chart ne crée alors aucun second PVC de sauvegarde.

Le stockage reste distinct du PVC PostgreSQL. Même conservé après une désinstallation, un PVC dans le même cluster n'est pas une copie hors site : prévoir une réplication, un export ou des snapshots vers un domaine de panne indépendant.

Une NetworkPolicy propre aux pods de sauvegarde est rendue dès l'activation. Elle refuse les entrées et limite les sorties au PostgreSQL inclus et au DNS standard `kube-system` portant le label `k8s-app: kube-dns`. Vérifier ce label et l'enforcement NetworkPolicy du CNI cible avant la production ; un DNS personnalisé non conforme doit être qualifié avant activation, sans élargir la règle à Internet.

## 3B. Installer avec PostgreSQL externe

Le rôle et la base externes doivent déjà exister. La valeur `postgres-password` de `subnetory-runtime-secrets` doit être exactement le mot de passe du rôle configuré dans `externalDatabase.username`.

Si ce mot de passe existe déjà, remplacer l'étape 2 par la création des deux Secrets depuis trois fichiers sécurisés, encodés en UTF-8 sans fin de ligne :

```powershell
$jwtSecretFile = '<CHEMIN_SECRET_JWT>'
$postgresPasswordFile = '<CHEMIN_MOT_DE_PASSE_POSTGRESQL>'
$bootstrapPasswordFile = '<CHEMIN_MOT_DE_PASSE_ADMIN_TEMPORAIRE>'

kubectl create secret generic subnetory-runtime-secrets `
  --namespace subnetory `
  --from-file="jwt-secret=$jwtSecretFile" `
  --from-file="postgres-password=$postgresPasswordFile"

kubectl create secret generic subnetory-bootstrap-secrets `
  --namespace subnetory `
  --from-file="admin-default-password=$bootstrapPasswordFile"
```

Le secret JWT doit contenir au moins 64 octets aléatoires, le mot de passe PostgreSQL au moins 32 caractères et le mot de passe administrateur temporaire au moins 24 caractères. Les fichiers sources doivent provenir d'un gestionnaire de secrets ou d'une génération cryptographique, puis être supprimés du poste dès que la politique de l'organisation le permet. Ne jamais les ajouter au dépôt.

Si `init-helm-secrets.ps1` a été utilisé, le mot de passe PostgreSQL externe doit au contraire être configuré pour correspondre à la valeur aléatoire créée dans le Secret avant de démarrer Subnetory.

Exemple de valeurs non sensibles :

```yaml
postgresql:
  enabled: false

externalDatabase:
  enabled: true
  host: postgresql.example.internal
  port: 5432
  database: subnetory
  username: subnetory
  sslMode: require
```

Installer :

```powershell
helm upgrade --install subnetory .\charts\subnetory `
  --namespace subnetory `
  --values .\values-external-database.yaml `
  --wait `
  --timeout 10m
```

Le schéma du chart refuse l'activation simultanée des deux modes, ainsi que l'absence totale de mode de base.

## 4. Vérifier le déploiement

```powershell
kubectl get pods,pvc -n subnetory
kubectl rollout status deployment/subnetory -n subnetory --timeout=300s
helm test subnetory -n subnetory --logs --timeout 5m
```

Avec PostgreSQL inclus :

```powershell
kubectl rollout status statefulset/subnetory-postgresql -n subnetory --timeout=300s
```

Flyway applique les migrations versionnées au premier démarrage. Une installation neuve sans Secret bootstrap échoue explicitement au démarrage.

## 5. Ouvrir l'interface et terminer le bootstrap

Pour un accès local :

```powershell
kubectl port-forward -n subnetory service/subnetory 8080:8080
```

Ouvrir `http://127.0.0.1:8080`, se connecter avec l'utilisateur `admin` et la valeur de `admin-default-password`, puis remplacer immédiatement ce mot de passe.

La lecture du mot de passe temporaire est une opération sensible à effectuer dans un terminal privé :

```powershell
$encoded = kubectl get secret subnetory-bootstrap-secrets -n subnetory `
  -o jsonpath='{.data.admin-default-password}'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
```

Après le changement réussi, supprimer l'objet bootstrap complet :

```powershell
kubectl delete secret subnetory-bootstrap-secrets -n subnetory
```

Ne jamais supprimer `subnetory-runtime-secrets` pendant l'exploitation. Un redémarrage ultérieur fonctionne sans le Secret bootstrap parce que le mot de passe haché est déjà stocké en base.

## 6. Exposition et réseau

Le Service est `ClusterIP` par défaut. L'Ingress est désactivé et nécessite un contrôleur Ingress déjà installé. Les hôtes, chemins, annotations et références TLS restent propres au cluster cible.

**`ingress.trustedProxyCidrs` est obligatoire dès que `ingress.enabled=true`** (correctif sécurité ÉLEVÉE, audit 04/08/2026) : le chart refuse de rendre les manifests si cette liste est vide. Sans elle, `SERVER_FORWARD_HEADERS_STRATEGY` et `SUBNETORY_SECURITY_TRUSTED_PROXY` ne sont jamais positionnés, donc l'application ignore `X-Forwarded-For`/`X-Forwarded-Proto` : tous les utilisateurs passant par l'Ingress apparaissent avec la même IP (celle du contrôleur Ingress), ce qui mutualise le rate limiting API et le verrouillage anti-bruteforce entre tous les utilisateurs, et empêche le cookie de session d'obtenir l'attribut `Secure` derrière une terminaison TLS sur l'Ingress. Renseigner le(s) CIDR depuis lesquels le pod applicatif voit arriver le trafic routé par l'Ingress (réseau des pods du cluster la plupart du temps ; à confirmer auprès du fournisseur Kubernetes cible).

Les NetworkPolicy sont également désactivées par défaut. Leur activation doit inclure tous les réseaux que Subnetory est autorisé à scanner et, en mode externe, les CIDR de PostgreSQL. Voir `backend/docs/KUBERNETES_OPERATIONS.md` avant de les activer.

## 7. Durcissement production

`values.yaml` fournit des défauts raisonnablement sûrs pour une première installation sans configuration préalable (chiffrement au repos et NetworkPolicy désactivés, par exemple, pour ne pas casser un `helm install` sans Secret complet ni CIDR renseignés — voir les commentaires de chaque champ dans le fichier). `charts/subnetory/values-production.yaml` (correctif sécurité MOYENNE, audit 04/08/2026) resserre volontairement cette poignée de défauts pour un déploiement de production : chiffrement au repos (base + sauvegardes), NetworkPolicy, et un exemple pour `externalDatabase.sslMode: verify-full`. À combiner avec `values.yaml`, jamais utilisé seul, et seulement après avoir préparé les prérequis décrits en commentaire dans ce fichier (clés de chiffrement déjà présentes dans le Secret, CIDR réseau réels, etc.) :

```bash
helm install subnetory ./charts/subnetory \
  -f charts/subnetory/values.yaml \
  -f charts/subnetory/values-production.yaml \
  --set ingress.hosts[0].host=subnetory.example.com \
  --set ingress.trustedProxyCidrs={10.244.0.0/16}
```

## Suite

- exploitation, sauvegarde, restauration et désinstallation : `backend/docs/KUBERNETES_OPERATIONS.md` ;
- versions réellement testées : `backend/docs/KUBERNETES_COMPATIBILITY.md` ;
- limites d'architecture : `backend/docs/KUBERNETES_HELM_READINESS.md`.
