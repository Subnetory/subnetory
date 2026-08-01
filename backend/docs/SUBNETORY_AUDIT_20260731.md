# Audit complet Subnetory v2 — 31 juillet 2026

Audit réalisé en une nuit, en cinq passes indépendantes en lecture seule (aucun fichier modifié) : bugs/pièges techniques backend, cohérence CSS/design/UX, fiabilité date/heure au-delà du fix de ce soir, sécurité offensive façon pentester, et regard produit/métier (chef d'entreprise, admin système, utilisateur final). Ce document consolide et priorise l'ensemble.

Contexte du soir : un bug de décalage horaire de 2h a été trouvé et corrigé (trois causes cumulées : JVM du conteneur en UTC, session PostgreSQL en UTC, Hibernate 6 qui ignore silencieusement `hibernate.jdbc.time_zone` par défaut). Commit `b4aa4ed`, poussé, CI verte sur le job `test`. Ce fix a servi de point de départ pour l'audit date/heure ci-dessous, qui a creusé beaucoup plus loin.

---

## Résumé exécutif — à traiter en premier

Par ordre de priorité réelle (impact × probabilité), indépendamment de la catégorie d'origine :

1. **Secret JWT/mot de passe admin de dev qui peut écraser silencieusement les secrets forts en prod** (`docker-compose.prod.yml` + `.env` local) — le risque de sécurité le plus sérieux trouvé, corrigible en 10 minutes.
2. **Aucun verrouillage optimiste sur les entités métier** (`Address`, `Subnet`, etc.) — deux admins qui modifient la même fiche en même temps s'écrasent silencieusement, sans message, sans trace. Risque concret dès qu'on est plus d'une personne à opérer l'outil.
3. **Le fix timezone de ce soir n'est pas complet sur tous les chemins de déploiement** — le chart Helm Kubernetes et `docker-compose.prod.yml` (PostgreSQL externe) peuvent reproduire exactement le même bug de 2h.
4. **Deux bugs CSS qui cassent visuellement quasi toute l'application** — `.sn-btn--secondary` et `.sn-alert--success`/`.sn-alert--error` sont utilisés dans des dizaines de fichiers mais n'existent pas dans `app.css`. Les boutons secondaires et les messages de confirmation/erreur n'ont donc aucun style. Corrigible en quelques lignes CSS.
5. **`GlobalExceptionHandler` s'applique aux routes web sans logging serveur** — masque les erreurs à la fois côté navigateur (déjà connu, cf. les 5 pièges POST/redirect ci-dessous) et côté logs (nouveau constat) : un futur bug de ce type sera aussi difficile à diagnostiquer côté serveur que côté client.
6. **5 occurrences confirmées du piège POST/redirect** déjà identifié une fois ce soir sur la page de réservation — corrections mécaniques, pattern déjà écrit et éprouvé.
7. **Aucun historique de modification par objet métier** (qui a changé cette IP, quand, pourquoi) — le facteur de confiance n°1 d'un IPAM, absent aujourd'hui.
8. **MFA : rate limiting par IP seulement, pas par compte** — un attaquant qui a déjà le mot de passe peut distribuer un bruteforce TOTP sur plusieurs IP sans jamais déclencher de verrou compte.

Tout le reste (accessibilité, formats de date incohérents, commentaires manquants, fonctionnalités "nice to have") est detaillé plus bas mais n'est pas bloquant pour une mise en production.

---

## 1. Pièges POST/redirect (backend)

### Mécanisme

`NavigationModelAdvice` (`backend/src/main/java/dev/subnetory/web/NavigationModelAdvice.java`) peuple à chaque requête web l'attribut `currentRequestPath` avec l'URL courante. `layout/base.html` (lignes 27-42) l'utilise dans deux formulaires (sélecteur de contexte, reset de contexte) via un champ caché `returnTo`, présents sur **chaque page**. `ContextSelectionController` redirige ensuite vers cette URL (validation anti-open-redirect correcte).

**Le piège** : si un handler `@PostMapping` rend une vue directement (sans `redirect:`) sur une URL sans `@GetMapping` jumeau, `currentRequestPath` reste bloqué sur cette URL POST-only. La prochaine utilisation du sélecteur de contexte déclenche un GET dessus → `HttpRequestMethodNotSupportedException` (405) → masqué en 500 générique par le catch-all de `GlobalExceptionHandler`.

Déjà corrigé ce soir sur `AddressWebController` (réservation multiple). Un audit précédent (session antérieure) avait signalé "5 autres occurrences" sans les documenter précisément — c'est fait maintenant.

### Les 5 occurrences confirmées (sévérité : important)

| # | Fichier | Ligne | Méthode | Déclencheur |
|---|---|---|---|---|
| 1 | `web/SiteWebController.java` | 131 | `update()` POST `/network/sites/{id}` | Erreur de validation ou conflit de nom sur l'édition d'un site |
| 2 | `web/SubnetWebController.java` | 425 | `update()` POST `/network/subnets/{id}` | Erreur de validation ou sous-réseau déjà existant |
| 3 | `web/VlanWebController.java` | 138 | `update()` POST `/network/vlans/{id}` | Erreur de validation ou VID déjà existant |
| 4 | `web/NetworkContextWebController.java` | 109 | `update()` POST `/network/contexts/{id}` | Erreur de validation ou nom déjà existant |
| 5 | `web/ProfileWebController.java` | 206 | `regenerateMfaRecoveryCodes()` POST `/profile/mfa/recovery-codes/regenerate` | Succès — l'écran affichant les nouveaux codes est rendu directement. Le plus gênant des 5 : l'utilisateur perd l'écran de ses codes de récupération sans pouvoir y revenir. |

**Correction** : répliquer le pattern déjà utilisé sur `AddressWebController.prepareReserveModel()` — surcharger `model.addAttribute("currentRequestPath", <url GET valide>)` avant chaque `return` concerné. Mécanique, pas de refonte.

### Trouvaille plus importante que les 5 occurrences elles-mêmes

`GlobalExceptionHandler` (`backend/src/main/java/dev/subnetory/exception/GlobalExceptionHandler.java:32`) est un `@RestControllerAdvice` **sans scoping** — il s'applique à tous les contrôleurs, y compris les contrôleurs web Thymeleaf. Toutes ses réponses sont du JSON (`ProblemDetail`), jamais les templates `error/403.html`/`404.html`/`500.html` qui existent pourtant dans le projet mais ne sont atteints que si un contrôleur web attrape l'exception lui-même. Toute exception web non interceptée localement (dont les 5 cas ci-dessus, ou n'importe quelle exception imprévue) part donc en JSON brut au lieu d'une page d'erreur HTML.

**Encore plus important** : **aucune des 15 méthodes `@ExceptionHandler` n'appelle un logger.** Le handler générique 500 convertit l'exception en réponse propre pour le client mais ne trace rien côté serveur (pas de stack trace, pas de message). Sans ce logging, le prochain incident de ce type sera aussi difficile à diagnostiquer côté serveur qu'un 500 masqué l'était côté navigateur ce soir.

**Correction recommandée en priorité** :
- Restreindre `GlobalExceptionHandler` aux contrôleurs API (`@RestControllerAdvice(basePackages = "dev.subnetory.api")`), ajouter un handler dédié pour les routes web qui utilise les templates `error/*.html` déjà écrits.
- Ajouter un `Logger` et au minimum `log.warn(...)` sur les 4xx et `log.error(ex.getMessage(), ex)` sur le catch-all 500.

### Autres constats backend

- **Autorisations** : cohérentes entre API et Web sur toutes les ressources comparées (sites/subnets/vlans/adresses). Aucune divergence trouvée.
- **CSRF** : sain — tous les formulaires utilisent `th:action`, Spring Security injecte le token automatiquement. À reconfirmer une fois en dynamique si un doute subsiste (audit statique uniquement).
- **N+1 / volumétrie** :
  - `AddressService.bulkUpsert()` (`service/AddressService.java:267-280`) : 2 requêtes DB par ligne importée (recherche adresse + résolution subnet), y compris pour un `subnetId` répété des centaines de fois. Impact réel sur les gros imports CSV/XLSX. *Correction* : précharger les subnets référencés en un seul `findAllById` avant la boucle.
  - `UserAdminService.resolveRoles()/resolveContexts()` : boucles avec `findById` individuel — impact négligeable vu les volumes (mineur).
  - Dropdowns de formulaires (`Pageable.unpaged()` sur sites/subnets/vlans/contexts quand aucun contexte actif) : pas de souci au volume actuel, à surveiller si le nombre de sous-réseaux croît significativement.
- **Code mort / TODO** : recherche exhaustive — **aucun** TODO/FIXME/XXX oublié dans le code Java. Base propre sur ce point.
- **MfaChallengeWebController.applyDelay()** bloque le thread servlet avec `Thread.sleep()` pour le délai anti-bruteforce — acceptable à faible trafic, à surveiller si l'app est un jour exposée plus largement (starvation de threads sous flood).

---

## 2. CSS / Design system / UX

### Bugs critiques (visibles sur presque toutes les pages)

**`.sn-btn--secondary` n'existe pas en CSS** mais est utilisé dans 18 fichiers / 23 occurrences (`contexts.html`, `sites.html`, `vlans.html`, `subnets.html`, `addresses.html`, tous les formulaires réseau, `admin/*.html`, `profile*.html`, `help.html`...). Tous les boutons "Modifier"/"Annuler" retombent sur `.sn-btn` nu (transparent) au lieu d'un vrai bouton stylé, alors que le bouton primaire juste à côté est un bloc plein. C'est l'incohérence la plus répandue de toute l'application.
→ Ajouter la classe manquante dans `app.css`, en reprenant la définition de `.sn-btn--ghost` (qui, elle, est correcte et définie).

**`.sn-alert--success` et `.sn-alert--error` n'existent pas en CSS** mais sont utilisés dans 22 fichiers / 34 occurrences. Seuls `.sn-alert--info` et `.sn-alert--danger` sont définis. Tous les messages de confirmation/erreur après une action s'affichent donc sans code couleur — pas de vert, pas de rouge. `auth/change-password-required.html` utilise `sn-alert--error` (cassé) alors que `auth/login.html` utilise `sn-alert--danger` (correct) pour un message équivalent.
→ Ajouter deux alias CSS pointant vers `--success`/`--danger` déjà définis dans `:root` (option la plus rapide vu le nombre d'occurrences).

**`.sn-card__title` défini deux fois avec des propriétés qui fusionnent mal** (lignes 445-452 et 1166-1173 de `app.css`). Le résultat est un hybride : texte en majuscules avec `letter-spacing` (du premier bloc) mais taille et couleur du second, **plus une bordure et une marge fantômes ajoutées sous chaque titre** — y compris dans des cartes qui ont déjà leur propre séparateur via `.sn-card__header`. Touche toutes les pages utilisant le pattern moderne recommandé : formulaires réseau migrés ce soir, `dashboard.html`, `subnet-scan.html`, `admin/ldap.html`, `profile*.html`, et même des titres de page nus (`auth/login-mfa.html`, `auth/change-password-required.html`) où la bordure apparaît sans carte pour la justifier.
→ Supprimer le second bloc (1166-1173) ; si le style "admin" (plus grand, avec séparateur) est voulu, créer un modificateur dédié plutôt que de redéfinir le sélecteur de base.

### Bugs importants

- **Classes utilisées mais jamais définies (résidus morts)** : `.sn-badge--danger` (badge d'échec de connexion dans `admin/audit-log.html`, sans couleur d'alerte), `.sn-card__subtitle` (`admin/user-detail.html`), `.sn-card__actions`/`.sn-scan-panel`/`.sn-terminal-card` (`subnet-scan.html`), `.sn-form-help` (`subnet-scan.html` — la vraie classe est `.sn-form-hint`), `.sn-profile-header`. Passage de nettoyage rapide à faire une fois.
- **Deux systèmes de header coexistent visuellement** : `layout/base.html` utilise le riche `.sn-appbar` moderne, mais `error/403.html`/`404.html`/`500.html` et les pages `auth/*.html` utilisent l'ancien `.sn-navbar` — précisément les pages de première/dernière impression (erreur, connexion) ont un habillage plus daté que le reste.
- **Ligne "IP temporaire" dans `address-form.html`** : au-delà de la question de nommage (legacy vs moderne, choix assumé faute d'équivalent), c'est un vrai défaut de rythme vertical mesurable — 36px d'espacement après cette ligne contre 16px uniforme partout ailleurs dans le même formulaire (cumul de `gap` du parent + `margin-bottom` hérité du legacy). → Un correctif utilitaire ciblé suffit.
- **`admin/user-form.html`/`user-detail.html`** : titres de carte sans `.sn-card__header`, qui ne fonctionnent que par un couplage fragile et non documenté (`.sn-admin-grid > .sn-card { padding }`). `admin/ldap.html`, dans la même section admin, utilise correctement `.sn-card__header`. → Harmoniser.
- **Formulaires legacy non migrés qui continuent de dériver** : `subnet-scan.html` mélange moderne et un champ isolé en legacy (bug de classe, pas juste incohérence). `admin/ldap.html` est presque entièrement en legacy sauf sa structure de carte. Ce sont deux pages non touchées ce soir qui illustrent exactement le risque de dérive que tu avais signalé — le double système continue de se propager au lieu de converger.
- **Astérisque "requis" purement visuel** : sur les 5 formulaires réseau, les champs marqués `*` n'ont ni `required` ni `aria-required` sur l'input — pas de validation native, rien pour un lecteur d'écran. `admin/user-form.html`, lui, le fait bien. → Ajouter `required` là où c'est déjà marqué visuellement.

### Bugs fonctionnels visibles

- **Barre d'utilisation des sous-réseaux sur le dashboard toujours à 100% vert** (`dashboard.html:117-123`) : la classe `sn-util-bar__fill--low` est codée en dur, sans largeur ni classe conditionnelle selon `entry.utilizationPct`. Le pourcentage textuel est correct, la barre visuelle ment. → `th:style` avec la largeur réelle + classe conditionnelle selon seuils (les 3 classes CSS low/medium/high existent déjà).
- **Export CSV/XLSX incohérent** : présent sur `subnets.html`/`addresses.html`, absent sur `contexts.html`/`sites.html`/`vlans.html`/`admin/users.html`, différent sur `admin/audit-log.html`. Pas forcément un manque (listes courtes), mais à documenter comme choix assumé plutôt que laisser paraître un oubli.
- **`import-result.html`** gère son état vide sans la classe `sn-table--empty` utilisée partout ailleurs — rendu visuellement différent sur cette page précise.

### Mineur

- Fautes d'accents systématiques sur un sous-ensemble de templates non touchés ce soir (`admin/user-detail.html`, `auth/login-mfa.html`, `profile*.html`, etc. — "Cree le", "Roles", "Desactiver"...) alors que les templates migrés ce soir et `admin/users.html` sont correctement accentués. Cosmétique mais visible au quotidien.
- Règles CSS dupliquées avec conflits silencieux : `.sn-mb-2` (défini deux fois, la seconde ajoute `display:inline-block` qui peut casser un bouton `inline-flex` combiné) ; `.sn-inline-form` (deux `display` contradictoires). 4 usages de `!important` (bataille de spécificité). Nettoyage à faible risque.
- Contraste du texte "muted" (`--color-text-muted: #9aa3b0`) probablement sous le seuil WCAG AA — à vérifier avec un outil de contraste.
- Pas de skip-link avant la nav (mineur, accessibilité clavier).
- `th:formaction` non commenté sur `address-reserve.html` alors que c'est exactement le genre de pattern non-évident qui mérite une ligne d'explication.
- Point positif à noter : les media queries existent bel et bien (contrairement à l'hypothèse de départ) — breakpoints cohérents sur l'essentiel des pages, tableaux en `overflow-x:auto` plutôt que du responsive complexe — choix pragmatique cohérent avec la philosophie du projet.
- Confirmations avant suppression : bien câblées et cohérentes partout via `data-confirm` (`app.js`) — pas de trou détecté.

### Verdict design

Le socle du design system est solide là où il est bien appliqué, et les 6 templates migrés ce soir sont propres. Mais `.sn-btn--secondary` et `.sn-alert--success`/`--error` manquants cassent visuellement la quasi-totalité des boutons secondaires et des messages de confirmation/erreur de l'app — ce sont les éléments qu'un utilisateur croise à chaque interaction. Corrections réalisables en une session, pas une refonte, mais indispensables avant toute release présentée à un tiers.

---

## 3. Date/heure — au-delà du fix de ce soir

### Ce qui est confirmé sain (pas d'action nécessaire)

- Tous les usages `Instant.now()` (RFC7807, rate limiting) sont volontairement indépendants du fuseau — c'est le comportement correct pour une API.
- JWT (`AuthController.java:310`, `OffsetDateTime.ofInstant(..., ZoneOffset.UTC)`) : correct et volontaire, un `exp` JWT est un epoch UTC par nature, l'affichage web/API de l'expiration n'a aucune incohérence puisque le web n'affiche aucun timestamp d'expiration (seulement une durée relative côté API).
- TOTP/MFA : basé sur l'epoch Unix (RFC 6238), structurellement indépendant du fuseau. Aucun risque lié au fix.
- Schéma DB : toutes les colonnes temporelles sont en `TIMESTAMPTZ` (V1 à V14), aucune colonne `TIMESTAMP` sans zone — le fix `NORMALIZE` ne peut donc pas avoir d'effet secondaire inattendu ailleurs.
- Cas limites DST sur les cron jobs de purge (audit 02h30, JWT 03h30) : impact nul même en cas de double-déclenchement ou de saut, car les opérations de purge sont idempotentes.

### Trouvailles importantes

**Le fix de ce soir n'est pas complet sur tous les chemins de déploiement.** La couche 2 du fix (fuseau de session PostgreSQL, `command: postgres -c timezone=...`) n'existe que dans `docker-compose.yml` (base embarquée) :
- **`docker-compose.prod.yml`** (PostgreSQL externe) : rien ne rappelle à l'opérateur que le serveur PostgreSQL externe doit être configuré avec le même fuseau — un déploiement contre un Postgres managé resté en UTC par défaut (cas très courant) **reproduirait exactement le bug corrigé ce soir**.
- **Chart Helm (`charts/subnetory/templates/postgresql-statefulset.yaml`)** : le PostgreSQL embarqué du chart n'a **ni `command -c timezone=...` ni `TZ`/`PGTZ`**. Les couches 1 (JVM, via l'image) et 3 (Hibernate, via le jar) sont couvertes automatiquement pour Kubernetes, mais **la couche 2 manque** — c'est très probablement un oubli de synchronisation entre le fix docker-compose et le chart Helm. C'est le seul chemin de déploiement où le bug exact d'hier peut réapparaître à l'identique.
→ Ajouter au StatefulSet Postgres du chart le même `-c timezone=...` (idéalement une valeur Helm commune réutilisée par le Deployment app et le StatefulSet Postgres), et documenter l'exigence pour `docker-compose.prod.yml`.

**Incohérence de convention (pas un bug) sur le CronJob de backup Helm** : `backup.timeZone` par défaut à `Etc/UTC` (`values.yaml:126`), alors que les cron jobs applicatifs (purge audit/JWT) tournent maintenant en heure française. Un admin qui lit "0 2 * * *" en pensant heure française se trompera d'1-2h. → Aligner le défaut sur `Europe/Paris` ou documenter clairement que c'est en UTC.

**Aucune expiration automatique pour les adresses "temporaires"** (`Address.temporary`) : le champ est un simple booléen positionné à la création, jamais purgé ni notifié après un délai. Si l'intention produit est qu'une IP temporaire redevienne libre après X jours, cette logique est totalement absente (pas de scheduler, pas de colonne `expires_at`). Signalé aussi côté produit/métier ci-dessous.

### Mineur

- Incohérence de format d'affichage (pas de risque de fuseau) : `addresses.html` affiche `dd/MM/yy` (année 2 chiffres) pour `lastSeenAt`, alors que `address-detail.html` utilise `dd/MM/yyyy` pour le même champ. → Harmoniser sur `dd/MM/yyyy HH:mm`.
- `UserTokenInvalidationService.java:33` code en dur `ZoneOffset.UTC` là où ~15 autres endroits utilisent `OffsetDateTime.now()` sans zone explicite — correct fonctionnellement mais incohérence de style qui mérite un commentaire.
- Scripts PowerShell de backup (`backend/scripts/*.ps1`) : s'exécutent sur l'horloge de l'hôte Windows, hors périmètre du fix Docker. Pas de bug (rotation basée sur un compte de fichiers, pas sur des dates), mais à documenter : l'hôte doit être en heure française pour une correspondance intuitive des noms de fichiers avec les journées applicatives.
- Fenêtre de tolérance TOTP non explicitement configurée (défaut librairie ±30s) — sujet de synchronisation NTP indépendant du fix, mentionné pour complétude.

### Verdict date/heure

Le fix de ce soir est correct et bien conçu pour le déploiement `docker-compose.yml` standard. Le sujet n'est pas totalement clos : **traiter le chart Helm en priorité avant tout déploiement Kubernetes en production**, c'est la seule zone où le bug exact d'hier peut réapparaître à l'identique.

---

## 4. Sécurité — regard attaquant

Audit statique uniquement. Points déjà validés lors d'un audit antérieur et non ré-audités en détail : CSRF (sain), autorisations API/Web cohérentes, rate limiting sur `/api/v1/auth/token`.

### Trouvaille la plus importante

**Précédence "variable d'environnement > secret fichier" combinée à un `.env` de dev contenant des secrets faibles et prévisibles.** `backend/.env` (présent localement, non commité) contient :
```
SUBNETORY_JWT_SECRET=subnetory-dev-jwt-secret-20260626-change-me
SUBNETORY_ADMIN_DEFAULT_PASSWORD=Admin-Dev-2026!
```
`docker-compose.prod.yml` déclare ces variables en entrées `environment:` "bare" (sans valeur), qui héritent automatiquement du `.env` du répertoire courant si celui-ci traîne à côté du fichier compose au moment d'un `docker compose -f docker-compose.prod.yml up -d`. Un secret JWT statique et devinable écraserait alors silencieusement le secret fort généré par `init-compose.sh`. **Scénario concret** : ce `.env` de dev circule (archive, poste de dev, vieux commit) ; s'il est un jour réutilisé pour un déploiement rapide en prod (urgence, mono-dev), un attaquant qui retrouve cette chaîne exacte peut forger n'importe quel JWT `ROLE_ADMIN` valide sans jamais s'authentifier — compromission totale.
→ Retirer `SUBNETORY_JWT_SECRET`/`SUBNETORY_ADMIN_DEFAULT_PASSWORD` de `environment:` dans `docker-compose.prod.yml` (garder uniquement les `secrets:` fichiers), documenter explicitement le risque en tête de fichier, et faire tourner ce secret de dev puisqu'il a circulé hors contrôle de version.

### Important

**MFA : rate limiting par IP uniquement, pas par compte.** Le défi MFA (TOTP + recovery code) réutilise le même `LoginRateLimiter` que l'échec de mot de passe, **clé par IP**. Un attaquant qui possède déjà le mot de passe d'un compte cible peut distribuer un bruteforce TOTP (1 000 000 de combinaisons) sur plusieurs IP — chacune dispose de 9 essais avant verrou — sans qu'aucune défense au niveau du compte ne se déclenche jamais.
→ Ajouter un compteur d'échecs MFA par (username, jour), complémentaire au verrou IP existant — ajout ciblé, pas une refonte.

**Pas de plafond explicite sur le nombre de lignes d'un import CSV/XLSX.** Le plafond de 5 Mo n'empêche pas ~150-250k lignes en mémoire puis en boucle transactionnelle. Nécessite un rôle ADMIN/IP (pas pré-auth), donc impact limité, mais peut ralentir la base pour tout le monde le temps de l'import.
→ Ajouter une constante `MAX_ROWS` (ex. 20 000) dans les parsers CSV/XLSX.

**Attribution Apache 2.0 non respectée** (détaillé section 6, dupliqué ici car c'est aussi un sujet de conformité) : le copyright original d'Adrezo (POSTEC Yann) a été effacé sans `NOTICE` de remplacement — manquement documenté à l'article 4(c)/(d) de la licence, même si le risque de poursuite réel est faible (licence permissive).

### Confirmé sain (bon travail déjà en place)

- **IDOR** : vérifié en profondeur sur tous les endpoints API et Web par `{id}` — `contextAccessService.requireResourceAccess()` systématiquement appliqué, y compris sur les recherches par IP/hostname (pas seulement par ID). Aucun trouvé.
- **Injection de formule Excel (CSV injection)** : déjà corrigée (`CsvSafeValue.protect()`), neutralise `=`, `+`, `-`, `@`, tab, CR en tête de valeur, appliquée systématiquement.
- **Path traversal import** : aucun risque, le nom de fichier n'est jamais utilisé pour construire un chemin disque.
- **XSS** : zéro usage de `th:utext` dans tous les templates ; zéro `innerHTML` avec données utilisateur dans `app.js`.
- **Open redirect** : le seul mécanisme de redirection piloté par paramètre utilisateur dans toute l'app (`ContextSelectionController.safeReturnTo()`) est déjà validé sain ; le login n'accepte aucun paramètre de redirection.
- **Injection LDAP** : le filtre est transmis via l'API JNDI paramétrée, qui échappe automatiquement les métacaractères — aucune concaténation manuelle trouvée.
- **Intégrité du journal d'audit** : aucune méthode de suppression/modification accessible depuis un contrôleur — un admin compromis ne peut pas effacer ses traces via l'UI/API.
- **Endpoints actuator** : seuls `health`/`info` exposés, rien de sensible (`env`, `heapdump`, etc.) enregistré côté serveur.
- **Break-glass admin** : aucun mécanisme HTTP pré-authentification pour réinitialiser un mot de passe — seul l'accès direct base de données documenté existe.

### Verdict sécurité

Si j'étais un attaquant externe avec juste une URL et un compte normal, je chercherais d'abord un vieux `.env`/backup qui traînerait avec le secret JWT de dev — c'est la voie la plus rapide vers une compromission totale si elle existe hors de ce dépôt gitignored. À défaut, je tenterais un bruteforce TOTP distribué sur des comptes à fort privilège (seul point structurellement faible identifié). IDOR, XSS, injection LDAP, open redirect sont verrouillés de façon cohérente et systématique — pas de temps à perdre là-dessus. Le point d'entrée le plus réaliste reste humain/opérationnel (mauvaise gestion d'un `.env` de dev) plutôt qu'une faille de code.

---

## 5. Fiabilité des données — le point aveugle le plus significatif

**Aucun verrouillage optimiste sur les entités métier.** Recherche exhaustive de `@Version` dans `domain/` : aucune occurrence sur `Address`, `Subnet`, `Site`, `Vlan`, `NetworkContext`, `User`. Si deux admins modifient la même fiche en même temps, le dernier `POST` écrase silencieusement le premier — aucune détection, aucun message, aucune trace (l'audit log ne couvre que les événements d'authentification, pas les modifications métier). Risque concret dès qu'il y a plus d'une personne à opérer l'outil en simultané.
→ Ajouter `@Version private Long version;` au minimum sur `Address` et `Subnet`, mapper `OptimisticLockException` en message clair ("Cette fiche a été modifiée entre-temps, rechargez la page"). Réalisable en une session, risque technique faible.

**Aucun historique de modification par objet métier.** La fiche d'une adresse IP n'affiche que le *dernier* modificateur et la dernière date — pas "qui a créé cette IP, qui l'a réassignée le mois dernier, pourquoi". Le seul journal d'audit existant est réservé aux événements de sécurité (login, mot de passe), pas aux objets métier. C'est exactement le "pourquoi cette IP est prise" qui fait la confiance dans un IPAM au quotidien, et c'est absent. Lié au point précédent : ce même historique donnerait aussi une base gratuite pour détecter les écrasements concurrents.
→ Voir idée de différenciation n°1 ci-dessous — faisable en 2-3 jours pour `Address`/`Subnet` seuls.

**Migrations Flyway** : propres, numérotées sans trou (V1 à V14), `baseline-on-migrate: false` (choix sûr). Pas de garde-fou systématique sur les migrations qui modifient une contrainte existante (ex. `V11` change une contrainte d'unicité globale vers un scope par sous-réseau — potentiellement destructeur si des doublons préexistaient). → Ajouter un commentaire d'avertissement standard sur ce type de migration.

---

## 6. Regard produit/métier — 3 personas

### Chef d'entreprise

- **Sauvegarde réellement testée en CI** (job `backup-safety`, restore drill hostile) — rare pour un projet de cette taille, un vrai argument de vente ("chaque commit valide qu'on peut restaurer").
- **Pas de sauvegarde hors-site par défaut** : tout reste sur le même hôte Docker. Si le serveur meurt (disque, incendie, ransomware), backups compris, tout est perdu. → Documenter/scripter un export vers un stockage externe en option "one command" (rclone, copie réseau planifiée).
- **Licence Apache 2.0, pas de problème de compatibilité** (Adrezo est lui-même Apache 2.0, vérifié directement sur le repo amont — pas de risque GPL). Mais **incohérence de posture** : `pom.xml` qualifie encore Subnetory de "fork modernisé du projet Adrezo" pendant que la doc interne affirme "nouveau projet" et qu'un sprint a délibérément supprimé les dernières mentions Adrezo. → Trancher une fois pour toutes : soit c'est une réécriture clean-room (retirer "fork" de partout), soit des éléments repris subsistent (ajouter un `NOTICE` minimal). Vu la réécriture complète Java/Spring vs JSP/Tomcat d'origine, la première option semble être la réalité.
- **Différenciation crédible face à phpIPAM/NetBox/Netdisco**, déjà en place : déploiement single-jar/portable Windows sans JRE à installer, scan réseau intégré nativement dans l'image Docker (documenté avec honnêteté sur ses limites), MFA/TOTP self-service avec procédure de secours documentée (NetBox n'a pas ça nativement), API-first complet et documenté (parité UI/API), auth hybride locale+LDAP.
- **Capacités "table stakes" manquantes** : aucune intégration DNS/DHCP, aucun webhook sortant. Pas besoin de construire une intégration DHCP/DNS complète (hors philosophie du projet), mais un webhook sortant simple serait un ajout à fort ROI (voir différenciation ci-dessous).

### Administrateur système

- **Première installation** : flux propre (`init-compose` génère tous les secrets automatiquement), moins de 15 minutes pour quelqu'un qui connaît Docker. Point de friction mineur : le changement de mot de passe obligatoire au premier login n'est mentionné que dans `USER_GUIDE_MVP.md`, pas dans le Quick Start du `README.md` racine.
- **Observabilité limitée en dehors des logs bruts** : seuls `health`/`info` exposés côté actuator, pas de `/actuator/metrics` ni export Prometheus. → Ajout à faible coût, gros gain (écosystème Spring Boot déjà prêt pour ça).
- **Migrations et robustesse infra** : propres (voir section 5). nmap absent produit un message clair côté web plutôt qu'un crash. Une coupure PostgreSQL transitoire fait échouer les requêtes en cours mais l'app se rétablit seule (pool Hikari standard) — non documenté nulle part, un admin pourrait paniquer et redémarrer inutilement. → Une ligne dans la doc ops suffit.
- **Création utilisateur/rôle/périmètre** : fluide, un seul formulaire complet, vrai point fort ergonomique.

### Utilisateur final (technicien réseau)

- **Aide contextuelle orientée développeur** : `help.html` ne contient que Swagger/curl/actuator — rien sur "comment créer mon premier sous-réseau" pour un non-dev, alors que ce contenu existe déjà dans `USER_GUIDE_MVP.md` mais n'est accessible qu'en fouillant le repo Git. → Faire le pont depuis `help.html` (lien ou inline des 5 premières étapes).
- **Messages d'erreur métier déjà bien traduits et actionnables** côté web ("Cette adresse IP est déjà enregistrée.") — bon point, aucune exception brute ne remonte à l'utilisateur GUI.
- **Réservation multiple desktop-only** : le tableau d'édition inline à 5 colonnes est pertinent au bureau mais peu praticable sur téléphone en salle serveur — acceptable tant que ce n'est pas présenté comme mobile-friendly (la réservation d'une seule IP, elle, reste utilisable sur mobile).
- Pas d'avertissement de perte de travail (`beforeunload`) sur ce même formulaire long — le seul écran où une navigation accidentelle ferait vraiment mal.

### Idées de différenciation ("faire ce que les autres ne font pas")

1. **Historique par objet métier, léger, affiché sur la fiche** — résout à la fois la confiance utilisateur (persona final) et donne une base pour détecter les écrasements concurrents (persona admin). Une seule table `object_change_log` (type, id, champ, ancienne/nouvelle valeur, auteur, date), alimentée dans les services qui posent déjà `modifiedBy`/`updatedAt`. Faisable en 2-3 jours pour `Address`/`Subnet` seuls.
2. **Verrouillage optimiste (`@Version`) + message de conflit explicite** — cf. section 5, 1-2 jours pour les entités les plus disputées.
3. **Webhook sortant simple sur les événements adresse** (create/update/delete/reserve, POST HTTP configurable avec secret HMAC) — ouvre la porte à Slack/n8n/scripts DNS internes sans que Subnetory ait à connaître DHCP/DNS. 3-4 jours.
4. **"Pourquoi cette IP est prise" visible directement dans la liste**, pas seulement la fiche détail — donnée déjà en base, juste pas exposée en colonne. Coût quasi nul, gain UX important sur la tâche la plus fréquente d'un IPAM.
5. **Widget "santé backup" dans le dashboard admin**, lisant le statut déjà produit par `check-postgres-backup.ps1` — transforme un script PowerShell (invisible pour un non-Windows ou un chef d'entreprise) en signal de confiance visible dans l'UI. 1-2 jours, aucune nouvelle logique de sauvegarde à écrire.

### Verdict produit

Subnetory v2 est plus mature que sa description "MVP" ne le laisse penser : sauvegardes testées en CI, gestion des secrets soignée, MFA self-service avec anti-lockout, parité API/UI documentée — une vraie rigueur produit rare chez un développeur seul. Les trous les plus sérieux pour une v1.0 sont concentrés sur un seul point aveugle, déjà détaillé section 5 : l'absence d'historique de modification et de verrouillage optimiste. Avec 2-3 sprints ciblés sur historique + verrouillage optimiste + un webhook sortant minimal, Subnetory passerait d'un IPAM "solide et sûr" à un IPAM "digne de confiance au quotidien" — le critère qui fait qu'une équipe adopte un outil plutôt que de rester sur un tableur.

---

## Synthèse — plan d'action suggéré

**À faire avant toute mise en production réelle (quelques heures de travail au total) :**
- Retirer les secrets bare de `docker-compose.prod.yml`, faire tourner le secret JWT de dev qui a circulé.
- Ajouter `.sn-btn--secondary` et les alias `.sn-alert--success`/`--error` dans `app.css`.
- Corriger le fuseau PostgreSQL dans le chart Helm (`-c timezone=...`) avant tout déploiement Kubernetes.
- Scoper `GlobalExceptionHandler` aux routes API + ajouter le logging serveur manquant.

**À faire dans les prochaines semaines (2-3 sessions ciblées) :**
- Les 5 corrections POST/redirect (mécaniques, pattern déjà écrit).
- Rate limiting MFA par compte en plus de l'IP.
- Verrouillage optimiste sur `Address`/`Subnet`.
- Fusion du `.sn-card__title` dupliqué + nettoyage des classes CSS mortes.
- `NOTICE` Apache 2.0 + décision tranchée sur le narratif "fork" vs "nouveau projet".

**Backlog produit, à planifier selon les priorités business :**
- Historique par objet métier.
- Webhook sortant.
- Widget santé backup dans le dashboard.
- Sauvegarde hors-site.
- Aide contextuelle orientée utilisateur final (pont vers `USER_GUIDE_MVP.md`).

Rien dans cet audit ne remet en cause la faisabilité ou la direction du projet — c'est une liste de corrections ciblées et de compléments, pas une refonte.
