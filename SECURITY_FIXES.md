# Subnetory — Correctifs de sécurité appliqués

> Document de suivi des correctifs de sécurité appliqués au projet Subnetory.
> Base historique : v0.3.0 (commit f722f42). Dernière mise à jour : 04/08/2026 (v0.8.4).
> Correction du 30/07/2026 : F5 et F6, listés ci-dessous comme « reportés » depuis Sprint 2.29,
> sont en réalité déjà implémentés dans le code (voir détail plus bas) — ce document n'avait
> pas été remis à jour depuis. Aucun autre écart identifié entre ce document et le code réel.
> Correction du 04/08/2026 : le document était resté figé à Sprint 2.29 alors que huit
> correctifs supplémentaires (H4 à H5, M6 à M11) avaient été appliqués depuis sans jamais être
> journalisés ici — écart repéré par un audit externe. Voir `CHANGELOG.md` pour l'historique
> complet par version ; ce document reste le résumé orienté sécurité.
>
> Vérification de référence :
>
> ```powershell
> cd backend
> .\mvnw.cmd test
> ```
>
> Résultat attendu après v0.8.4 : voir le nombre de tests courant dans le dernier run CI
> (`test` job, `.github/workflows/ci.yml`) — ce document ne fige plus un chiffre daté.

---

## Résumé

