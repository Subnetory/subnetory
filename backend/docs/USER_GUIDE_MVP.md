# Subnetory — Guide utilisateur MVP

> Version : 0.8.0-SNAPSHOT
> Public : administrateurs réseau, équipes IT

---

## Qu'est-ce que Subnetory ?

Subnetory est un outil de gestion d'adresses IP (IPAM) léger et auto-hébergé.

Il permet de :
- inventorier les sous-réseaux IPv4 d'un réseau d'entreprise ;
- enregistrer et rechercher des adresses IP avec leurs métadonnées ;
- trouver rapidement les adresses disponibles dans un sous-réseau ;
- importer et exporter des adresses en CSV ou Excel ;
- gérer plusieurs contextes de routage (VRF) et plusieurs sites.

---

## Prérequis

- Docker et Docker Compose installés sur la machine hôte
- Port 8080 disponible
- Pas d'installation Java requise

---

## Démarrage rapide

### 1. Récupérer les fichiers

```bash
mkdir subnetory && cd subnetory
cp .env.example .env
```

### 2. Configurer `.env`

Éditer `.env` et renseigner au minimum :

```env
SUBNETORY_ADMIN_DEFAULT_PASSWORD=choisir-un-mot-de-passe-robuste
SUBNETORY_JWT_SECRET=une-chaine-aleatoire-de-32-caracteres-minimum
SPRING_DATASOURCE_PASSWORD=mot-de-passe-base-de-donnees
```

> **Important** : définir ces valeurs **avant le premier démarrage**.
> Le compte admin est initialisé une seule fois, au premier démarrage,
> si le compte n'existe pas encore en base.
> Modifier `.env` après initialisation ne change pas le mot de passe
> déjà enregistré en base.

> **Sécurité** : `.env` ne doit jamais être versionné dans Git.
> Vérifier qu'il figure bien dans `.gitignore`.

### 3. Lancer l'application

```bash
docker compose up -d
```

L'application démarre en quelques secondes. Les migrations Flyway
sont appliquées automatiquement.

### 4. Se connecter

Naviguer sur `http://localhost:8080`

- Identifiant : `admin`
- Mot de passe : la valeur définie dans `SUBNETORY_ADMIN_DEFAULT_PASSWORD`

Après connexion, vous arrivez sur le **Tableau de bord**.

---

## Sécurité — bonnes pratiques pour ce déploiement

### Mot de passe administrateur

Choisir un mot de passe d'au moins 12 caractères.
Éviter les mots de passe évidents (nom du projet, `admin`, dates).
Privilégier une passphrase ou un générateur de mots de passe.

`SUBNETORY_ADMIN_DEFAULT_PASSWORD` initialise le compte admin au premier
démarrage uniquement, si le compte est absent ou son mot de passe vide en base.
Ce n'est pas un mécanisme de rotation applicable à une base déjà initialisée.

Les comptes locaux peuvent changer leur mot de passe depuis leur profil.
Un administrateur peut réinitialiser le mot de passe d'un compte local depuis
l'écran de détail utilisateur.

### Secret JWT

`SUBNETORY_JWT_SECRET` doit contenir au moins 32 caractères aléatoires.
Ce secret signe les tokens d'API. Ne jamais utiliser une valeur triviale
en production.

### Fichier `.env`

Ne jamais versionner `.env`. Ne jamais le copier dans un dépôt Git, même
privé. Ne jamais le partager dans un canal non chiffré.

### Limites de sécurité connues

Cette version est un MVP destiné à un usage interne ou en environnement
contrôlé. Les contrôles suivants sont disponibles :

| Fonctionnalité | Statut |
|---|---|
| MFA / authentification à deux facteurs | Disponible (optionnel, TOTP), voir section dédiée |
| Changement de mot de passe via interface | Disponible pour les comptes locaux |
| Politique de complexité mot de passe | Active |
| Rate limiting / verrouillage après échecs | Actif |
| Audit des connexions | Actif |
| Révocation des tokens API | Disponible |

**Recommandation** : ne pas exposer Subnetory directement sur Internet
sans reverse proxy HTTPS (Nginx, Traefik, Caddy) et durcissement
de l'authentification.

