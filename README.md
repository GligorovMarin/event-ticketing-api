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

## Accounts and permissions

Two roles, both open to self-registration (`POST /auth/register` with `"role": "CUSTOMER"` or `"ORGANIZER"`):
**customers** buy tickets; **organizers** create events and see the sales of their own events.

| Endpoint | Public | Customer | Organizer |
|---|:-:|:-:|:-:|
| `GET /actuator/health`, `/docs`, `POST /auth/register`, `POST /auth/login` | yes | | |
| `GET /auth/me` | | yes | yes |

Missing, forged, tampered or expired token -> `401`. Valid token but wrong role -> `403`. Every error is an
RFC 9457 problem-details JSON document. Everything not listed as public is locked by default.

In Swagger (`/docs`): call `POST /auth/login`, copy `accessToken`, click **Authorize**, paste it.

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
  TicketingApplication ──scans──> config/SecurityConfig   (who may call what; locked by default; bcrypt bean)
                                  config/WebConfig        (Clock bean, AuthenticatedUser parameter resolver)
                                  config/OpenApiConfig    (Swagger UI text and Authorize button)
                                  auth/        AuthController -> AuthService -> UserRepository (users table)
                                               JwtService signs tokens; JwtConfig builds the verifier Spring Security uses
                                  security/    AuthenticatedUser: what controllers receive as "the caller"
                                  user/        User entity + UserRepository (maps the users table)
                                  common/      exceptions + ApiExceptionHandler (all errors become problem-details JSON)
                                  resources/application.yml        (settings)
                                  resources/db/migration/V*.sql    (Flyway: the database schema)

  Tests:  src/test/java/**/*Test.java  unit tests (no Spring, no database)
          src/test/java/**/*IT.java    integration tests (@IntegrationTest: real app + real PostgreSQL)
```

## Roadmap

- [x] **M1** Project skeleton, Docker, Flyway, test setup, CI
- [x] **M2** Users, JWT login, roles (Customer / Organizer), everything locked by default
- [ ] M3 Events and seat maps
- [ ] M4 Seat availability and the "find N seats together" algorithm
- [ ] M5 Seat holds, orders and a rate limiter
- [ ] M6 Simulated payment, hold expiry, cancellation, sales report
- [ ] M7 Final documentation