| #  | Sévérité | Sujet                                                              | Fichiers touchés                                                                                                      | Risque de régression test                |
| -- | -------- | ------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| H1 | Élevé    | `X-Forwarded-For` forgeable (bypass rate limit + DoS ciblé)        | `ClientIpResolver.java`                                                                                               | Faible (bean mocké dans les IT)          |
| H2 | Élevé    | Pas de rate limiting sur `/api/v1/auth/token`                      | `AuthController.java`, `GlobalExceptionHandler.java`                                                                  | Faible à vérifier                        |
| H3 | Élevé    | Mot de passe initial ou temporaire utilisable sans renouvellement   | `V9__add_must_change_password.sql`, `DataInitializer.java`, `MandatoryPasswordChange*`, contrôleurs Web et API         | Moyen (authentification Web + API + JWT) |
| M1 | Moyen    | Pas de limite upload / lignes (DoS import, zip bomb XLSX)          | `application.yml`, `AddressCsvParser.java`, `AddressXlsxParser.java`                                                  | Faible                                   |
| M2 | Moyen    | Longueur du secret JWT non validée au démarrage                    | `SecurityConfig.java`                                                                                                 | Nulle (secret de test = 43 octets)       |
| M3 | Moyen    | Révocation JWT / logout API effectif par `jti`                     | `V7__create_revoked_tokens.sql`, `AuthController.java`, `SecurityConfig.java`                                         | Moyen (resource server + IT)             |
| M4 | Moyen    | Invalidation globale des tokens API par utilisateur (`not_before`) | `V8__create_user_token_invalidations.sql`, `UserTokenInvalidation*`, `SecurityConfig.java`, `AdminWebController.java` | Moyen (resource server + IT + GUI admin) |
| M5 | Moyen    | Validation explicite de l'issuer JWT (`iss=subnetory`)             | `SecurityConfig.java`, `JwtTokenService.java`                                                                         | Faible                                   |
| F2 | Faible   | `toLowerCase()` sans `Locale.ROOT`                                 | `PasswordPolicyService.java`                                                                                          | Nulle                                    |
| F3 | Faible   | Liste `common-passwords.txt` trop courte                           | `common-passwords.txt`                                                                                                | Nulle (entrées testées conservées)       |
| F4 | Faible   | Accesseur exposait le `Set` mutable                                | `PasswordPolicyService.java`                                                                                          | Nulle                                    |
| F5 | Faible   | Limitation des sessions concurrentes (`maximumSessions`)           | `SecurityConfig.java`                                                                                                 | Nulle                                    |
| F6 | Faible   | Échappement des jokers SQL LIKE (`%`, `_`, `\`)                     | `AddressSpecifications.java`                                                                                          | Nulle                                    |
| F7 | Faible   | Rate limiting généralisé sur `/api/v1/**`                          | `ApiRateLimiter.java`, `ApiRateLimitingFilter.java`, `SecurityConfig.java`, `application.yml`                          | Faible (seuil relevé en profil test)     |
| F8 | Faible   | Authentification à deux facteurs (MFA/TOTP) optionnelle             | `MfaService.java`, `MfaLoginChallengeService.java`, `MfaChallengeFilter.java`, `AuthController.java`, `SecurityConfig.java`, `V14__add_mfa_support.sql` | Moyen (authentification Web + API)       |
| —  | —        | Retrait du `.env` (secrets dev) de l'archive                       | `.env` supprimé                                                                                                       | Aucune (gitignoré)                       |
| H4 | Élevé    | Contournement du rate limiting MFA côté Web (04/08/2026)            | `RateLimitingAuthenticationSuccessHandler.java`, `MfaChallengeWebController.java`                                     | Faible (tests dédiés ajoutés)            |
| H5 | Élevé    | Fuite entre contextes au déplacement d'un Site/VLAN (03/08/2026)    | `SiteService.java`, `VlanService.java`, `SubnetService.java`, `AddressService.java`                                   | Faible (tests dédiés ajoutés)            |
| M6 | Moyen    | Rotation `--force` des Secrets Helm effaçait les clés de chiffrement (04/08/2026) | `init-helm-secrets.sh`, `init-helm-secrets.ps1`                                                          | Nulle (scripts hors app)                 |
| M7 | Moyen    | Compteur `LoginRateLimiter` non atomique sous concurrence (03/08/2026) | `LoginRateLimiter.java`                                                                                            | Nulle (test de concurrence ajouté)       |
| M8 | Moyen    | Préfixe CIDR de confiance non validé (0–32) dans `ClientIpResolver` (03/08/2026) | `ClientIpResolver.java`                                                                                    | Nulle                                    |
| M9 | Moyen    | Limite d'import CSV/XLSX partagée avec celle des sauvegardes (03/08/2026) | `ImportFileValidator.java`, `application.yml`                                                                   | Nulle                                    |
| M10 | Moyen   | Consommation non atomique d'un code de récupération MFA (03/08/2026) | `MfaService.java`, `MfaRecoveryCodeRepository.java`                                                                  | Nulle (test dédié ajouté)                |
| M11 | Moyen   | Déplacement d'un sous-réseau sans garde-fou context/site/CIDR (03/08/2026) | `SubnetService.java`, `AddressRepository.java`, `SubnetRepository.java`                                         | Faible (tests dédiés ajoutés)            |

---

## Détail des correctifs

### H1 — ClientIpResolver : modèle de proxy de confiance

**Avant :** l'en-tête `X-Forwarded-For` (forgeable par n'importe quel client) était toujours préféré. Un attaquant pouvait :

* contourner le rate limiting en changeant l'en-tête à chaque requête ;
* verrouiller un tiers en usurpant son IP.

**Après :** par défaut (`subnetory.security.trusted-proxy=false`), seul `getRemoteAddr()` — la source TCP réelle, non falsifiable — est utilisé.

Les en-têtes ne sont lus que si :

1. `subnetory.security.trusted-proxy=true` ;
2. la connexion directe provient d'une plage déclarée dans `subnetory.security.trusted-proxy-cidrs`, par exemple `10.0.0.0/8,172.16.0.0/12`.

**Configuration optionnelle, défaut sûr :**

```yaml
subnetory:
  security:
    trusted-proxy: false
    trusted-proxy-cidrs: ""
```

> Action de déploiement : si Subnetory tourne derrière Nginx/Traefik, mettre `trusted-proxy=true` et renseigner l'IP ou la plage du proxy dans `trusted-proxy-cidrs`. Sinon le rate limiting comptera toutes les requêtes derrière le proxy comme venant d'une seule IP.

La logique CIDR IPv4 a été validée sur les cas principaux : plages `/8`, `/12`, `/16`, `/0`, IP exacte et IPv6 en fail-safe. IPv6 n'est pas géré par le matching CIDR et retombe en non-confiance, ce qui est le comportement sûr.

---

### H2 — Rate limiting sur l'endpoint API d'authentification

**Avant :** `LoginRateLimitingFilter` ne couvrait que `POST /login` côté Web. `POST /api/v1/auth/token` permettait donc un brute-force illimité.

**Après :** `AuthController` applique la même politique que le login Web via `LoginRateLimiter` :

* IP verrouillée : `429 Too Many Requests` immédiat ;
* échec : compteur incrémenté, audit `LOGIN_FAILURE`, délai au-delà du seuil ;
* 10 échecs : audit `LOGIN_LOCKED` + `429 Too Many Requests` ;
* succès : reset du compteur, audit `LOGIN_SUCCESS`.

Un handler `ResponseStatusException` a été ajouté au `GlobalExceptionHandler` pour que le `429` ne soit pas transformé en `500` par le handler générique.

Le comportement sur un échec isolé reste `401 Unauthorized`, ce qui conserve la compatibilité avec les tests existants comme `OpenApiIT.authToken_wrongPassword_returns401`.

---

### H3 — Changement obligatoire du mot de passe initial ou temporaire

**Avant :** le mot de passe de bootstrap du compte administrateur ou un mot de passe temporaire défini lors d'une réinitialisation administrative pouvait rester utilisable sans renouvellement obligatoire. Un utilisateur authentifié avec cette valeur conservait l'accès normal à l'interface Web et pouvait obtenir un JWT.

**Après :** le Sprint 2.29 introduit un état persistant de changement obligatoire pour les comptes locaux.

Migration ajoutée :

```text
backend/src/main/resources/db/migration/V9__add_must_change_password.sql
```

La colonne ajoutée est :

```sql
must_change_password BOOLEAN NOT NULL DEFAULT FALSE
```

Le défaut `FALSE` préserve les comptes existants lors d'une mise à niveau.

Comportements couverts :

* lorsque `DataInitializer` initialise le hash BCrypt du compte local `admin`, il positionne `mustChangePassword=true` sans journaliser le mot de passe en clair ;
* une réinitialisation administrative d'un compte local active le changement obligatoire, invalide les JWT déjà émis et conserve l'audit de l'opération ;
* les comptes LDAP sont explicitement exclus du mécanisme de mot de passe local ;
* l'état est relu en base pendant la session Web, de sorte qu'une réinitialisation administrative bloque également une session déjà ouverte ;
* après authentification Web, l'utilisateur concerné est redirigé vers `/profile/change-password-required` ;
* tant que l'état reste actif, les autres fonctions Web sont bloquées, à l'exception de l'écran de changement, de la déconnexion, des erreurs et des ressources statiques nécessaires ;
* après un changement valide respectant la politique de mot de passe, l'état est désactivé et l'accès normal est restauré ;
* `POST /api/v1/auth/token` refuse toute émission de JWT avec HTTP 403 et un `ProblemDetail` portant le code `PASSWORD_CHANGE_REQUIRED` ;
* aucun token n'est retourné avant la régularisation du mot de passe.

Ce contrôle concerne les comptes locaux. Il ne modifie pas l'authentification ni le cycle de vie des mots de passe LDAP.

---

### M1 — Limites d'import

Des limites applicatives ont été ajoutées pour borner les imports CSV/XLSX :

* `spring.servlet.multipart.max-file-size: 5MB` ;
* `spring.servlet.multipart.max-request-size: 10MB` ;
* `AddressCsvParser.MAX_DATA_ROWS = 50000` ;
* `AddressXlsxParser.MAX_DATA_ROWS = 50000`.

Au-delà de la limite de lignes, l'import est rejeté proprement via une exception métier.

Cela réduit le risque de DoS par upload massif, CSV excessif ou fichier XLSX fortement compressé.

---

### M2 — Validation du secret JWT au démarrage

`SecurityConfig` valide au démarrage que le secret JWT fait au moins 32 octets, conformément au niveau attendu pour HS256 / 256 bits.

Si le secret est trop court, le démarrage de l'application est refusé avec un message explicite.

Le secret de test respecte cette contrainte, ce qui évite toute régression sur la suite Maven.

---

### M3 — Révocation JWT / logout API par `jti`

**Avant :** un token JWT émis par `POST /api/v1/auth/token` restait valide jusqu'à son expiration naturelle, même si le client voulait se déconnecter ou si le token était compromis.

**Après :** chaque nouveau token porte un identifiant standard `jti`. L'endpoint `POST /api/v1/auth/logout` ajoute ce `jti` dans la table `revoked_tokens`.

Le `JwtDecoder` API vérifie ensuite cette denylist et refuse tout token révoqué avec un `401 Unauthorized`.

Migration ajoutée :

```text
backend/src/main/resources/db/migration/V7__create_revoked_tokens.sql
```

Configuration de purge :

```yaml
subnetory:
  jwt:
    revocation:
      purge:
        enabled: true
        cron: "0 30 3 * * *"
```

La purge supprime uniquement les entrées dont `expires_at` est passé. Le scheduler ne se déclenche pas au démarrage ; il suit le cron configuré.

Compatibilité : les tokens émis avant le Sprint 2.26 ne portent pas de `jti`. Ils restent acceptés jusqu'à leur expiration naturelle et ne sont pas révocables. La fenêtre est bornée par `subnetory.jwt.expiration-minutes` : 60 minutes par défaut.

Audit : un logout effectif ajoute l'événement `TOKEN_REVOKED` dans `auth_audit_log`.

---

### M4 — Invalidation globale des tokens API par utilisateur

**Avant :** la révocation Sprint 2.26 permettait d'invalider un token précis via son `jti`, mais ne permettait pas d'invalider en une seule action tous les tokens API déjà émis pour un utilisateur.

Un changement de mot de passe ou une action administrateur ne rendait donc pas automatiquement invalides les tokens API antérieurs encore valides.

**Après :** le Sprint 2.27 ajoute une invalidation globale par utilisateur via un seuil `not_before` stocké en base.

Migration ajoutée :

```text
backend/src/main/resources/db/migration/V8__create_user_token_invalidations.sql
```

Table ajoutée :

```text
user_token_invalidations
```

Principe :

* chaque utilisateur peut avoir un seuil `not_before` ;
* tout token dont le `iat` est antérieur à ce seuil est rejeté ;
* si une entrée d'invalidation existe et qu'un token ne porte pas de claim `iat`, le token est rejeté en fail-closed ;
* le seuil est rattaché au sujet JWT, sans clé étrangère vers `users`, pour rester compatible avec des sujets externes ou LDAP.

Actions couvertes :

* `POST /api/v1/auth/logout-all` : invalidation globale des tokens API du sujet courant ;
* action administrateur GUI `POST /admin/users/{id}/invalidate-tokens` ;
* invalidation automatique lors du changement de mot de passe utilisateur ;
* invalidation automatique lors d'une réinitialisation de mot de passe par un administrateur.

Audit :

```text
TOKENS_INVALIDATED
```

Comportement attendu :

* les nouveaux tokens émis après le seuil restent valides ;
* les anciens tokens deviennent invalides ;
* un token déjà révoqué par `jti` et également antérieur au seuil `not_before` est rejeté proprement en `401 Unauthorized`, sans erreur serveur ;
* une seconde action `logout-all` avec le même ancien token retourne `401 Unauthorized`, car le token appelant est lui-même devenu invalide.

Limite connue :

* la précision de `iat` est à la seconde. Un token émis exactement à la même seconde que le seuil peut rester accepté si `iat == not_before`. Les tests utilisent une attente contrôlée pour couvrir les cas strictement antérieurs.

Important :

* cette invalidation concerne les tokens API JWT ;
* les sessions Web GUI ne sont pas invalidées par ce mécanisme.

---

### M5 — Validation explicite de l'issuer JWT

**Avant :** le décodeur JWT API validait la signature et les contraintes standard, mais l'issuer attendu n'était pas explicitement documenté comme critère de validation.

**Après :** le `JwtDecoder` API applique une validation explicite de l'issuer attendu :

```text
iss=subnetory
```

Le service d'émission de tokens positionne l'issuer `subnetory`, et le resource server refuse les tokens dont le claim `iss` est absent ou différent.

Ce durcissement réduit le risque d'acceptation d'un token signé avec le bon secret mais émis dans un autre contexte applicatif.

Comportement attendu :

* token avec `iss=subnetory` : accepté si les autres validateurs passent ;
* token sans `iss` : rejeté ;
* token avec un autre issuer : rejeté ;
* les tests de sécurité restent alignés avec l'issuer attendu.

---

### F2 / F4 — PasswordPolicyService

Correctifs appliqués :

* toutes les comparaisons de casse utilisent `Locale.ROOT`, ce qui corrige les comportements dépendants de la locale système ;
* `getLoadedCommonPasswords()` retourne une vue non modifiable, ce qui empêche la modification externe du `Set` interne.

---

### F5 — Limitation des sessions concurrentes

`SecurityConfig` positionne `.maximumSessions(5)` dans la gestion de session de la chaîne Web (`sessionManagement`), avec redirection vers `/login?expired` en cas de session invalide.

Une même identité ne peut donc pas ouvrir un nombre illimité de sessions Web concurrentes.

Limite connue : le seuil (5) est une valeur fixe, non configurable par propriété. Pas de nécessité identifiée à ce jour de le rendre paramétrable.

---

### F6 — Échappement des jokers SQL LIKE

`AddressSpecifications.containsPattern()` échappe explicitement les caractères spéciaux de l'opérateur `LIKE` (`\`, `%`, `_`) avant toute recherche texte (hostname, adresse, MAC, réseau, site, contexte, source de découverte), avec le caractère d'échappement `\` déclaré explicitement dans chaque appel `cb.like(...)`.

Une valeur utilisateur contenant `%` ou `_` est donc traitée comme du texte littéral et ne peut pas élargir la recherche au-delà de l'intention de l'utilisateur.

---

### F7 — Rate limiting généralisé sur `/api/v1/**`

**Avant :** seuls `POST /login` (Web) et `POST /api/v1/auth/token` (correctif H2) étaient soumis à un rate limiting. Le reste de l'API authentifiée (`/api/v1/addresses`, `/api/v1/subnets`, imports en masse, etc.) n'avait aucune limite de débit par IP.

**Après :** un second rate limiter, `ApiRateLimiter`, s'applique à l'ensemble de `/api/v1/**` et `/actuator/**` (chaîne `apiFilterChain`, `@Order(1)`), en amont de la validation JWT :

* compteur in-memory par IP (via `ClientIpResolver`, correctif H1), fenêtre fixe ;
* seuil et fenêtre configurables, par défaut généreux pour ne pas gêner un usage normal :

```yaml
subnetory:
  security:
    api-rate-limit:
      enabled: true
      requests-per-window: 300
      window-seconds: 60
```

* au-delà du seuil : `429 Too Many Requests`, corps `application/problem+json`, en-tête `Retry-After` ;
* `/actuator/health` et `/actuator/health/**` sont explicitement exemptés (sondes de disponibilité) ;
* complémentaire à `LoginRateLimiter` (H2), qui reste le mécanisme dédié aux tentatives d'authentification avec un seuil plus strict.

Limite connue : fenêtre fixe (pas de fenêtre glissante ni de token bucket), cohérent avec le choix déjà fait pour `LoginRateLimiter`. Suffisant pour une architecture mono-réplique ; ne serait plus adapté sans état partagé (Redis ou équivalent) en cas de passage multi-instance, hors périmètre actuel du projet.

**Correctif de cohérence associé (hors CVE, hygiène) :** `ApiRateLimitingFilter` étant un `@Component` (bean de type `Filter`), Spring Boot l'auto-enregistrait *aussi* comme filtre servlet générique sur `/*`, en plus de son rattachement explicite à `apiFilterChain` via `addFilterBefore` — chaque requête traversait le filtre deux fois, doublant le comptage auprès d'`ApiRateLimiter`. Corrigé par un `FilterRegistrationBean` désactivé (`setEnabled(false)`) dans `SecurityConfig`, pattern documenté par Spring Boot pour ce cas précis.

`LoginRateLimitingFilter` (correctif T4/H2, préexistant) présentait la même exposition structurelle, vérifiée et corrigée par le même mécanisme dans la foulée. Son impact fonctionnel était nul avant correction : ce filtre ne fait qu'une lecture pure (`isLocked`) sans effet de bord, les échecs de connexion étant comptabilisés ailleurs (`RateLimitingAuthenticationFailureHandler`, invoqué une seule fois par Spring Security). Corrigé par cohérence et pour éliminer l'exécution redondante du filtre sur l'intégralité des requêtes.

---

### F8 — Authentification à deux facteurs (MFA/TOTP)

**Avant :** l'authentification reposait uniquement sur le mot de passe (local) ou l'annuaire (LDAP). Un mot de passe compromis (phishing, réutilisation, fuite tierce) suffisait à obtenir un accès complet.

**Après (Sprint 2.37) :** chaque compte local peut activer un second facteur TOTP (RFC 6238) depuis sa page de profil, optionnel et jamais imposé globalement :

* enrôlement self-service (`/profile/mfa/setup`) : génération d'un secret, QR code, confirmation par un premier code valide avant activation effective ;
* 10 codes de récupération à usage unique, hachés en bcrypt, affichés une seule fois, utilisables en substitut du TOTP (login et actions self-service) ;
* login Web : redirection systématique vers `/login/mfa` après identifiants corrects tant que le second facteur n'est pas vérifié pour la session (`MfaChallengeFilter`, même patron que `MandatoryPasswordChangeFilter`) ;
* login API : `POST /api/v1/auth/token` accepte un champ optionnel `totpCode` ; absent ou invalide → `401` avec un code dédié (`MFA_REQUIRED` / `MFA_INVALID`), cohérent avec la politique anti-énumération existante ;
* les tentatives de code TOTP/récupération sont soumises au même rate limiting par IP que le mot de passe (`LoginRateLimiter`, correctif H2/F7, aucun compteur séparé) ;
* anti-lockout administrateur : `POST /api/v1/admin/users/{id}/disable-mfa` permet à un ADMIN de désactiver le MFA d'un compte tiers bloqué (appareil TOTP perdu, codes de récupération épuisés) ;
* le secret TOTP est chiffré au repos via `SecretCipherService` (AES/GCM, réutilisation de la brique déjà utilisée pour `LdapSettings.managerPasswordEncrypted`), aucune nouvelle primitive cryptographique introduite ;
* tous les événements MFA sont audités : `MFA_ENABLED`, `MFA_DISABLED`, `MFA_RECOVERY_CODES_REGENERATED`, `MFA_CHALLENGE_FAILED`, `MFA_DISABLED_BY_ADMIN`.

Limite connue : TOTP standard uniquement, pas de WebAuthn/FIDO2 dans ce sprint. Le MFA s'applique aux comptes locaux (`authType=LOCAL`) ; l'articulation avec LDAP reste à définir séparément si besoin. Si le titulaire d'un compte admin unique perd à la fois son mot de passe et son second facteur (device et codes de récupération), aucune récupération en libre-service n'existe (pas d'email) : voir la procédure de secours documentée dans `backend/docs/ADMIN_GUIDE.md`.

---

### F3 — Liste common-passwords élargie

La liste `common-passwords.txt` a été élargie de 92 à 225 entrées uniques.

Les entrées utilisées par les tests existants sont conservées, notamment :

* `Password123!` ;
* `Subnetory1234`.

Des mots de passe courants, saisonniers et orientés infrastructure ont été ajoutés.

Limite connue : l'approche par exact-match ne détecte pas toutes les variantes, par exemple `Password123!!`. Une future amélioration pourrait reposer sur une vérification de type HIBP par k-anonymity.

---

### H4 — Contournement du rate limiting MFA côté Web

**Avant :** `RateLimitingAuthenticationSuccessHandler`, invoqué par Spring Security immédiatement après validation du mot de passe (avant toute vérification du second facteur), remettait inconditionnellement à zéro le compteur `LoginRateLimiter` et journalisait `LOGIN_SUCCESS` — même quand le compte a le MFA activé et que la connexion n'est donc pas terminée. `MfaChallengeWebController` n'incrémentait le compteur qu'en cas de code TOTP invalide. Un attaquant connaissant le mot de passe pouvait alterner « re-soumission du formulaire de login » (reset gratuit du compteur) et « un essai de code TOTP », sans jamais atteindre le seuil de verrouillage sur le second facteur — brute-force TOTP illimité avec un mot de passe compromis. Le parcours API (`POST /api/v1/auth/token`) n'était pas concerné : il applique déjà le bon principe (reset différé après validation MFA effective).

**Après :** `RateLimitingAuthenticationSuccessHandler` interroge `MfaLoginChallengeService.isRequired(username)` ; si le MFA est requis, le reset du compteur et l'audit `LOGIN_SUCCESS` sont différés jusqu'à la vérification effective du second facteur, désormais effectués par `MfaChallengeWebController#verify` sur code valide — même principe que côté API.

Tests ajoutés : régression multi-cycles (`MfaLoginBypassRegressionTest`) reproduisant l'alternance « login valide → code MFA invalide » jusqu'au seuil de verrouillage sur les deux composants réels (pas de mock du rate limiter), plus des tests unitaires ciblés sur chaque composant.

---

### H5 — Fuite entre contextes lors du déplacement d'un Site ou d'un VLAN

Voir `CHANGELOG.md`, section `[0.8.1]`, entrée « Fuite entre contextes lors du déplacement d'un site ou d'un VLAN » pour le détail complet (cause, correctif en deux plans, portée). Résumé : `Subnet`/`Address` stockent leur propre `context_id`, jamais resynchronisé après un changement de contexte du `Site` ou de site du `VLAN` parent ; corrigé en bloquant ces changements tant que des sous-réseaux existent encore, et en filtrant en plus par le contexte propre de chaque sous-réseau retourné (défense en profondeur).

---

### M6 — Rotation Helm `--force` effaçait les clés de chiffrement optionnelles

**Avant :** `init-helm-secrets.sh`/`.ps1`, en mode `--force`, remplaçait intégralement (`kubectl replace`, pas un patch) le Secret runtime avec uniquement `jwt-secret` et `postgres-password`. Si le Secret contenait déjà `encryption-key` (chiffrement des secrets LDAP/MFA en base) ou `backup-encryption-key` (chiffrement des sauvegardes), une rotation effaçait ces clés silencieusement — secrets LDAP/MFA et sauvegardes déjà chiffrées devenus illisibles.

**Après :** avant tout remplacement, le script relit les clés déjà présentes dans le Secret existant et réinjecte telles quelles celles qui ne sont pas explicitement régénérées par l'appel en cours.

---

### M7 — `LoginRateLimiter` : compteur non atomique sous concurrence

Voir `CHANGELOG.md`, section `[0.8.2]`. Section critique désormais synchronisée par instance de tentative (une par clé IP/utilisateur), champs `volatile` pour la visibilité hors verrou. Test de concurrence ajouté (plusieurs threads sur la même clé, aucun incrément perdu).

---

### M8 — `ClientIpResolver` : préfixe CIDR de confiance non validé

Voir `CHANGELOG.md`, section `[0.8.2]`. Un préfixe hors bornes (`/33`, négatif) pouvait produire un masque à 0 via décalage de bits modulo 64, traitant toute IP comme proxy de confiance — exploitable uniquement via une faute de configuration opérateur, désormais refusé au démarrage.

---

### M9 — Limite d'import CSV/XLSX partagée avec celle des sauvegardes

Voir `CHANGELOG.md`, section `[0.8.2]`. `ImportFileValidator` réutilisait la limite `spring.servlet.multipart.max-file-size` (200 Mo, dimensionnée pour les dumps de sauvegarde) pour les imports CSV/XLSX, alors qu'`XSSFWorkbook` (Apache POI, non-streaming) charge tout le fichier en mémoire. Nouvelle propriété dédiée `subnetory.import.max-file-size` (défaut 10 Mo).

---

### M10 — Consommation non atomique d'un code de récupération MFA

Voir `CHANGELOG.md`, section `[0.8.2]`. Lecture puis `save()` séparés permettaient à deux authentifications concurrentes de consommer toutes deux le même code à usage unique. `MfaRecoveryCodeRepository.markUsedIfUnused` effectue désormais une mise à jour conditionnelle atomique (`UPDATE ... WHERE used_at IS NULL`).

---

### M11 — Déplacement d'un sous-réseau sans garde-fou context/site/CIDR

Voir `CHANGELOG.md`, section `[0.8.1]`, entrée « Déplacement d'un sous-réseau sans garde-fou ». `SubnetService.update` refuse désormais tout changement de contexte/site/CIDR si le sous-réseau a encore des adresses, et tout changement de contexte/CIDR s'il a encore des sous-réseaux enfants.

---

### Retrait du `.env`

Le fichier `.env`, présent dans certaines copies de travail mais déjà ignoré par Git, contenait des secrets de développement en clair.

Il est exclu des archives de reprise. Seul `.env.example`, avec des placeholders, est conservé.

Action recommandée :

* considérer les anciennes valeurs de développement comme compromises ;
* ne jamais les réutiliser ;
* générer un secret JWT aléatoire d'au moins 32 caractères pour tout environnement réel.

---

## Reportés

Aucun point reporté actuellement. F5 et F6, précédemment listés ici, sont implémentés (voir tableau récapitulatif et détail ci-dessus).

---

## Points de vérification après `mvn test`

Si un test échoue, vérifier dans l'ordre :

1. **Encodage** : les fichiers réécrits doivent être en UTF-8 sans BOM. Si Maven signale `illegal character: '\ufeff'`, appliquer le correctif PowerShell habituel du projet.
2. **H2 / suite IT complète** : le `LoginRateLimiter` est un singleton in-memory partagé entre les classes de test si elles tournent dans la même JVM. Chaque classe IT ne fait normalement qu'au plus un échec d'authentification, donc le seuil de 10 ne devrait jamais être atteint. Si un `429` inattendu apparaît, réinitialiser explicitement l'état en `@BeforeEach` ou rendre le seuil configurable en profil test.
3. **AuthController** : aucune instanciation directe ne doit court-circuiter l'injection Spring. Les nouvelles dépendances doivent être résolues par le contexte Spring.
4. **ClientIpResolver** : les IT le mockent via `@MockBean`, donc le changement d'implémentation ne doit pas affecter leurs assertions.
5. **JWT `jti`** : vérifier que les tokens émis par `POST /api/v1/auth/token` portent bien un `jti`.
6. **JWT `iat`** : vérifier que les tokens API portent bien un `iat`, nécessaire au validateur `not_before`.
7. **JWT `iss`** : vérifier que les tokens API portent bien `iss=subnetory`.
8. **Interaction Sprint 2.26 / 2.27** : un token à la fois révoqué par `jti` et antérieur au seuil `not_before` doit retourner `401 Unauthorized`, sans erreur serveur.
9. **Migration V9** : vérifier la présence de `users.must_change_password`, avec `FALSE` pour les comptes existants et `TRUE` après initialisation ou réinitialisation locale.
10. **Blocage Web obligatoire** : vérifier la redirection vers `/profile/change-password-required`, y compris pour une session déjà ouverte après reset administrateur.
11. **Blocage JWT obligatoire** : vérifier HTTP 403, `application/problem+json`, le code `PASSWORD_CHANGE_REQUIRED` et l'absence de token avant changement.
12. **Comptes LDAP** : vérifier qu'ils restent exclus du mécanisme de mot de passe local.

---

## Fichiers modifiés

```text
backend/src/main/java/dev/subnetory/security/ClientIpResolver.java                     (H1)
backend/src/main/java/dev/subnetory/api/v1/AuthController.java                         (H2, M3, M4)
backend/src/main/java/dev/subnetory/exception/GlobalExceptionHandler.java              (H2)
backend/src/main/java/dev/subnetory/config/SecurityConfig.java                         (M2, M3, M4, M5, F5)
backend/src/main/java/dev/subnetory/security/JwtTokenService.java                      (M3, M5)
backend/src/main/java/dev/subnetory/config/DataInitializer.java                       (H3)
backend/src/main/java/dev/subnetory/domain/User.java                                  (H3)
backend/src/main/java/dev/subnetory/service/MandatoryPasswordChangeService.java       (H3)
backend/src/main/java/dev/subnetory/security/MandatoryPasswordChangeFilter.java       (H3)
backend/src/main/java/dev/subnetory/security/RateLimitingAuthenticationSuccessHandler.java (H3)
backend/src/main/java/dev/subnetory/exception/PasswordChangeRequiredException.java    (H3)
backend/src/main/java/dev/subnetory/web/ProfileWebController.java                     (H3)
backend/src/main/resources/db/migration/V9__add_must_change_password.sql              (H3)
backend/src/main/java/dev/subnetory/security/JwtLogoutDecoder.java                     (M3)
backend/src/main/java/dev/subnetory/security/RevokedTokenValidator.java                (M3)
backend/src/main/java/dev/subnetory/domain/RevokedToken.java                           (M3)
backend/src/main/java/dev/subnetory/repository/RevokedTokenRepository.java             (M3)
backend/src/main/java/dev/subnetory/service/RevokedTokenPurgeService.java              (M3)
backend/src/main/java/dev/subnetory/service/RevokedTokenPurgeScheduler.java            (M3)
backend/src/main/resources/db/migration/V7__create_revoked_tokens.sql                  (M3)
backend/src/main/java/dev/subnetory/security/UserTokenInvalidationValidator.java        (M4)
backend/src/main/java/dev/subnetory/domain/UserTokenInvalidation.java                  (M4)
backend/src/main/java/dev/subnetory/repository/UserTokenInvalidationRepository.java     (M4)
backend/src/main/java/dev/subnetory/service/UserTokenInvalidationService.java           (M4)
backend/src/main/java/dev/subnetory/web/AdminWebController.java                        (M4)
backend/src/main/resources/db/migration/V8__create_user_token_invalidations.sql        (M4)
backend/src/main/resources/application.yml                                             (M1, M3)
backend/src/main/resources/security/common-passwords.txt                               (F3)
backend/src/main/java/dev/subnetory/repository/AddressSpecifications.java              (F6)
backend/src/main/java/dev/subnetory/security/ApiRateLimiter.java                       (F7)
backend/src/main/java/dev/subnetory/security/ApiRateLimitingFilter.java                (F7)
backend/src/main/java/dev/subnetory/service/MfaService.java                            (F8)
backend/src/main/java/dev/subnetory/service/MfaLoginChallengeService.java              (F8)
backend/src/main/java/dev/subnetory/security/MfaChallengeFilter.java                   (F8)
backend/src/main/java/dev/subnetory/domain/MfaRecoveryCode.java                        (F8)
backend/src/main/resources/db/migration/V14__add_mfa_support.sql                       (F8)
backend/.env                                                                          (supprimé de l'archive)
backend/src/main/java/dev/subnetory/web/MfaChallengeWebController.java                (H4)
scripts/init-helm-secrets.sh                                                          (M6)
scripts/init-helm-secrets.ps1                                                         (M6)
backend/src/main/java/dev/subnetory/repository/MfaRecoveryCodeRepository.java         (M10)
backend/src/main/java/dev/subnetory/service/SubnetService.java                        (M11)
backend/src/main/java/dev/subnetory/repository/AddressRepository.java                 (M11)
backend/src/main/java/dev/subnetory/repository/SubnetRepository.java                  (M11)
backend/src/main/java/dev/subnetory/service/SiteService.java                          (H5)
backend/src/main/java/dev/subnetory/service/VlanService.java                          (H5)
backend/src/main/java/dev/subnetory/util/ImportFileValidator.java                     (M9)
```

Des tests unitaires et d'intégration couvrent désormais :

* le rate limiting de l'authentification API ;
* la présence du `jti` ;
* le logout API par révocation de `jti` ;
* la purge des tokens révoqués expirés ;
* l'invalidation globale par utilisateur ;
* les cas fail-closed sans `iat` ;
* les cas limites `iat` / `not_before` ;
* l'action administrateur GUI avec POST + CSRF ;
* les hooks de changement et de réinitialisation de mot de passe ;
* l'interaction entre révocation `jti` et invalidation globale `not_before` ;
* l'initialisation et la persistance de `mustChangePassword` ;
* la redirection et le blocage Web jusqu'au changement obligatoire ;
* la réinitialisation locale avec invalidation des JWT et l'exclusion LDAP ;
* le refus JWT `PASSWORD_CHANGE_REQUIRED`, puis l'émission après changement valide.