---

## Premier pas — créer les données de base

Subnetory organise les données selon une hiérarchie :

```
Contexte (VRF)
└── Site
    ├── VLAN
    └── Sous-réseau
        └── Adresse IP
```

### Étape 1 — Créer un contexte

Navigation : **Contextes → Nouveau contexte**

Un contexte représente un espace de routage (VRF, table de routage).

### Étape 2 — Créer un site

Navigation : **Sites → Nouveau site**

Un site est un emplacement physique ou logique rattaché à un contexte.

### Étape 3 — Créer un VLAN (optionnel)

Navigation : **VLANs → Nouveau VLAN**

### Étape 4 — Créer un sous-réseau

Navigation : **Sous-réseaux → Nouveau sous-réseau**

Exemple : `192.168.1.0/24`, gateway `192.168.1.1`.

### Étape 5 — Créer une adresse IP

Navigation : **Adresses IP → Nouvelle adresse**

---

## Tableau de bord

La page d'accueil affiche les compteurs globaux (contextes, sites, VLANs, sous-réseaux, adresses IP) ainsi qu'un classement des dix sous-réseaux les plus utilisés, avec leur capacité, le nombre d'adresses utilisées, le nombre d'adresses disponibles et le taux d'utilisation.

La capacité affichée est la capacité théorique du bloc CIDR : elle inclut l'adresse réseau et l'adresse de broadcast. Le nombre d'adresses disponibles correspond à la capacité moins les adresses utilisées, jamais négatif. Le taux d'utilisation est plafonné à 100 %. Un sous-réseau sans aucune adresse enregistrée apparaît avec zéro adresse utilisée plutôt qu'avec une valeur absente.

---

## Assistant IP disponible

Dans le formulaire **Nouvelle adresse**, sélectionner un sous-réseau
active le bouton **Suggérer une IP disponible**.

Cliquer sur une IP proposée remplit automatiquement le champ adresse.

---

## Recherche d'adresses IP

Navigation : **Adresses IP**

Filtres disponibles : recherche libre, hostname, MAC.
La liste est paginée (25 adresses par page).

---

## Export

Boutons **↓ CSV** et **↓ Excel** disponibles sur les pages
**Adresses IP** et **Sous-réseaux**.

Les filtres actifs sont appliqués à l'export.

---

## Import d'adresses IP

Navigation : **Adresses IP** → section **Importer des adresses**

Formats supportés : **CSV** et **Excel XLSX**.
Format des colonnes documenté dans `IMPORT_FORMAT.md`.

Option **Remplacer les adresses existantes** :
- cochée → adresse existante mise à jour
- décochée → conflit signalé sans écraser

Un rapport s'affiche après chaque import avec les compteurs et
le détail des erreurs ligne par ligne.

---

## Authentification à deux facteurs (MFA)

Chaque compte local peut activer un second facteur (TOTP, compatible Google
Authenticator, Authy, etc.) depuis son profil. Le MFA est optionnel : il
n'est jamais imposé automatiquement. Les comptes gérés par LDAP ne sont pas
concernés.

### Activer le MFA

Navigation : **Profil → Authentification à deux facteurs (MFA) → Activer le MFA**

1. Scanner le QR code affiché avec une application d'authentification, ou
   saisir la clé manuellement si le scan n'est pas possible.
2. Saisir le code à 6 chiffres généré par l'application pour confirmer.
3. Noter immédiatement les **10 codes de récupération** affichés : ils ne
   seront plus jamais montrés ensuite. Les conserver dans un gestionnaire de
   mots de passe ou un coffre-fort, jamais en clair sur le poste de travail.

### Se connecter avec le MFA actif

- **Interface Web** : après identifiant et mot de passe, une page dédiée
  demande le code TOTP (ou un code de récupération).
- **API** : `POST /api/v1/auth/token` accepte un champ optionnel
  `totpCode` dans le corps de la requête :

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"votre-mot-de-passe","totpCode":"123456"}' \
  | jq .accessToken
