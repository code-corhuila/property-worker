# Changelog of `property-worker`

Every release of this repository, newest first. One release per course cut, tagged on `main`
(`git-conventions.md`, "Tags and versioning"; SemVer). Each entry lists the user stories it
brought, with the pull requests of `develop` that built them.

## [3.0.0] - 2026-11-15

### Added

- **HU-BOOK-005 (code-corhuila/property-docs#204) — Expire stale pending Reservas**
  - feat(worker): expire the reservations left pending (#1)
  - feat(worker): run the expiry on a schedule against booking (#2)
