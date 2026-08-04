# API-first parity

Subnetory is API-first: every business operation available from the web interface must be automatable through `/api/v1/**`.

## Coverage

| Area | Web UI | API |
|---|---|---|
| Dashboard statistics | `/` | `GET /api/v1/dashboard` |
| Contexts | `/network/contexts` | `/api/v1/contexts` |
| Sites | `/network/sites` | `/api/v1/sites` |
| VLAN | `/network/vlans` | `/api/v1/vlans` |
| Subnets | `/network/subnets` | `/api/v1/subnets` |
| Available IPs | `/network/subnets/{id}/available-ips` | `GET /api/v1/subnets/{id}/available-ips` |
| Subnet scan | `/network/subnets/{id}/scan` | `POST /api/v1/subnets/{id}/scan` |
| Addresses | `/network/addresses` | `/api/v1/addresses` |
| Address import/export | `/network/addresses/import`, exports | `/api/v1/addresses/import`, `/import/csv`, `/import/xlsx`, exports |
| Authentication | `/login` | `POST /api/v1/auth/token`, `/logout`, `/logout-all` |
| Mandatory first password change | `/profile/change-password-required` | `POST /api/v1/auth/change-password-required` |
| Profile | `/profile` | `GET /api/v1/profile` |
| Password change | `/profile/change-password` | `POST /api/v1/profile/change-password` |
| User administration | `/admin/users` | `/api/v1/admin/users` |
| User roles and contexts | `/admin/users/{id}` | `GET /api/v1/admin/users/assignable-roles`, `/api/v1/admin/users/{id}/roles`, `/contexts` |
| User activation and password reset | `/admin/users/{id}` | `/api/v1/admin/users/{id}/enable`, `/disable`, `/reset-password` |
| User deletion (03/08/2026) | `/admin/users/{id}` ("Zone dangereuse") | `DELETE /api/v1/admin/users/{id}` |
| API token invalidation | `/admin/users/{id}` | `/api/v1/admin/users/{id}/invalidate-tokens` |
| LDAP configuration and diagnostics | `/admin/ldap` | `/api/v1/admin/ldap` |
| Authentication audit log | `/admin/audit-log` | `/api/v1/admin/audit-log` |
| Audit log manual purge (01/08/2026) | `/admin/audit-log` ("Purger définitivement") | `POST /api/v1/admin/audit-log/purge` |
| Backup configuration and status | `/admin/backup` | `GET/PUT /api/v1/admin/backup` |
| Backup run history and manual trigger | `/admin/backup` (history table, "Sauvegarder maintenant") | `GET /api/v1/admin/backup/runs`, `POST /api/v1/admin/backup/trigger` |
| Backup file download | `/admin/backup` ("Télécharger") | `GET /api/v1/admin/backup/runs/{id}/download` |
| Backup restore | `/admin/backup/runs/{id}/restore-confirm` | `GET /api/v1/admin/backup/restores`, `POST /api/v1/admin/backup/restore` |
| Backup import (external .dump file) | `/admin/backup` ("Importer") | `POST /api/v1/admin/backup/import` (multipart) |
| Backup manual history purge | `/admin/backup` ("Purger définitivement") | `POST /api/v1/admin/backup/purge` |
| Backup single-row delete | `/admin/backup` (history row, "Supprimer") → `/admin/backup/runs/{id}/delete-confirm` | `DELETE /api/v1/admin/backup/runs/{id}` |
| Backup delete with linked restores | `/admin/backup/runs/{id}/delete-confirm` ("Supprimer avec les restaurations liées") | `GET /api/v1/admin/backup/runs/{id}/linked-restores`, `DELETE /api/v1/admin/backup/runs/{id}?cascade=true` |
| Backup encryption at rest (enabled by default, see `BACKUP_ENCRYPTION.md`) | `/admin/backup` (badge "Chiffrement" + "🔒 Chiffré" per row) | `encryptionEnabled` on `GET /api/v1/admin/backup`, `encrypted` on `BackupRunResponse` |

