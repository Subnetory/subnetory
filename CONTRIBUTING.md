# Contributing to Subnetory

Thanks for your interest in Subnetory. This is a young, early-stage project, currently maintained by a very small team with limited review bandwidth. This document sets realistic expectations rather than a full open-source governance process.

## How this repository relates to development

This repository (`Subnetory/subnetory`) is where releases are published: tagged versions, the GitHub Release, and the GHCR image all come from here. Day-to-day development happens in a separate internal repository, periodically synced here as a clean, squashed history once changes are tested and ready.

External contributions are still welcome directly on this repository. Open your pull request here as usual; once reviewed and accepted, it's integrated internally and included in the next sync, at which point it becomes part of this repository's history like any other change. You don't need to interact with the internal repository at all.

## Before you start

For anything beyond a small, obvious fix (typo, broken link, off-by-one in a doc), please open an issue first to discuss the change before writing code. This avoids wasted effort on a pull request that doesn't fit the project's direction — the guiding constraints are incremental steps, no unnecessary complexity, and every change must keep the app fully working.

Subnetory intentionally avoids:

- premature microservices or a distributed architecture;
- a mandatory Kubernetes deployment path (it stays optional, alongside Docker Compose and the portable Windows app-image);
- a full rewrite of any subsystem in one pull request;
- scope creep into full DCIM territory. Subnetory's focus is IPAM: contexts/VRF, sites, VLANs, subnets, addresses, backups. Large functional additions should be discussed in an issue first.

## Development setup

Requirements: Java 21, Maven (via the wrapper), Docker and Docker Compose v2 for local end-to-end testing.

```bash
cd backend
./mvnw test        # unit + integration tests
./mvnw verify       # tests + JaCoCo coverage report + coverage gate
```

PowerShell equivalents (`.\mvnw.cmd test`) work the same way on Windows.

To run the app locally against a real PostgreSQL instance via Docker Compose, see the "Quick start with Docker Compose" section of [`README.md`](README.md).

## Before opening a pull request

- **Tests must pass locally**: `./mvnw test` (or `verify`) with zero failures and zero errors.
- **Coverage gate**: JaCoCo enforces a minimum of 75% line coverage and 55% branch coverage on `mvn verify`. New code should carry its own tests rather than riding on the existing margin.
- **Flyway migrations**: any schema change is a new, additive `V<n>__description.sql` file under `backend/src/main/resources/db/migration/`. Never edit an already-released migration.
- **Database types**: prefer native PostgreSQL types (`inet`, `cidr`, `macaddr`) over generic strings where applicable, consistent with the existing schema.
- **i18n**: user-facing strings go through the existing message bundles (`messages.properties`, `messages_fr.properties`, `messages_en.properties`), not hardcoded text in templates or JS.
- **Docs**: if behavior described in `README.md`, `backend/docs/USER_GUIDE_MVP.md` or `backend/docs/ADMIN_GUIDE.md` changes, update those files in the same pull request. A previous internal audit found several cases where docs had drifted from actual behavior — the goal is to not reintroduce that.
- **Changelog**: add an entry under `## [Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md) describing the change from a user's perspective.

## Continuous integration

Every push and pull request runs the GitHub Actions `CI` workflow. All jobs must pass before a merge:

| Job | What it checks |
|---|---|
| `test` | Unit/integration test suite and JaCoCo coverage gate |
| `compose-smoke` | Docker Compose stack boots and serves traffic |
| `compose-https-smoke` | HTTPS reverse-proxy overlay (Caddy) |
| `backup-safety` | Backup integrity, retention, and hostile-restore-input rejection |
| `dependency-scan` | Maven dependency and Docker image vulnerability scan (Trivy) |
| `helm-static` | Helm chart validation, schema, rendered secret handling |
| `helm-smoke` | Real deployment of both Helm profiles on a `kind` cluster |

Local commands mentioned above cover most of `test`; the other jobs need Docker (and, for `helm-smoke`, `kind`/`kubectl`) and are usually left to CI unless you're specifically working on deployment tooling.

## Commit and PR conventions

- Keep commits and pull requests small and focused — one logical change per PR, consistent with the project's incremental-delivery approach.
- Write commit subjects in the imperative, prefixed by type when relevant (`fix:`, `docs:`, `feat:`, `chore:`), matching the existing history (see `git log`).
- Never commit `.env` files, anything under `backend/secrets/`, database dumps (`*.sql.gz`, `*.dump`), or backup contents. `.gitignore` covers `.env` and `secrets/` by pattern and `/backups/` as a folder; dumps placed elsewhere are not automatically caught, so don't rely on `.gitignore` alone.

## Security issues

Do not report security vulnerabilities through a public issue or pull request. See [`SECURITY.md`](SECURITY.md) for the private reporting process.

## License

Subnetory is licensed under the Apache License 2.0 (see [`LICENSE`](LICENSE)). By contributing, you agree that your contributions are licensed under the same terms. Subnetory is a derivative of [Adrezo](https://github.com/YannPostec/adrezo); see [`NOTICE`](NOTICE) for attribution requirements that must be preserved.
