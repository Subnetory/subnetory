# HTTPS clé en main via reverse proxy Caddy (optionnel)

## Objet

`backend/docker-compose.https.yml` est un overlay optionnel qui ajoute un reverse proxy [Caddy](https://caddyserver.com/) devant le service `app` de la stack Docker Compose autonome (`docker-compose.yml`). Il permet d'exposer Subnetory en HTTPS sans infrastructure externe (pas de certificat à générer ou installer manuellement), pour deux usages :

- **mode interne** : réseau d'entreprise sans domaine public résolu (ex. usage professionnel sur le réseau local) — certificat auto-signé généré par Caddy ;
- **mode public** : domaine public résolu vers cet hôte — certificat Let's Encrypt automatique, renouvelé sans intervention.

L'overlay ne modifie pas le comportement par défaut de `docker-compose.yml` : sans cet overlay, Subnetory continue de fonctionner en HTTP direct comme avant.

## Prérequis

- Stack autonome déjà fonctionnelle (voir [INSTALL_DOCKER_COMPOSE.md](INSTALL_DOCKER_COMPOSE.md)) ;
- ports **443** et **80** libres sur l'hôte (80 sert à la redirection HTTP et, en mode public, à la validation ACME) ;
- en mode public uniquement : un nom de domaine dont le DNS pointe déjà vers l'adresse publique de cet hôte, et les ports 80/443 accessibles depuis Internet.

## 1. Choisir et copier le Caddyfile

Depuis `backend/` :

### Mode interne (réseau d'entreprise, pas de domaine public)

```powershell
Copy-Item deploy\Caddyfile.internal deploy\Caddyfile
```

Éditer `deploy/Caddyfile` si le nom d'hôte `subnetory.local` doit être remplacé par un autre (ou déclarer ce nom dans le fichier hosts des postes clients qui doivent y accéder).

### Mode public (domaine résolu vers cet hôte)

```powershell
Copy-Item deploy\Caddyfile.public deploy\Caddyfile
```

Éditer `deploy/Caddyfile` pour renseigner une adresse email réelle (notifications ACME) et le nom de domaine réel.

`deploy/Caddyfile` (une fois copié) n'est pas versionné — il contient potentiellement des informations spécifiques au déploiement (domaine, email). Seuls les deux fichiers d'exemple `Caddyfile.internal` et `Caddyfile.public` sont versionnés.

## 2. Démarrer avec l'overlay

Depuis `backend/` :

```powershell
docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build
```

Suivre l'état :

```powershell
docker compose -f docker-compose.yml -f docker-compose.https.yml ps
docker compose -f docker-compose.yml -f docker-compose.https.yml logs --tail 100 caddy
```

## 3. Vérifier l'accès HTTPS

### Mode interne

```powershell
Invoke-WebRequest https://subnetory.local/actuator/health/readiness -SkipCertificateCheck
```

Un avertissement de certificat est normal et attendu (certificat auto-signé, autorité locale non distribuée aux postes clients). `-SkipCertificateCheck` (PowerShell 7) ou l'acceptation manuelle du risque dans le navigateur permet de continuer.

### Mode public

```powershell
Invoke-WebRequest https://subnetory.example.com/actuator/health/readiness
```

Aucun avertissement attendu : certificat Let's Encrypt valide.

## 4. Pourquoi `trusted-proxy` est obligatoire ici

Le rate limiting par IP (correctifs sécurité H1 et F7) et l'audit d'authentification s'appuient sur `ClientIpResolver`, qui par défaut ignore les en-têtes `X-Forwarded-For`/`X-Real-IP` pour éviter qu'un client falsifie son IP (voir `SECURITY_FIXES.md`).

Derrière Caddy, la connexion TCP directe vue par l'application provient toujours de Caddy, pas du client réel. Sans configuration, toutes les requêtes seraient donc comptées comme venant d'une seule IP (le conteneur Caddy), ce qui casserait le rate limiting et fausserait les logs d'audit.

L'overlay active donc automatiquement :

```yaml
SUBNETORY_SECURITY_TRUSTED_PROXY: true
SUBNETORY_SECURITY_TRUSTED_PROXY_CIDRS: 172.16.0.0/12   # plage IPAM Docker par défaut
```

Le sous-réseau `172.16.0.0/12` couvre la plage privée que Docker alloue par défaut à ses réseaux Compose. Cette valeur reste sûre dans ce contexte précis : le seul chemin réseau vers l'application est le réseau Compose interne du même hôte Docker (non exposé publiquement), donc un attaquant externe ne peut pas y présenter une IP source dans cette plage. Si le réseau Docker réel utilise une autre plage, ajuster via la variable d'environnement `SUBNETORY_TRUSTED_PROXY_CIDRS` avant de démarrer.

## 5. Persistance des certificats

Le volume nommé `caddy_data` conserve les certificats générés (auto-signés ou Let's Encrypt) entre redémarrages. Ne pas le supprimer sans raison : en mode public, cela déclencherait de nouvelles demandes ACME (risque de limitation de débit Let's Encrypt en cas de suppressions répétées).

## 6. Arrêt

```powershell
docker compose -f docker-compose.yml -f docker-compose.https.yml down
```

Le volume `caddy_data` est conservé sauf ajout explicite de `-v`.

## Limites connues

- pas de renouvellement de certificat au-delà de ce que Caddy gère nativement (pas de PKI d'entreprise intégrée) ;
- le mode interne n'installe pas l'autorité locale Caddy sur les postes clients : chaque poste devra accepter l'avertissement de certificat, ou importer manuellement l'autorité générée par Caddy (`docker compose exec caddy cat /data/caddy/pki/authorities/local/root.crt`) ;
- l'overlay ne couvre qu'un déploiement mono-hôte ; il ne remplace pas un load-balancer TLS pour un scénario multi-nœuds (hors périmètre du projet, architecture mono-réplique).

## Documents liés

- [INSTALL_DOCKER_COMPOSE.md](INSTALL_DOCKER_COMPOSE.md)
- [DOCKER_COMPOSE_OPERATIONS.md](DOCKER_COMPOSE_OPERATIONS.md)
- `SECURITY_FIXES.md` (H1, F7)
