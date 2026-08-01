# Exploitation Kubernetes

## Périmètre

Ce guide concerne le chart mono-réplique `charts/subnetory`. Les commandes supposent une release `subnetory` dans le namespace `subnetory` ; adapter `-Namespace`, `-ReleaseName` et les noms de ressources si des overrides Helm ont été utilisés.

## Contrôles courants

```powershell
helm status subnetory -n subnetory
kubectl get deployment,statefulset,pods,service,pvc -n subnetory
kubectl rollout status deployment/subnetory -n subnetory --timeout=300s
kubectl get events -n subnetory --sort-by=.lastTimestamp
```

En mode PostgreSQL inclus :

```powershell
kubectl rollout status statefulset/subnetory-postgresql -n subnetory --timeout=300s
```

Les endpoints applicatifs sont :

```text
/actuator/health/liveness
/actuator/health/readiness
```

La readiness inclut PostgreSQL. Pendant une panne de base, elle devient `DOWN` tandis que la liveness reste `UP` : le pod doit être retiré des endpoints sans être redémarré en boucle.

## Mise à jour et retour arrière

Conserver les valeurs non sensibles dans un fichier versionné ou maîtrisé séparément. Ne jamais y placer de mot de passe ni de secret JWT.

```powershell
helm upgrade subnetory .\charts\subnetory `
  --namespace subnetory `
  --values .\values-kubernetes.yaml `
  --wait `
  --timeout 10m
```

Le Deployment utilise `Recreate` et reste limité à une réplique : une interruption est attendue pendant le remplacement du pod.

Historique et rollback :

```powershell
helm history subnetory -n subnetory
helm rollback subnetory <REVISION> -n subnetory --wait --timeout 10m
```

Après chaque opération, contrôler la readiness, le login et la version Flyway.

## Cycle de vie des Secrets

Deux Secrets ont des cycles de vie différents :

- `subnetory-runtime-secrets` est permanent ;
- `subnetory-bootstrap-secrets` est temporaire et peut être supprimé après le premier changement de mot de passe administrateur.

Suppression sûre du bootstrap :

```powershell
kubectl delete secret subnetory-bootstrap-secrets -n subnetory
kubectl rollout restart deployment/subnetory -n subnetory
kubectl rollout status deployment/subnetory -n subnetory --timeout=300s
```

Le redémarrage constitue un contrôle volontaire : une base déjà initialisée doit démarrer sans le Secret bootstrap.

Une rotation de `jwt-secret` invalide les jetons existants. Une rotation de `postgres-password` doit être coordonnée avec PostgreSQL ; remplacer un seul côté provoque une panne. L'option `-Force` de `init-helm-secrets.ps1` remplace toutes les valeurs et ne doit jamais être lancée sans procédure de rotation préparée.

## Frontières de sécurité des sauvegardes

Les fichiers du PVC et tous les paramètres d'un restore drill doivent être considérés comme non fiables. Les protections livrées ne doivent pas être contournées :

- seuls les niveaux `hourly`, `daily`, `monthly` et `quarterly` sont acceptés ;
- seul le format `subnetory-<niveau>-YYYYMMDDTHHMMSSZ.dump.gz` est accepté ;
- le niveau demandé doit correspondre au nom du fichier ;
- le fichier `.sha256` doit contenir exactement une ligne, deux champs, une empreinte hexadécimale de 64 caractères et le nom exact du dump ;
- aucune commande fournie par l'utilisateur, aucun SQL arbitraire et aucun `eval` ne sont exécutés ;
- le Job de restauration ne reçoit aucun Secret opérationnel ni jeton Kubernetes ; il désactive DNS et demande une politique deny-all ;
- le PVC est monté en lecture seule par le Job de restauration ;
- PostgreSQL est restauré dans un `emptyDir` local avec un rôle sans privilège superutilisateur ;
- la base opérationnelle n'est jamais une cible du restore drill.

