# Event Ticketing API

[![CI](https://github.com/GligorovMarin/event-ticketing-api/actions/workflows/ci.yml/badge.svg)](https://github.com/GligorovMarin/event-ticketing-api/actions/workflows/ci.yml)

A REST API for selling event tickets with **seat selection**: organizers create events and seat maps,
customers pick seats, the seats are **held for 10 minutes** while they pay, and nobody can ever buy a seat
that someone else holds, even when thousands click at once. Built with **Java 21, Spring Boot 4 and
PostgreSQL**, fully containerised with Docker.

> Work in progress: built milestone by milestone. See [Roadmap](#roadmap).

## Tech stack

| Concern | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1 (Spring Framework 7, Hibernate 7) |
| Database | PostgreSQL 16, Spring Data JPA, schema versioned with Flyway migrations |
| Security | Spring Security, stateless JWT, role-based access |
| API docs | Swagger UI (springdoc) at `/docs` |
| Tests | JUnit 6 + AssertJ + MockMvc: unit tests (`*Test`) and integration tests against real PostgreSQL (`*IT`) |
| Runtime | Docker Compose; multi-stage Dockerfile (dev / build / prod, non-root) |
| CI | GitHub Actions |

## Quick start

Nothing needs to be installed except **Docker**.

```bash
docker compose up -d --build        # API on http://localhost:8080, Swagger UI on /docs
docker compose down -v              # stop and wipe the database
```

## Running the tests

```bash
docker compose run --rm api mvn -B test      # unit tests only (fast, no database)
docker compose run --rm api mvn -B verify    # unit + integration tests (real PostgreSQL, separate ticketing_test database)
```

Integration tests never touch development data: they use their own database, created by `docker/postgres-init/`.

## Project layout

```
docker-compose.yml ──starts──> db (PostgreSQL)  <── docker/postgres-init/*.sql creates ticketing_test
        │
        └──builds (Dockerfile)──> api (Spring Boot)
                                    │
  TicketingApplication ──scans──> config/SecurityConfig   (who may call what; locked by default)
                                  config/OpenApiConfig    (Swagger UI text and Authorize button)
                                  resources/application.yml        (settings)
                                  resources/db/migration/V*.sql    (Flyway: the database schema)

  Tests:  src/test/java/**/*Test.java  unit tests (no Spring, no database)
          src/test/java/**/*IT.java    integration tests (@IntegrationTest: real app + real PostgreSQL)
```

## Roadmap

- [x] **M1** Project skeleton, Docker, Flyway, test setup, CI
- [ ] M2 Users, JWT login, roles (Customer / Organizer)
- [ ] M3 Events and seat maps
- [ ] M4 Seat availability and the "find N seats together" algorithm
- [ ] M5 Seat holds, orders and a rate limiter
- [ ] M6 Simulated payment, hold expiry, cancellation, sales report
- [ ] M7 Final documentation
