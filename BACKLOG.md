# Backlog Subnetory

Dernière mise à jour : 1 octobre 2026
Référence auditée : `v0.8.13` / code applicatif `a27cb65`

## Ordre d'exécution

1. Auditer l'intégralité de l'API actuelle à partir du contrat OpenAPI.
2. Exécuter le script PowerShell réutilisable contre l'instance locale.
3. Créer des données réalistes et vérifier qu'au moins une adresse IP est assignée dans chaque contexte.
4. Vérifier les autorisations, validations, réponses d'erreur et événements du journal d'audit.
5. Ajouter au présent backlog tout défaut supplémentaire découvert.
6. Attendre le « go » explicite avant toute correction applicative.
7. Corriger ensuite les éléments du backlog, puis rejouer la totalité des tests.

## Audit API — TERMINÉ LE 8 SEPTEMBRE 2026

- [x] Inventorier toutes les opérations exposées par `/v3/api-docs` : 80 opérations REST sous `/api/v1`.
- [x] Couvrir chaque opération pertinente par un scénario positif et les principaux scénarios négatifs : 159 scénarios réussis sur 159 dans la stack Docker isolée finale.
- [x] Vérifier l'authentification JWT, les rôles, les périmètres de contexte, les statuts HTTP et les formats de réponse.
- [x] Tester les opérations destructives uniquement avec des ressources jetables, y compris sauvegarde, import, restauration et purge.
- [x] Vérifier les événements d'audit avant et après chaque mutation couverte.
- [x] Créer `Siège`, `Datacenter` et `Agences`, puis un site, un VLAN, un sous-réseau et une adresse dans chacun des quatre contextes, y compris `Default`.
- [x] Reproduire ces données dans l'instance Docker locale : tableau de bord vérifié à `4 contextes / 4 sites / 4 VLAN / 4 sous-réseaux / 4 adresses` et événements de création présents dans le journal d'audit.
- [x] Produire et exécuter `scripts/audit-api.ps1`; preuves finales conservées localement dans `reports/api-audit-isolated-20260908-212003/` et exclues de Git car elles peuvent contenir des données d'inventaire.
- [x] Rejouer le chemin de succès du scan Nmap dans l'image Docker qui embarque Nmap : scan contrôlé d'un `/30` terminé avec HTTP 200 le 8 septembre 2026.

## Corrections applicatives — IMPLÉMENTÉES LE 8 SEPTEMBRE 2026

Une case cochée dans cette section signifie que le correctif est présent dans le code et couvert par les contrôles hors Docker appropriés. Les validations d'intégration, Compose et API sur l'image reconstruite restent suivies séparément dans les conditions de sortie.

### P1 — Priorité haute

- [x] **Jauge d'utilisation fausse sous CSP** — remplacer la largeur inline bloquée par la CSP par un composant compatible, puis tester le rendu et la valeur affichée.
- [x] **VLAN 0 accepté comme VLAN assignable** — imposer `1..4094` dans l'interface, les DTO, le domaine et PostgreSQL, avec migration et tests de non-régression.
- [x] **JWT neuf parfois immédiatement rejeté après changement de mot de passe** — `/auth/token` renvoie 200, mais le jeton émis dans la même seconde reçoit 401. Aligner la précision de `iat` et `user_token_invalidations.not_before`, puis couvrir les changements et réinitialisations de mot de passe.
- [x] **Chaque restauration attend inutilement le délai de drainage complet** — la requête `POST /api/v1/admin/backup/restore` est elle-même comptée parmi les mutations à drainer; les tests montrent toujours une mutation restante. Avec la valeur par défaut, toute restauration ajoute environ 65 secondes d'indisponibilité avant `pg_restore`.

### P2 — Priorité moyenne

- [x] **Capacité et pourcentage des sous-réseaux trompeurs** — exclure correctement réseau, broadcast et passerelle selon la métrique, préciser `/31` et `/32`, et éviter l'arrondi abusif à `0 %`.
- [x] **Mises à jour réseau non journalisées** — tracer les modifications de contexte, site, VLAN, sous-réseau et adresse (`PUT`/`PATCH`), actuellement absentes du journal.
- [x] **Upserts, imports et réservations multiples non journalisés** — tracer les créations et mises à jour provenant de l'interface, de l'upsert unitaire, du bulk upsert et des imports CSV/XLSX avec des événements de synthèse exploitables.
- [x] **Scans Nmap non journalisés** — tracer séparément les scans terminés et échoués, avec le sous-réseau, le résultat synthétique et la cause d'échec.
- [x] **Purge du journal d'audit sans trace inviolable** — après une purge totale réussie, le journal reste vide; conserver une preuve séparée/non purgeable ou imposer une politique équivalente adaptée à la production.
- [x] **JSON malformé ou incomplet transformé en erreur 500** — notamment l'omission du booléen obligatoire `temporary` dans `AddressRequest`; mapper `HttpMessageNotReadableException` vers une réponse 400 détaillée et stable.
- [x] **Texte `suffix` rendu littéralement** — corriger les compteurs des contextes, sites, VLAN, sous-réseaux, adresses, utilisateurs, réservations et sauvegardes, en français et en anglais.
- [x] **Dates et fuseaux mal formatés** — centraliser les formats localisés et corriger notamment l'affichage collé à `UTC+02:00`.
- [x] **Port HTTP publié sur toutes les interfaces** — écouter sur `127.0.0.1` par défaut et conserver une variable explicite pour les déploiements nécessitant un accès LAN.
- [x] **Créations proposées sans données parentes** — désactiver ou remplacer les actions VLAN, sous-réseau et adresse tant que le site ou le sous-réseau requis n'existe pas.