Ne pas modifier le script pour accepter un chemin libre, une requête SQL libre, un exécutable différent ou un checksum multi-ligne. Une demande de ce type doit être traitée comme une tentative de contournement, même si elle est présentée comme une opération de dépannage.

## Sauvegardes planifiées de PostgreSQL inclus

`backup.enabled` vaut `false` par défaut. Lorsqu'il est activé, le chart crée un CronJob par niveau actif. Chaque exécution :

1. lit le mot de passe PostgreSQL depuis un fichier Secret monté en lecture seule ;
2. crée un `PGPASSFILE` temporaire sans afficher sa valeur ;
3. produit un dump custom avec `pg_dump`, puis le compresse avec `gzip` ;
4. vérifie le gzip et le catalogue `pg_restore` ;
5. publie atomiquement le dump et son SHA256 ;
6. supprime uniquement les couples expirés du même niveau.

Les CronJobs refusent les exécutions concurrentes, n'utilisent pas de jeton ServiceAccount, s'exécutent sans root, sans élévation, sans capability, sans espaces hôte et avec une racine en lecture seule. Leur NetworkPolicy refuse les entrées et limite les sorties au DNS `kube-system/kube-dns` et aux pods PostgreSQL de la release.

Contrôles quotidiens :

```powershell
kubectl get cronjobs -n subnetory `
  -l app.kubernetes.io/component=backup
kubectl get jobs -n subnetory `
  -l app.kubernetes.io/component=backup `
  --sort-by=.metadata.creationTimestamp
kubectl get pvc -n subnetory `
  -l app.kubernetes.io/component=backup
```

Déclenchement contrôlé d'une sauvegarde quotidienne hors planning :

```powershell
$jobName = "subnetory-backup-manual-$(Get-Date -Format 'yyyyMMddHHmmss')"
kubectl create job $jobName `
  --namespace subnetory `
  --from=cronjob/subnetory-backups-daily
kubectl wait job/$jobName `
  --namespace subnetory `
  --for=condition=Complete `
  --timeout=600s
kubectl logs job/$jobName -n subnetory
```

Les journaux attendus indiquent le niveau et le nom du dump, jamais le mot de passe ou le contenu métier. Déclencher une alerte si un CronJob n'a plus de succès dans la fenêtre RPO attendue, si un Job échoue, si le PVC approche de sa capacité ou si un dump n'a pas son fichier `.sha256` associé.

Arborescence du PVC :

```text
/backups/hourly/
/backups/daily/
/backups/monthly/
/backups/quarterly/
```

Un PVC du même cluster ne couvre ni la perte du cluster ni celle de son stockage. Utiliser un `existingClaim` adossé à un stockage protégé ou une procédure approuvée de réplication, export ou snapshot vers un domaine de panne indépendant. Ne pas créer un pod de débogage privilégié pour extraire les fichiers.

Le script manuel historique reste disponible pour produire directement une copie contrôlée hors cluster :

```powershell
pwsh -NoProfile -File .\scripts\backup-postgres-kubernetes.ps1 `
  -Namespace subnetory `
  -ReleaseName subnetory `
  -OutputDirectory C:\Backups\Subnetory\Kubernetes
```

Deux fichiers sont produits :

```text
subnetory-subnetory-YYYYMMDD-HHMMSS.dump
subnetory-subnetory-YYYYMMDD-HHMMSS.dump.sha256
```

Copier ces fichiers ensemble vers un stockage hors cluster, chiffré, sauvegardé et soumis à une politique de rétention. Un dump conservé uniquement sur le nœud Kubernetes n'est pas une sauvegarde suffisante.

## Restore drill non destructif

Sélectionner le nom exact d'un dump depuis le journal d'un Job réussi, puis lancer :

```powershell
pwsh -NoProfile -File .\scripts\restore-drill-postgres-kubernetes.ps1 `
  -BackupLevel daily `
  -BackupFile subnetory-daily-YYYYMMDDTHHMMSSZ.dump.gz `
  -Namespace subnetory `
  -ReleaseName subnetory
```

