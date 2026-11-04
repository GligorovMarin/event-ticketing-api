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
| `GET /events`, `GET /events/{id}` (published events only) | yes | | |
| `GET /events/{id}/seats`, `GET /events/{id}/seats/suggestions` | yes | | |
| `POST /events`, `POST /events/{id}/sections`, `POST /events/{id}/publish` | | | own events |
| `GET /organizer/events`, `GET /organizer/events/{id}` (drafts included) | | | own events |

Missing, forged, tampered or expired token -> `401`. Valid token but wrong role -> `403`. Every error is an
RFC 9457 problem-details JSON document. Everything not listed as public is locked by default.

In Swagger (`/docs`): call `POST /auth/login`, copy `accessToken`, click **Authorize**, paste it.

## Events and seat maps

An organizer builds an event as a **draft**: `POST /events`, then one or more **sections**
(`POST /events/{id}/sections` with `rows`, `seatsPerRow` and a price in cents). The server generates the seats
(rows A to Z, seats numbered from 1) with a single JDBC batch insert. `POST /events/{id}/publish` puts the
event on sale and **freezes the seat map**, so seats never appear or vanish under customers.

- A section and all its seats are created in one transaction: all or nothing.
- Organizer writes lock the event row (`SELECT ... FOR UPDATE`), so "publish" cannot race with "add section".
- Another organizer's event looks exactly like a missing one (`404`, never `403`).
- Money is stored as integer cents, never as a decimal number.
- The public listing (`GET /events?q=&page=&size=`) shows upcoming published events, soonest first, and fetches
  every event's cheapest price with one grouped query (no N+1; a test counts the SQL statements).
- Search text goes through a bound parameter (no SQL injection) and `%` / `_` are escaped, so they are plain characters.

## Seat availability and "find N seats together"

`GET /events/{id}/seats` returns the whole seat map (sections, rows, seats) with each seat's **current** status.
A seat whose hold has run out is shown as available even if no background job has cleaned the row yet
("lazy expiry"), so the answer is right regardless of when cleanup runs. Who holds or bought a seat is never exposed.

`GET /events/{id}/seats/suggestions?sectionId=&count=4` finds the best block of N **adjacent** free seats in one row
(`AdjacentSeatFinder`, a pure function):

1. rows are tried front to back, and the first row with any block wins;
2. inside that row, the block whose middle is closest to the middle of the row;
3. on a tie, the leftmost.

It builds a **prefix-sum array** of unavailable seats per row, so "are seats i..i+N-1 all free?" is two array reads.
Sliding the window along a row is O(1) per position, so the whole search is **O(S)** for S seats in the section
(a naive re-check of N seats per position would be O(S x N)). The unit tests compare it with a brute-force version
on 2000 random seat maps.

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
                                  availability/ AvailabilityController -> AvailabilityService -> SeatRepository;
                                               AdjacentSeatFinder (the algorithm), SeatView (lazy expiry)
                                  order/       OrderRules: limits shared by availability and (next) ordering
                                  event/       EventController (public + organizer writes) -> EventService
                                               -> EventRepository / SectionRepository; SeatMapService batch-inserts seats;
                                               SeatMapLayout = pure rules about rows and seat numbering
                                  common/      exceptions + ApiExceptionHandler (all errors become problem-details JSON)
                                  resources/application.yml        (settings)
                                  resources/db/migration/V*.sql    (Flyway: the database schema)

  Tests:  src/test/java/**/*Test.java  unit tests (no Spring, no database)
          src/test/java/**/*IT.java    integration tests (@IntegrationTest: real app + real PostgreSQL)
```

## Roadmap

- [x] **M1** Project skeleton, Docker, Flyway, test setup, CI
- [x] **M2** Users, JWT login, roles (Customer / Organizer), everything locked by default
- [x] **M3** Events and seat maps (organizer builds a draft, publishes it; public browsing with paging and search)
- [x] **M4** Seat availability (live status, lazy hold expiry) and the "find N seats together" algorithm
- [ ] M5 Seat holds, orders and a rate limiter
- [ ] M6 Simulated payment, hold expiry, cancellation, sales report
- [ ] M7 Final documentation
