# Changelog

All notable changes to Subnetory will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows semantic versioning pragmatically during early development.

## [Unreleased]

## [0.7.0] - 2026-08-01

### Added

* Automated dependency vulnerability scanning in CI (30/07/2026): weekly Dependabot updates for Maven (`/backend`), GitHub Actions and Docker (`.github/dependabot.yml`), plus a `dependency-scan` job in `.github/workflows/ci.yml` running Trivy (pinned to a full commit SHA, never a mutable tag, following the `aquasecurity/trivy-action` supply-chain incident of March 2026) against both the Maven source tree and the built Docker image, failing on any unresolved CRITICAL/HIGH CVE.
* Optional HTTPS reverse-proxy overlay (30/07/2026): `backend/docker-compose.https.yml` adds a Caddy 2.11 service terminating TLS (self-signed `tls internal`, or a public domain via ACME/Let's Encrypt — see `backend/docs/HTTPS_REVERSE_PROXY.md`), removes the application's direct host port exposure, and forwards the real client IP via `trusted-proxy`/`trusted-proxy-cidrs` so rate limiting and the audit log resolve the actual caller instead of Caddy. Verified in CI by the `compose-https-smoke` job.
* Generalized rate limiting on `/api/v1/**` (30/07/2026, correctif F7): `ApiRateLimiter`/`ApiRateLimitingFilter` cap requests per client IP (default 300 requests / 60 s window, configurable via `subnetory.security.api-rate-limit.*`), returning `429 Too Many Requests` as `application/problem+json` with a `Retry-After` header; `/actuator/health` and `/actuator/health/**` stay exempt.
* Optional TOTP-based multi-factor authentication (30/07/2026): self-service enrollment from `/profile` (QR code, `dev.subnetory.service.MfaService`), 10 single-use bcrypt-hashed recovery codes, enforcement at Web login (`MfaChallengeFilter` redirecting to `/login/mfa`) and at API login (`POST /api/v1/auth/token` responds `401 MFA_REQUIRED`/`401 MFA_INVALID`), an admin anti-lockout override (`POST /api/v1/admin/users/{id}/disable-mfa`), and a documented break-glass recovery procedure for a single admin account that loses both its password and second factor (`backend/docs/ADMIN_GUIDE.md`). Available for `authType=LOCAL` accounts only; LDAP accounts are unaffected.
* In-app backup engine (Phase 7 audit, 31/07/2026): `pg_dump`/`pg_restore` run by the application itself, identical in Docker Compose and Kubernetes (`BackupExecutionService`).
* Admin UI `/admin/backup` and REST API `/api/v1/admin/backup` in full parity: configuration, run history, manual trigger, file download, restore history and restore.
* Restore safety: confirmation by exact file name (never a single click), SHA-256 checksum verification, automatic safety backup before every restore, retention that purges files but never history.
* Helm chart: dedicated `backupApp` PVC/ConfigMap for the in-app engine, alongside the existing standalone CronJob mechanism (`backup`); the chart now rejects enabling both against the same database.
* Clickable, click-to-copy SHA-256 checksums and file names on the backup screens.
* Backup import (01/08/2026): upload an external `.dump` file (e.g. downloaded from another instance) via `POST /admin/backup/import` / `/api/v1/admin/backup/import`; format is validated with `pg_restore --list` before acceptance, then the file is restorable like any other backup with the same protections.
* Manual, explicit history purge (01/08/2026): `POST /admin/backup/purge` / `/api/v1/admin/backup/purge` permanently deletes backup/restore history (and files) strictly before a given date, while protecting any backup still referenced by a retained restore.
* Optional label/comment per backup run (01/08/2026), shown in the history and restore confirmation screens.
* Automatic reconciliation of orphaned "RUNNING" backup/restore rows on application startup (01/08/2026): an operation interrupted by a crash or restart is now marked FAILED instead of staying stuck forever.
* Copy-to-clipboard for full error messages in the backup/restore history tables.
* Single-row backup delete (01/08/2026): `DELETE /api/v1/admin/backup/runs/{id}` / "Supprimer" button per row in `/admin/backup`, finer-grained than the mass purge-before-date. Refused with `409` if the backup is still referenced by a retained restore, or still `RUNNING`.
* Cascade backup delete (01/08/2026): `GET /api/v1/admin/backup/runs/{id}/linked-restores` lists exactly which restores reference a backup (as source or pre-restore safety copy); `DELETE .../runs/{id}?cascade=true` deletes them along with the backup instead of refusing. The web confirmation page (`/admin/backup/runs/{id}/delete-confirm`) lists the linked restores before offering this explicitly, keeping the strict refusal as the default.
* `ROLE_BACKUP` (01/08/2026): new assignable role limited to `/admin/backup` and `/api/v1/admin/backup` (configuration, trigger, import, restore, history), without the rest of administration. Follow-up of the DB password rotation feasibility study (`backend/docs/DB_PASSWORD_ROTATION_FEASIBILITY.md`).
* Backup encryption at rest (01/08/2026, backlog #13; enabled by default since 01/08/2026 for new Docker Compose installs — see the breaking-change entry below): AES-256-GCM + a separate HMAC-SHA256 over the whole file (independent keys derived via PBKDF2WithHmacSHA256, per-file random salt), streamed on both write and read paths. Key comes exclusively from `SUBNETORY_BACKUP_ENCRYPTION_KEY` (Docker secret / Kubernetes Secret via the existing configtree mechanism, or a raw env var for dev) — never stored in the database, never settable from the UI, same reasoning as `DB_PASSWORD_ROTATION_FEASIBILITY.md`. Once configured, new backups (scheduled/manual/pre-restore safety) are encrypted transparently; restore decrypts to a temp file right before `pg_restore` and always deletes it afterward; import detects and validates an already-encrypted file, or encrypts a plaintext import on the fly if the key is configured. Existing plaintext backups keep working unmodified — encryption only ever applies going forward, never retroactively. New `backup_runs.encrypted` column, `encrypted`/`encryptionEnabled` fields in the API, badge in the history table. See `backend/docs/BACKUP_ENCRYPTION.md` for the on-disk format and the important caveat that changing/losing the key is not a rotation — it permanently locks out backups already encrypted with the old value.
* Visual cron builder on `/admin/backup` (01/08/2026): tabs for Daily/Weekly/Monthly/Interval/Custom above the existing cron text field, which stays the single source of truth submitted to the server (server-side validation unchanged, `BackupConfigurationService`/Spring `CronExpression`). Weekly lets you pick one or more days as highlighted chips; Interval covers "every N hours" and "every N days" (e.g. every other day) at a given time; the field is read-only outside Custom mode to avoid drift, and any existing expression the structured modes don't recognize exactly falls back to Custom with the raw text preserved. Pure front-end enhancement (`app.js`/`app.css`), no backend or data-model change.
* Audit log extended to the whole backup lifecycle and to network-resource CRUD (01/08/2026, backlog #27): every backup action now has its own event type — `BACKUP_TRIGGERED`, `BACKUP_IMPORTED`, `BACKUP_RESTORED`, `BACKUP_DELETED`, `BACKUP_PURGED`, `BACKUP_SETTINGS_UPDATED` — recorded by `BackupExecutionService`/`BackupConfigurationService` with the acting username (from `SecurityContextHolder`), so it's now possible to answer "who ran/deleted/restored/purged which backup, and when" from `/admin/audit-log` alone, instead of only the file timestamps. Creating or deleting an IP address, VLAN, context, site or subnet is now audited too (`ADDRESS_CREATED`/`_DELETED`, `VLAN_CREATED`/`_DELETED`, `CONTEXT_CREATED`/`_DELETED`, `SITE_CREATED`/`_DELETED`, `SUBNET_CREATED`/`_DELETED`), on both the REST API and the web controllers, with a human-readable target (IP address, `VLAN <vid> (<name>)`, name, or CIDR). No schema change: the 16 new event types reuse the existing `auth_audit_log` table and its free-text `message` column. The audit-log filter dropdown and CSV export now group all 30 event types by category (Authentification/comptes, Sauvegardes, Ressources réseau).
* Manual audit-log purge (01/08/2026, backlog #27): `/admin/audit-log` gains a "Rétention" card showing the automatic nightly retention window (`subnetory.audit.retention.days`, 90 by default) and a form to purge entries strictly before a chosen date immediately, without waiting for the next scheduled run — `POST /admin/audit-log/purge` (web) / `POST /api/v1/admin/audit-log/purge` (API), both delegating to the existing `AuthAuditRetentionService` so the deletion logic itself is unchanged, only newly exposed and manually triggerable.

### Changed (UI)

* All checkboxes across the app (01/08/2026) replaced with a highlighted "chip" pill (`.sn-chip-checkbox`) instead of the raw native checkbox: user roles, contexts, LDAP default roles, backup/LDAP toggles, "temporary IP". Role/context lists (`.sn-roles-list`) now flow as wrapped chips instead of a vertical stack. Table-row checkboxes without a label (bulk IP reservation) get a compact rounded variant (`.sn-table-checkbox`) instead. Import/scan option cards (`.sn-import-option`) gain a highlighted border/background when checked. The underlying `<input type="checkbox">` stays in the DOM (visually hidden, never `display:none`): native keyboard focus and screen readers keep working, only the visual layer changed. Legacy classes `sn-form-checkbox`/`sn-form-label--checkbox`/`sn-form-group--checkbox` removed (no longer referenced anywhere).

### Fixed

* Restore crash (`StaleObjectStateException`, 31/07/2026): `pg_dump` no longer includes the application's own backup metadata tables/sequences, which previously caused a 500 on restore and permanently stuck "RUNNING" rows in restored dumps.
* `pg_restore` now runs with `--exit-on-error` (01/08/2026): previously, a failed restore (e.g. blocked `DROP SEQUENCE`) could still silently execute later statements in the same archive, desynchronizing a sequence and causing a delayed `ConstraintViolationException` on the next backup.
* Double confirmation dialog on delete actions (01/08/2026): `app.js` had two independent `data-confirm` handlers — one on `click`, a duplicate one on `submit` — both firing for the same form, so every delete (addresses, contexts, sites, subnets, VLANs) required confirming twice. Removed the redundant handler.
* `.sn-chip-checkbox` not highlighting on click (01/08/2026): the CSS relied on `input:checked + .sn-chip-checkbox__pill` (immediate-sibling combinator), but on any checkbox bound with `th:field`, Thymeleaf injects a hidden `<input name="_xxx">` right after it for Spring's unchecked-checkbox binding, breaking that adjacency. Affected "Compte actif" (users), "Activer" (backup, LDAP), "Supprimer le mot de passe enregistré" (LDAP) and "IP temporaire" (address form). Switched to the `~` (general sibling) combinator; also added the missing `position: relative` on `.sn-chip-checkbox` so the visually-hidden input stays anchored to its label. Role/context chip lists were unaffected (bound via `name`/`th:value`/`th:checked`, not `th:field`). Table-row checkboxes (`.sn-table-checkbox`, bulk IP reservation) were also unaffected — styled directly on `:checked`, no sibling dependency.

### Changed

* **BREAKING for existing Docker Compose instances** — Backup encryption at rest enabled by default (01/08/2026, backlog #13): `backend/docker-compose.yml` and `backend/docker-compose.prod.yml` now declare the `subnetory_backup_encryption_key` secret unconditionally (previously commented out / opt-in), and `scripts/init-compose.ps1` / `scripts/init-compose.sh` now generate it by default alongside the three existing secrets. On an **existing** deployment where `backend/secrets/subnetory_backup_encryption_key` does not yet exist, `docker compose up` will fail with a missing-secret error until that file is created. Migration for an existing instance: re-run `pwsh -File .\scripts\init-compose.ps1` (or `./scripts/init-compose.sh`) **without** `-Force`/`--force` — thanks to the script's existing "only create missing secrets" behavior, only `subnetory_backup_encryption_key` is created; the JWT secret, admin password and PostgreSQL password already on disk are left strictly untouched — then `docker compose up -d --build` to reload the configuration. To opt out entirely, pass `-WithoutBackupEncryption` / `--without-backup-encryption` when (re)running the script and keep the corresponding secret blocks commented out in the Compose file(s) in use. See `backend/docs/BACKUP_ENCRYPTION.md` and `backend/docs/INSTALL_DOCKER_COMPOSE.md` for details. The Kubernetes Helm chart is unaffected: backup encryption remains unwired there (pre-existing gap, out of scope for this change).
* Clickable row navigation (drill-down) across context → site → VLAN → subnet → addresses.
* Optimistic locking (`@Version`) on `Address` and `Subnet`.

## [0.6.0] - 2026-07-30

### Changed

* Upgraded to Spring Boot 4.1.0 (Spring Framework 7, Spring Security 7, Hibernate ORM 7 / Jakarta Persistence 3.2, Jackson 3 as the default JSON library, Testcontainers 2.0, springdoc-openapi 3.0.3), staged through an intermediate Spring Boot 3.5.16 upgrade.
* `AddressSpecifications` native-column text casting (`inet`, `macaddr`) now uses the JPA 3.2 standard `Expression#cast(String.class)` instead of the Hibernate 6.6 empty-string-concatenation workaround.
* Test suite migrated to Jackson 3 (`tools.jackson`), `@MockitoBean`/`@MockitoSpyBean`, and the relocated `spring-boot-starter-webmvc-test` module for `@AutoConfigureMockMvc`/`@WebMvcTest`.
* Testcontainers usages migrated to the canonical `org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.0); the class is no longer generic.
* Dependency hygiene: `commons-net` 3.10.0 → 3.13.0, `opencsv` 5.9 → 5.12.0.
* Added `.gitattributes` to formalize LF line endings across the repository, independent of each contributor's or CI runner's `core.autocrlf` setting.
* Removed a dead, unused `ObjectMapper` field from `AddressService`.

### Fixed

* `SECURITY_FIXES.md` corrected: F5 (concurrent session limiting via `maximumSessions(5)`) and F6 (SQL `LIKE` wildcard escaping) were already implemented in code but incorrectly listed as deferred since Sprint 2.29.

## [0.5.0] - 2026-07-30

### Added

* Mandatory first-use password replacement for local bootstrap and administratively reset accounts.
* Flyway V9 column `users.must_change_password`.
* Dedicated mandatory password-change Web flow and per-request enforcement filter.
* HTTP 403 ProblemDetail response with code `PASSWORD_CHANGE_REQUIRED` when JWT issuance is attempted too early.
* Windows x64 `app-image` packaging with a bundled Java 21 runtime.
* Reproducible portable ZIP, SHA256, manifest and Windows smoke-test scripts.

* JWT `jti` claim on API tokens.
* Revoked token denylist backed by the `revoked_tokens` table.
* API logout endpoint `POST /api/v1/auth/logout`.
* Idempotent JWT logout decoder for already revoked tokens.
* Audit event `TOKEN_REVOKED`.
* Scheduled purge service for expired revoked tokens.
* User-wide JWT invalidation backed by the `user_token_invalidations` table (Flyway V8).
* API endpoint `POST /api/v1/auth/logout-all` to invalidate every token of the current subject.
* Admin GUI action to revoke all API tokens of a user account.
* Automatic API token invalidation on password change and admin password reset.
* Audit event `TOKENS_INVALIDATED`.
* PostgreSQL-native available-IP allocation for large IPv4 networks.
* API endpoint `POST /api/v1/auth/change-password-required` to replace a mandatory temporary password by credentials, without JWT, enabling fully API-driven bootstrap of automation accounts.
* OpenAPI descriptions on every `/api/v1` endpoint: required roles, context-scope behavior and error semantics.
* Kubernetes/Helm deployment: production-ready Helm chart (see `backend/docs/INSTALL_KUBERNETES.md`) with either a bundled single-replica PostgreSQL or an external database, non-root application and PostgreSQL containers, separate runtime/bootstrap Kubernetes Secrets, dedicated startup/liveness/readiness probes, optional Ingress and NetworkPolicy (disabled by default), `helm test`/upgrade/rollback support, and dedicated `helm-static`/`helm-smoke` CI jobs against a `kind` cluster. See also `backend/docs/KUBERNETES_OPERATIONS.md` and `KUBERNETES_COMPATIBILITY.md`.
* Scheduled Kubernetes PostgreSQL backups: opt-in CronJobs (`backup.enabled`, disabled by default, included-database only) with independent `hourly`/`daily`/`monthly`/`quarterly` retention levels, SHA-256-verified compressed `pg_dump` archives, a non-destructive restore drill exercised against an ephemeral, network-isolated PostgreSQL instance, and a dedicated `backup-safety` CI job.

### Changed

* GitHub Actions upgraded to `actions/checkout@v7`, `actions/setup-java@v5` and `actions/upload-artifact@v7`.
* Local admin bootstrap passwords are now explicitly temporary and never grant normal application access.
* Administrative local-password resets now reactivate mandatory replacement while preserving token invalidation and audit behavior.
* Test baseline increased to 552 tests during Sprint 2.29.

* API JWT decoder now rejects tokens whose `jti` is present in the revocation denylist.
* Security configuration now uses a dedicated API logout filter chain before the standard API resource-server chain.
* API JWT decoder now rejects tokens issued before the per-user `not_before` threshold.
* API JWT decoder now validates the `iss` claim (`subnetory`); tokens without the expected issuer are rejected.
* Test baseline increased to 522 tests after Sprint 2.27.
* Available-IP lookup now detects occupied-address gaps in PostgreSQL and only generates the requested candidates.
* Test baseline increased to 530 tests during Sprint 2.28.
* Root README and `backend/README.md` quick-start now document the real secrets bootstrap flow (`scripts/init-compose.sh` / `.ps1`) instead of a stale `.env` `change-me` example.
* Removed four unrelated Adrezo v2 analysis documents from `backend/docs/`.
* Test baseline increased to 615 tests during Sprint 2.33.

### Fixed

* API clients can now explicitly invalidate their current JWT before natural expiration.
* Assigned `/32` addresses are no longer reported as available; subnet membership now uses inclusive PostgreSQL containment (`<<=`).
* `ImportFileValidator` now reads its size limit from `spring.servlet.multipart.max-file-size` instead of an independent hardcoded 5 MB constant, so raising the Spring property actually raises the import limit.
* `CsvSafeValue` also neutralizes values starting with a tab or carriage return, per OWASP CSV injection guidance.
* Password policy violations on API endpoints now return `400 Bad Request` with the policy message instead of a generic `500`.

## [0.4.0] - 2026-07-03

### Added

* Release hygiene and verified reprise archive workflow.
* Versioned PowerShell 7 script `scripts/make-reprise-archive.ps1`.
* Blocking archive checks for Flyway migrations, `.env`, dumps, backups and Maven wrapper files.
* Retroactive changelog.
* Documentation index updated through Sprint 2.25.
* PostgreSQL backup and restore strategy across Sprints 2.15 to 2.22.
* Backup monitoring, scheduling, retention profile, root consistency and integrity checks.
* Runtime validation documentation and gateway reservation behavior.
* Edge CIDR allocation handling for `/31` and `/32`.

### Changed

* Maven project version aligned to `0.4.0` for the release commit.
* Reprise archive rules clarified: Flyway migrations are source code and must always be included.
* Root README updated with current usage, Docker Compose launch, tests and reprise archive workflow.

### Fixed

* Archive generation process no longer excludes Flyway `*.sql` migrations.
* Release milestone v0.4.0 is no longer left floating after the v0.3.x patch sequence.

## [0.3.0] - 2026-06-02

### Added

* Security baseline for local authentication.
* Password policy service.
* Self-service profile page.
* Admin password reset flow.
* Rate limiting and audit log foundations.
* Audit log pagination and retention scheduler.
* CSV injection protection.

### Changed

* Project moved from MVP readiness toward hardened private release workflow.

## [0.2.0] - 2026-06-02

### Added

* Release readiness milestone.
* Optional LDAP authentication (disabled by default): local accounts and the API JWT chain keep working unmodified when no LDAP server is configured; the first successful LDAP bind auto-provisions the account with a configurable default role, and a username collision between a local and an LDAP account is rejected.
* User/role administration GUI at `/admin/users`, restricted to `ROLE_ADMIN`: paginated user list, per-user role assignment, account enable/disable, with anti-lockout safeguards (cannot disable your own account, cannot remove `ROLE_ADMIN` from or disable the last active admin, cannot submit zero roles).
* Network dashboard on `/`: global counters (contexts, sites, VLANs, subnets, addresses) plus a top-10 most-utilized subnets view with capacity/available/utilization bars (`DashboardService`).
* Default landing page set to dashboard.
* GUI improvements around IP availability assistant, imports and exports.
* XLSX import support.
* CSV and Excel export capabilities.
* OpenAPI / Swagger UI and JaCoCo reporting.

## [0.1.0] - 2026-05-15

### Added

* Initial Spring Boot backend foundation.
* PostgreSQL schema managed by Flyway.
* REST CRUD for core IPAM entities.
* Contexts, sites, VLANs, subnets and addresses.
* Basic secured GUI with Thymeleaf.
* Docker packaging and GitHub Actions CI/CD foundations.

[Unreleased]: https://github.com/Subnetory/subnetory/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/Subnetory/subnetory/releases/tag/v0.6.0
[0.5.0]: https://github.com/Subnetory/subnetory/releases/tag/v0.5.0
[0.4.0]: https://github.com/Subnetory/subnetory/releases/tag/v0.4.0
[0.3.0]: https://github.com/Subnetory/subnetory/releases/tag/v0.3.0
[0.2.0]: https://github.com/Subnetory/subnetory/releases/tag/v0.2.0
[0.1.0]: https://github.com/Subnetory/subnetory/releases/tag/v0.1.0