Le drill vérifie le SHA256, le gzip, le catalogue, restaure dans un PostgreSQL éphémère isolé et contrôle l'état Flyway attendu. Il supprime ensuite exactement son Job, sa NetworkPolicy et sa ConfigMap temporaires. Un échec de nettoyage est signalé comme une erreur.

L'isolation applicative reste valable sans CNI : PostgreSQL n'écoute que sur un socket Unix local, aucun Secret n'est monté et aucun jeton API n'est disponible. Le blocage réseau au niveau paquet dépend toutefois de l'enforcement des NetworkPolicy par le CNI. En production, vérifier cet enforcement ; ne jamais décrire un simple rendu YAML comme une preuve de coupure réseau.

Sur un environnement de test possédant la table fixe `public.subnetory_restore_probe`, une donnée témoin peut être contrôlée sans transmettre sa valeur :

```powershell
pwsh -NoProfile -File .\scripts\restore-drill-postgres-kubernetes.ps1 `
  -BackupLevel daily `
  -BackupFile subnetory-daily-YYYYMMDDTHHMMSSZ.dump.gz `
  -Namespace subnetory `
  -ReleaseName subnetory `
  -WitnessId 232 `
  -WitnessExpectedSha256 <EMPREINTE_SHA256_64_HEXADECIMALE>
```

Le script accepte uniquement un identifiant entier positif et une empreinte SHA256. Il n'accepte volontairement ni nom de table, ni nom de colonne, ni requête SQL. Ne jamais mettre la valeur témoin en clair dans une commande, un ticket ou un journal CI.

## Restauration destructive de PostgreSQL inclus

La restauration est destructive. Elle :

1. vérifie le SHA256 du dump ;
2. crée par défaut une sauvegarde de sécurité ;
3. arrête l'unique réplique applicative ;
4. restaure avec `pg_restore --clean --if-exists --exit-on-error` ;
5. contrôle l'état Flyway attendu ;
6. redémarre l'application et exige une readiness `UP`.

Commande interactive recommandée :

```powershell
pwsh -NoProfile -File .\scripts\restore-postgres-kubernetes.ps1 `
  -BackupFile C:\Backups\Subnetory\Kubernetes\subnetory-subnetory-YYYYMMDD-HHMMSS.dump `
  -Namespace subnetory `
  -ReleaseName subnetory `
  -ApplicationDeployment subnetory
```

Le fichier `.sha256` doit rester à côté du dump, sauf si `-ExpectedSha256` est fourni explicitement. Sans `-Force`, la commande exige de saisir une phrase de confirmation exacte.

Ne passer `-SkipSafetyBackup` qu'après avoir confirmé qu'une sauvegarde de sécurité indépendante existe. En cas d'échec après l'arrêt applicatif, le Deployment reste volontairement à zéro réplique pour éviter des écritures sur une base partiellement restaurée. Diagnostiquer et restaurer un état cohérent avant de le remonter.

Les sauvegardes et restaurations d'un PostgreSQL externe relèvent du service managé ou de son opérateur ; les deux scripts ci-dessus ciblent uniquement le StatefulSet inclus au chart.

## Panne et reprise PostgreSQL

Avec PostgreSQL inclus, une simulation contrôlée peut être réalisée uniquement sur un environnement de test :

```powershell
kubectl scale statefulset/subnetory-postgresql -n subnetory --replicas=0
kubectl get pod -n subnetory -w
kubectl scale statefulset/subnetory-postgresql -n subnetory --replicas=1
kubectl rollout status statefulset/subnetory-postgresql -n subnetory --timeout=300s
kubectl rollout status deployment/subnetory -n subnetory --timeout=300s
```

