# property-booking-api

> booking bounded context: service API

Part of the **Pms_Property** distributed system, Grupo 2. Governance and
documentation live in [`property-docs`](https://github.com/code-corhuila/property-docs).

Hexagonal service of the **booking** domain (the `Reserva` aggregate), in Java 21
and Spring Boot 3.5, as a three-module Maven build (ADR-011, Annex C). It is a
participant of the Saga `reservar` orchestrated by `property-workflow` (ADR-009).

```
booking-core/       domain + application — NO Spring dependency
  domain/model/            entities, value objects, invariants and domain exceptions
  application/port/in/     what the service offers
  application/port/out/    what it needs
  application/usecase/     one use case per business operation
booking-adapters/   Spring Web adapters (and persistence, later)
  adapter/in/http/         RS256 verifier, auth and correlation filters, error envelope, /health
booking-app/        Spring Boot application: the composition root
deploy/             Dockerfile and this service's compose file
```

**Why three modules.** `booking-core` does not declare Spring as a dependency, so
a `@Service` or `@Entity` in the domain **does not compile** (norm 5.3.3). The
dependency rule stops being a convention and becomes a build error.

The schema lives in `property-booking-db`; this service never migrates it. In the
platform it connects as `booking_app` (Annex J, ADR-008).

## Run

```bash
mvn -B verify                                   # build and every test
mvn -B -DskipTests package
java -jar booking-app/target/booking-app-0.1.0.jar
```

Without Maven installed:

```bash
docker run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-21 mvn -B verify
```

The image:

```bash
docker build -f deploy/Dockerfile -t booking-api .
```

Inside the network `platform` the service is `http://booking-api:3001`; from
outside it is reached only through the gateway, at `http://localhost:8080/api/v1/reservas`.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  hu-<story>-qa
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` and `00-governance/git-conventions.md` in
`property-docs`.
