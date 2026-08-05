# Security Policy

## Supported versions

Subnetory is early-stage software with a single active development line. Only the most recently released version and the `main` branch receive security fixes.

| Version | Supported |
|---|---|
| `main` (unreleased) | Yes |
| Latest tagged release (currently `v0.8.9`) | Yes |
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

## Release integrity

Since v0.8.7 (troisieme audit externe, constat M-03, 04/08/2026), every tagged release publishes, alongside the JAR and the GHCR image:

- a CycloneDX SBOM of the JAR (`subnetory-bom.json`), and a native SBOM + SLSA provenance attestation attached to the GHCR image itself (BuildKit `sbom`/`provenance`, queryable with `docker buildx imagetools inspect`);
- a Sigstore "keyless" signature of the published image, tied to the exact digest that was pushed (never a mutable tag) and to this repository's `release.yml` workflow identity via GitHub OIDC — no private key is generated, stored or rotated;
- the same keyless signing applied to `SHA256SUMS.txt` (which itself covers the JAR and the SBOM), producing `SHA256SUMS.txt.sig`/`SHA256SUMS.txt.pem` alongside it.

To verify a release with [Cosign](https://docs.sigstore.dev/cosign/system_config/installation/):

```bash
# Image (replace <digest> with the sha256 the tag currently resolves to)
cosign verify ghcr.io/subnetory/subnetory@<digest> \
  --certificate-identity-regexp 'https://github.com/Subnetory/subnetory/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# Release assets (SHA256SUMS.txt, then sha256sum -c against the JAR/SBOM)
cosign verify-blob SHA256SUMS.txt \
  --signature SHA256SUMS.txt.sig --certificate SHA256SUMS.txt.pem \
  --certificate-identity-regexp 'https://github.com/Subnetory/subnetory/.github/workflows/release.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
sha256sum -c SHA256SUMS.txt
```

Base Docker images (`eclipse-temurin:21-jdk-alpine`/`21-jre-alpine`) are pinned by digest in `backend/Dockerfile`, tracked by Dependabot like any other dependency so a base image update surfaces as a reviewable pull request rather than silently changing what a rebuild produces.

## Known limitations

Subnetory is a young project intended primarily for internal use by small IT teams, not for direct exposure on the public Internet. In particular:

- several rounds of external code review have been performed and remediated (see [`CHANGELOG.md`](CHANGELOG.md) and [`SECURITY_FIXES.md`](SECURITY_FIXES.md) for the fixes applied on 01/08, 03/08 and 04/08/2026), but none of them is a formal, credentialed penetration test or a certified third-party security audit — treat the findings as a thorough code review, not as an independent security certification;
- Kubernetes deployments are documented as single-replica, with several production concerns (Ingress, CNI, NetworkPolicies, external storage) left to the operator to qualify for their own environment;
- dependency scanning runs in CI (see the `dependency-scan` workflow job), but no formal CVE monitoring process exists yet beyond that;
- static application security testing (SAST) of the Java source itself runs in CI since 04/08/2026 (see the `codeql` workflow job, CodeQL with the `security-extended` query pack) — findings surface under this repository's Security tab, and a failed upload here (public repository, GitHub Advanced Security available for free) does fail the CI run since 04/08/2026 (troisième audit externe, constat I-01). The internal development fork keeps this step non-blocking, since it runs without a paid GitHub Code Security license there.

If you plan to expose Subnetory beyond a trusted internal network, put it behind a reverse proxy with HTTPS termination and review the documents linked above first.