```

Un code de récupération peut remplacer le code TOTP, aussi bien pour la
connexion que pour désactiver le MFA ou régénérer les codes. Chaque code de
récupération n'est utilisable qu'une seule fois.

### Désactiver le MFA ou régénérer les codes de récupération

Depuis **Profil**, deux actions sont disponibles une fois le MFA actif :
régénérer les 10 codes de récupération (invalide les anciens), ou désactiver
le MFA. Les deux exigent un code MFA valide ; la désactivation exige en plus
le mot de passe actuel.

### Perte d'accès (appareil et codes de récupération perdus)

Si un autre compte administrateur existe, il peut désactiver le MFA du
compte bloqué depuis l'écran de détail utilisateur. Si le compte bloqué est
le seul compte administrateur, contacter la personne en charge de
l'exploitation du serveur : voir la procédure de secours dans
`backend/docs/ADMIN_GUIDE.md`.

---

## Administration des utilisateurs

Navigation : **Administration** (ROLE_ADMIN uniquement)

Rôles disponibles :

| Rôle | Droits |
|---|---|
| `ROLE_ADMIN` | Accès complet, gestion des utilisateurs |
| `ROLE_READ_ONLY` | Consultation des données des contextes autorisés |
| `ROLE_NETWORK` | Création et modification des contextes, sites, VLAN, sous-réseaux et scans |
| `ROLE_IP` | Création, modification, suppression, import et export d'adresses IP |

Les rôles peuvent être cumulés. Un utilisateur `ROLE_NETWORK` + `ROLE_IP`
administre l'exploitation réseau et les adresses IP sans accès à
l'administration de l'application.

Pour un compte d'automatisation en lecture seule, attribuer `ROLE_READ_ONLY`
et limiter son périmètre aux contextes nécessaires.

---

## API REST

Documentation interactive : `http://localhost:8080/swagger-ui.html`

Obtenir un token :
```bash
curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"votre-mot-de-passe"}' \
  | jq .accessToken
```

Utiliser le token :
```bash
curl -s http://localhost:8080/api/v1/addresses \
  -H "Authorization: Bearer <token>"
```

---

## Sauvegarde

```bash
docker compose exec db pg_dump -U subnetory subnetory > backup.sql
```

Restauration :
```bash
docker compose exec -T db psql -U subnetory subnetory < backup.sql
```

---

## Variables d'environnement

| Variable | Description | Obligatoire |
|---|---|---|
| `SUBNETORY_ADMIN_DEFAULT_PASSWORD` | Mot de passe bootstrap du compte admin (premier démarrage) | Oui |
| `SUBNETORY_JWT_SECRET` | Secret HMAC JWT (≥ 32 caractères) | Oui |
| `SPRING_DATASOURCE_PASSWORD` | Mot de passe PostgreSQL | Oui |
| `SPRING_DATASOURCE_USERNAME` | Utilisateur PostgreSQL | Non (défaut : `subnetory`) |
| `SERVER_PORT` | Port HTTP | Non (défaut : `8080`) |
| `SUBNETORY_LDAP_ENABLED` | Activer l'auth LDAP | Non (défaut : `false`) |
| `SUBNETORY_JWT_EXPIRATION_MINUTES` | Durée de vie du token JWT | Non (défaut : `60`) |

---

## Dépannage

**L'application ne démarre pas**
```bash
docker compose logs app
```
Cause fréquente : variable `SUBNETORY_ADMIN_DEFAULT_PASSWORD` ou
`SUBNETORY_JWT_SECRET` absente dans `.env`.

**Erreur de connexion à la base de données**
```bash
docker compose ps
```
Attendre l'initialisation complète de PostgreSQL au premier démarrage.

**Migrations Flyway en erreur**
Vérifier les logs. S'assurer que les migrations V1–V5 sont toutes appliquées.

**Connexion admin refusée après modification de `.env`**
`SUBNETORY_ADMIN_DEFAULT_PASSWORD` n'est utilisé qu'au bootstrap initial.
Si la base est déjà initialisée, modifier ce paramètre ne change pas le
mot de passe existant. Utiliser le profil du compte ou une réinitialisation
administrateur.
