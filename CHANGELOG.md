# Changelog of `property-booking-api`

Every release of this repository, newest first. One release per course cut, tagged on `main`
(`git-conventions.md`, "Tags and versioning"; SemVer). Each entry lists the user stories it
brought, with the pull requests of `develop` that built them.

## [3.0.0] - 2026-11-15

### Added

- **HU-BOOK-001 (code-corhuila/property-docs#161) — Create a Reserva and lock its dates**
  - chore(booking): scaffold the three-module spring boot service (#1)
  - feat(booking): add the reserva domain model and its invariants (#2)
  - feat(booking): add the common http adapters (#3)
  - docs(booking): publish the versioned openapi contract (#4)
  - feat(booking): add the reservation store port and its in-memory adapter (#5)
  - chore(booking): run the integration tests against the booking schema (#6)
  - feat(booking): store reservations and their outbox events in the booking schema (#7)
  - feat(booking): read the price and capacity of a property from catalog (#8)
  - feat(booking): create a reservation for the saga (#9)
  - feat(booking): relay the outbox to the broker in order (#13)
  - feat(booking): run the outbox relay as a process apart (#14)
- **HU-BOOK-003 (code-corhuila/property-docs#189) — Confirm a Reserva**
  - feat(booking): confirm a reservation (#10)
- **HU-BOOK-004 (code-corhuila/property-docs#190) — Cancel a Reserva and release its dates**
  - feat(booking): cancel and expire a reservation (#11)
- **HU-BOOK-006 (code-corhuila/property-docs#148) — Query booking status**
  - feat(booking): read a reservation and list them (#12)