### P3 — Interface, accessibilité et cohérence

- [x] **Champs CSRF redondants** — supprimer seulement les champs manuels que Thymeleaf injecte déjà automatiquement et vérifier tous les formulaires POST.
- [x] **Français incorrect ou incomplet** — corriger les apostrophes interprétées par `MessageFormat`, les messages sans accents et les termes résiduels `Gateway` / `Subnet`. Ne pas réécrire les anciennes entrées d'audit.
- [x] **Événements de scan absents du filtre d'audit** — exposer `SUBNET_SCAN_COMPLETED` et `SUBNET_SCAN_FAILED` dans le menu de filtrage et verrouiller leur présence par un test d'intégration Web.
- [x] **Navigation bureau chevauchée autour de 1440 px** — basculer l'en-tête sur deux lignes dès 1500 px afin que le lien des adresses, le sélecteur de langue et le profil ne se superposent plus avec les libellés français.
- [x] **Réservation multiple peu accessible** — ajouter `scope="col"`, des noms accessibles contextualisés par adresse IP et des libellés utiles pour les actions répétées.
- [x] **Navigation mobile peu lisible** — réduire la hauteur de l'en-tête et remplacer les glyphes ambigus par une navigation compacte et accessible.
- [x] **Finitions UX** — libeller les filtres, retirer les titres répétés, améliorer la largeur des formulaires et guider les écrans vides dans l'ordre contexte → site → VLAN → sous-réseau → adresse.
- [x] **Actions administrateur impossibles encore cliquables** — masquer ou désactiver les opérations interdites sur son propre compte ou sur le dernier administrateur actif, sans retirer les protections serveur.
- [x] **Pagination JSON non stable** — ne plus sérialiser directement `PageImpl`; activer le mode DTO paginé stable ou publier un DTO explicite.
- [x] **Route Web publiée dans le contrat REST** — exclure `GET /network/subnets/{id}/available-ips` du document OpenAPI destiné à `/api/v1`.
- [x] **Description contradictoire du rôle BACKUP** — `assignable-roles` renvoie `ROLE_BACKUP` tout en le décrivant comme « Rôle non attribuable. ».
- [x] **Contrat d'upsert d'adresse contradictoire** — la description annonce 201 lors d'une création, tandis que le contrôleur et OpenAPI renvoient/documentent uniquement 200.

### Dette technique et durcissement

- [x] **Trois vulnérabilités critiques dans Tomcat embarqué détectées par la CI de développement** — mettre à niveau Spring Boot vers 4.1.1 et surcharger temporairement Tomcat en 11.0.25, première version corrigée pour `CVE-2026-65182`, `CVE-2026-65905` et `CVE-2026-68525`.
- [x] **API de validation et Testcontainers dépréciées** — utiliser `List<@Valid ...>` pour tous les emplacements de `BulkUpsertRequest` et `BulkReservationForm`, puis les constructeurs Testcontainers modernes fondés sur `DockerImageName`.
- [x] **Agent Mockito chargé dynamiquement** — configurer explicitement l'agent de test dans Maven avant une future incompatibilité JDK.
- [x] **Swagger/OpenAPI public en production** — ajouter une option documentée pour désactiver ou protéger la documentation sans gêner le développement local.
- [x] **Avertissement AuthenticationManager au démarrage** — confirmer que le fournisseur assemblé manuellement est intentionnel, puis supprimer la configuration redondante ou neutraliser explicitement l'avertissement.

## Conditions de sortie avant production

- [x] Tous les tests unitaires et d'intégration sont verts : 993/993.
- [x] Les seuils JaCoCo du build complet sont respectés.
- [x] Le script d'audit API couvre les 80/80 opérations et les 159 scénarios fonctionnels passent. Le dernier constat était uniquement une constante attendue mal nommée dans le script (`LDAP_CONFIG_UPDATED` au lieu de `LDAP_CONFIGURATION_UPDATED`), corrigée et recoupée avec deux événements réels.
- [x] Les événements sensibles attendus sont présents dans le journal d'audit : 74 mutations sondées, y compris changement de mot de passe initial, scan Nmap, restauration et purge.
- [x] Le démarrage Docker Compose et les healthchecks application/PostgreSQL sont validés sur la stack isolée.
- [x] Le parcours Web FR/EN est validé sur 19 pages et formulaires en mobile/tablette et en bureau : titres, langue, débordements, libellés de contrôles, boutons, textes alternatifs et console sont propres. Après reconstruction, les écrans denses FR/EN ne présentent plus aucun chevauchement dans l'en-tête et les filtres de scan sont visibles dans le journal d'audit.
- [x] Les risques résiduels et les choix de configuration de production sont documentés.

## Validation intermédiaire du 8 septembre 2026

- [x] 537 tests hors Testcontainers : 537 réussis, 0 échec, 0 erreur, 0 ignoré.
- [x] Compilation, packaging du JAR et génération du SBOM CycloneDX réussis.
- [x] `docker compose config --quiet` réussi pour les variantes développement et production.
- [x] Syntaxe de `scripts/audit-api.ps1` validée par le parseur PowerShell 7.
- [x] Journalisation Nmap couverte par 11 tests ciblés réussis (service de scan et persistance des événements).
- [x] Build complet avec Testcontainers (993 tests), migrations Flyway et seuils JaCoCo réussis depuis un processus autorisé à accéder à Docker Desktop.
- [x] Image applicative reconstruite ; application et PostgreSQL déclarés sains par leurs healthchecks.
- [x] Audit API destructif rejoué sur la stack isolée : 80/80 opérations, 159/159 scénarios, scan Nmap HTTP 200, sauvegarde/import/restauration/purge réussis et nettoyage automatique des volumes jetables.