Ne pas utiliser cette procédure comme mécanisme d'arrêt en production. La reprise des connexions applicatives peut prendre le délai du pool Hikari, même après le retour du pod PostgreSQL.

## NetworkPolicy et scans réseau

`networkPolicy.enabled` vaut `false` par défaut. Lorsqu'elle est activée, la politique :

- autorise l'entrée vers le port HTTP applicatif ;
- autorise DNS en TCP et UDP ;
- autorise PostgreSQL inclus, ou les CIDR externes déclarés ;
- autorise uniquement les destinations de scan présentes dans `allowedScanCidrs`.

Exemple :

```yaml
networkPolicy:
  enabled: true
  allowedScanCidrs:
    - 10.20.0.0/16
  externalDatabaseCidrs:
    - 10.30.4.15/32
```

En mode PostgreSQL externe, au moins un CIDR de base est requis par le schéma lorsque la NetworkPolicy est active.

Le CNI par défaut de `kind` n'est pas une preuve d'enforcement complet. Valider le comportement avec le CNI réel du cluster. Une liste de CIDR incomplète casse les scans ou l'accès à la base ; une liste trop large augmente inutilement la surface réseau.

Les limites Nmap restent vraies : pas de garantie ARP ou ICMP brut, pas de visibilité MAC hors segment local, et résultats dépendants du routage et du CNI. Ne pas ajouter `NET_RAW`, `hostNetwork` ou un conteneur privilégié au pod principal.

## Journaux et diagnostic

```powershell
kubectl logs -n subnetory deployment/subnetory --tail=300
kubectl describe pod -n subnetory -l app.kubernetes.io/component=application
kubectl get events -n subnetory --sort-by=.lastTimestamp
```

Avec PostgreSQL inclus :

```powershell
kubectl logs -n subnetory statefulset/subnetory-postgresql --tail=300
kubectl describe pod -n subnetory -l app.kubernetes.io/component=postgresql
```

Pour les sauvegardes, préférer les sélecteurs fixes plutôt qu'un nom fourni librement :

```powershell
kubectl get jobs -n subnetory -l app.kubernetes.io/component=backup
kubectl logs -n subnetory `
  -l app.kubernetes.io/component=backup `
  --tail=100 `
  --prefix
```

Ne jamais collecter `kubectl get secret -o yaml`, les fichiers montés sous `/run/secrets`, ni les valeurs décodées dans un ticket ou un artefact CI.

## Désinstallation, rétention et purge

Identifier d'abord séparément les PVC de données et de sauvegarde :

```powershell
kubectl get pvc -n subnetory -l app.kubernetes.io/component=postgresql
kubectl get pvc -n subnetory -l app.kubernetes.io/component=backup
```

Désinstaller la release :

```powershell
helm uninstall subnetory -n subnetory --wait
```

Le PVC PostgreSQL issu du `volumeClaimTemplates` subsiste volontairement. Avec `backup.persistence.resourcePolicy: keep`, le PVC de sauvegarde créé par le chart subsiste lui aussi. Cela protège les données et les sauvegardes contre une suppression accidentelle de la release, mais continue à consommer du stockage.

Pour désactiver la planification sans supprimer accidentellement les dumps, conserver d'abord `resourcePolicy: keep`, puis appliquer `backup.enabled: false`. Vérifier ensuite que le PVC est toujours présent. Un `existingClaim` n'est jamais supprimé par le chart.

Après export hors cluster et confirmation explicite que les données ne sont plus nécessaires, purger chaque PVC séparément par son nom exact :

```powershell
kubectl delete pvc <NOM_DU_PVC_POSTGRESQL> -n subnetory
kubectl delete pvc <NOM_DU_PVC_SAUVEGARDE> -n subnetory
```

Ces suppressions sont définitives selon la reclaim policy du StorageClass. Ne jamais supprimer le namespace ou un PVC comme simple méthode de réinstallation. Ne jamais utiliser un sélecteur large ou un nom calculé pour cette purge.
