# Security Policy

## Supported versions

Subnetory is early-stage software with a single active development line. Only the most recently released version and the `main` branch receive security fixes.

| Version | Supported |
|---|---|
| `main` (unreleased) | Yes |
| Latest tagged release (currently `v0.8.5`) | Yes |
| Older tagged releases | No |

There is no long-term support branch at this stage. Upgrading to the latest release is the recommended way to stay covered.

## Reporting a vulnerability

Please **do not open a public GitHub issue** for a suspected security vulnerability.

Use GitHub's private vulnerability reporting instead: go to the **Security** tab of this repository, then **Report a vulnerability**. This opens a private advisory visible only to the maintainers until a fix is ready, and lets you attach details, logs or a proof of concept without exposing the issue publicly.

If private reporting is not available for any reason, open a regular issue asking to be contacted privately, without any technical detail, and a maintainer will follow up through GitHub.

Please include as much of the following as you can:

- affected version or commit, and deployment mode (Docker Compose, Kubernetes/Helm, portable Windows app-image);
- steps to reproduce, or a minimal proof of concept;
- the impact you believe this has (data exposure, privilege escalation, authentication bypass, denial of service, etc.).

## Response expectations

Subnetory is currently maintained by a small team. There is no formal SLA, but the aim is:

- an acknowledgement within a few days of the report;
- a fix or mitigation, or at least a public status update, before any public disclosure.

Coordinated disclosure is appreciated: please give the maintainers a reasonable window to ship a fix before discussing the issue publicly.

## Security posture and relevant documentation

Subnetory ships with several security mechanisms already in place. These are documented in detail rather than repeated here:

- authentication, MFA (TOTP), rate limiting, audit logging, LDAP: [`backend/docs/ADMIN_GUIDE.md`](backend/docs/ADMIN_GUIDE.md) and [`backend/docs/USER_GUIDE_MVP.md`](backend/docs/USER_GUIDE_MVP.md);
- secrets handling (Docker Secrets, dedicated encryption key for LDAP/MFA secrets, backup encryption key): see the "Quick start" section of [`README.md`](README.md);
- backup/restore integrity and encryption: [`backend/docs/BACKUP_STRATEGY.md`](backend/docs/BACKUP_STRATEGY.md) and [`backend/docs/RESTORE_OPERATIONS.md`](backend/docs/RESTORE_OPERATIONS.md);
- Kubernetes/Helm deployment caveats (single replica, NetworkPolicy, external PostgreSQL, PVC backup limitations): [`backend/docs/KUBERNETES_HELM_READINESS.md`](backend/docs/KUBERNETES_HELM_READINESS.md);
- the most recent internal security/functional audit and the fixes applied: [`backend/docs/SUBNETORY_AUDIT_20260731.md`](backend/docs/SUBNETORY_AUDIT_20260731.md) and the `[Unreleased]` section of [`CHANGELOG.md`](CHANGELOG.md).

## Known limitations

Subnetory is a young project intended primarily for internal use by small IT teams, not for direct exposure on the public Internet. In particular:

- there is no independent third-party security audit yet;
- Kubernetes deployments are documented as single-replica, with several production concerns (Ingress, CNI, NetworkPolicies, external storage) left to the operator to qualify for their own environment;
- dependency scanning runs in CI (see the `dependency-scan` workflow job), but no formal CVE monitoring process exists yet beyond that.

If you plan to expose Subnetory beyond a trusted internal network, put it behind a reverse proxy with HTTPS termination and review the documents linked above first.