## LDAP default roles

LDAP auto-provisioning can assign one or several roles at the first successful login.
Use `defaultRoles` on `PUT /api/v1/admin/ldap`.

Example for a network operator who can manage topology and IP addresses without application administration:

```json
{
  "defaultRoles": ["ROLE_NETWORK", "ROLE_IP"]
}
```

`defaultRole` remains accepted for compatibility with older API clients.

Example for an automation account limited to read-only access on its allowed contexts:

```json
{
  "defaultRoles": ["ROLE_READ_ONLY"]
}
```

## LDAP user filter

The web UI exposes common login attributes such as `sAMAccountName`, `userPrincipalName`, `mail`, `uid` and `cn`.
For API automation, the stored value remains the complete LDAP filter, for example:

- `(sAMAccountName={0})` for an Active Directory short login;
- `(userPrincipalName={0})` for an Active Directory UPN login;
- `(mail={0})` for an email login;
- `(uid={0})` for a common OpenLDAP login.

`{0}` is the login entered by the user. API clients must keep `{0}` in the filter.

## Intentional UI-only routes

These routes are browser concerns, not standalone business operations:

- form pages such as `/network/subnets/new` or `/admin/users/new`;
- edit pages such as `/network/sites/{id}/edit`;
- session active-context selection under `/context-selection`.

Automation should pass explicit identifiers in API calls instead of relying on the browser session context.

## Address import context

The API import endpoint `POST /api/v1/addresses/import` detects `.csv` and `.xlsx` files automatically and requires `contextId`.
This keeps imports deterministic when several customers or environments use identical IP ranges.

The older typed endpoints remain available for compatibility:

- `POST /api/v1/addresses/import/csv`
- `POST /api/v1/addresses/import/xlsx`

They also accept `contextId` and should use it for multi-context deployments.

## Automation accounts and the mandatory first password change

Every local account is created with a temporary password that must be replaced before normal use. JWT issuance (`POST /api/v1/auth/token`) is refused with `403 PASSWORD_CHANGE_REQUIRED` until the replacement is done.

The replacement itself is available on both surfaces:

- Web: first login redirects to `/profile/change-password-required`;
- API: `POST /api/v1/auth/change-password-required` with `username`, `currentPassword` and `newPassword`. No JWT is required or issued; on `204`, request a token normally through `POST /api/v1/auth/token`.

Typical bootstrap of an automation account (for example `ROLE_READ_ONLY` limited to one context):

1. an administrator creates the account with a temporary password (`POST /api/v1/admin/users`);
2. the automation calls `POST /api/v1/auth/change-password-required` once with its own new strong password;
3. the automation calls `POST /api/v1/auth/token` and uses the API within its role and context scope.

The endpoint applies the same per-IP rate limiting as `/token`, the same password policy as the web form, returns a generic `401` on invalid credentials, `409` when no mandatory change is pending, and records the change in the authentication audit log. LDAP accounts are not concerned: their passwords are managed by the directory.

## Assignable roles

Only roles enforced by Subnetory authorization rules are assignable through the UI and API:

- `ROLE_ADMIN` — full administration;
- `ROLE_READ_ONLY` — read-only access to allowed contexts;
- `ROLE_NETWORK` — create and update contexts, sites, VLAN, subnets and scans;
- `ROLE_IP` — create, update, delete, import and export IP addresses;
- `ROLE_BACKUP` — backup configuration, trigger, history, download, purge and delete (`/admin/backup`, `/api/v1/admin/backup`) only, none of the rest of administration. Import and restore require `ROLE_ADMIN` (security fix, audit 04/08/2026): both operations let the caller overwrite or inject arbitrary content into the application database, which made `ROLE_BACKUP` a de facto data-admin role.

Other role rows may exist in older data sets, but they are not assignable unless a Subnetory feature explicitly uses them.

## Completion rule

New business capability is not complete until:

1. the service-layer rule exists once;
2. the API exposes the capability;
3. the web UI reuses the same service behavior;
4. tests cover the API path and the security boundary.
