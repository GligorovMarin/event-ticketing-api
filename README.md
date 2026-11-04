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
| `POST /orders`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/pay`, `POST /orders/{id}/cancel` | | own orders (customers only) | |
| `GET /organizer/events/{id}/sales` | | | own events |
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

## Holding seats: how two customers can never get the same seat

`POST /orders` with `{eventId, seatIds}` (max 6) holds the seats for **10 minutes** while the customer pays. The
client sends seat ids only; prices and totals are computed on the server.

1. **Lock the seat rows in a fixed order**: `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`. Anyone else
   wanting one of those seats waits. Because *every* request locks in ascending id order, `[A1, A2]` against
   `[A2, A1]` can never deadlock (a test fires 20 such pairs and, by inspecting the loser's message, proves
   PostgreSQL's deadlock detector never had to step in).
2. **Check availability only after the locks are held** (checking first leaves a gap for someone else).
3. **Write order, items and held seats in one transaction.** Any failure rolls everything back, so a refused request
   holds nothing at all.
4. **The database backs it up:** a partial unique index allows one `PENDING` order per customer and event; foreign keys
   and CHECK constraints tie seats to real orders; `SET LOCAL lock_timeout = '5s'` stops a request waiting forever.
5. **Lazy expiry:** a seat whose hold ran out can be taken at once, and an order shows as `EXPIRED` the moment its
   hold ends, with no cleanup job needed for correctness.

The concurrency tests fire 8 customers at one seat, 5 orders from one customer at once, and 15 customers at 12 seats,
then check invariants (no seat in two live orders, no orphaned holds, only `201` or `409` ever returned). They run
against a deliberately small connection pool (5) because that is where concurrency bugs show up.

### Paying, cancelling, expiring

`POST /orders/{id}/pay` with a payment **token** (never card numbers: a real provider turns card details into a token on
the customer's device, which keeps card data off this server). The provider sits behind a `PaymentGateway` interface
(strategy pattern) with a `FakePaymentGateway`: `tok_ok` approves, `tok_declined` returns `402`, `tok_timeout` returns `503`
with nothing charged. Every attempt is recorded in `payments`.

- **Never charged twice.** The order row is locked while paying, paying an already paid order just returns it (a test fires
  four simultaneous "pay" requests: one payment), and a **partial unique index** lets the database refuse a second
  successful payment for an order whatever the code does.
- **A declined card keeps the order open** (and the `DECLINED` attempt is kept: `noRollbackFor`); a provider outage rolls
  everything back.
- **One lock order everywhere:** the order row first, then its seats in ascending id. That is why pay, cancel, the expiry
  sweep and ordering can never deadlock with one another (a test races "pay" against "cancel" 12 times).
- `POST /orders/{id}/cancel`: a pending order any time; a paid order until **48 hours before the event**, refunded through
  the gateway (if the refund fails the order stays paid). The seats go back on sale. Seats that another customer has taken
  over since are never freed by mistake.
- **Expiry sweep:** a `@Scheduled` job (every minute, `app.orders.sweep-interval`) frees the seats of orders whose hold ended.
  Correctness never depends on it (lazy expiry already treats a lapsed hold as free); it keeps stored data and reports tidy.
  It is safe on several servers at once and is switched off in tests, which run it explicitly.
- **Known trade-off:** the gateway is called inside the database transaction, which is fine for a fast fake and wrong for
  a slow real provider. The production pattern is "record intent, commit, call the provider with no locks held, record
  the result", plus a reconciliation job; documented in `OrderLifecycleService`.

### Sales report

`GET /organizer/events/{id}/sales` is computed by the database with `GROUP BY` / `SUM` / `COUNT` (three queries, however big
the event): capacity, sold, held, available, revenue (from the price copied at order time), orders by status and a
per-section breakdown. `LEFT JOIN`s keep sections with no sales in the report.

### Rate limiting

`POST /orders` allows **10 attempts per minute per customer** (`@RateLimited`); the 11th gets `429` with `Retry-After`.
It is a **sliding-window log**: per user, a queue of recent request times; old ones are dropped from the front, a new one
is added at the back, so each request costs amortised **O(1)** and memory is O(limit) per user. A fixed per-minute
counter would allow 10 requests at 12:00:59 and 10 more at 12:01:00; the sliding window does not. Counters live in this
server's memory (several servers would each count separately; a shared store such as Redis would fix that).

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
                                  order/       OrderController -> OrderService: lock seats in id order, check, hold, price
                                               OrderLifecycleService: pay, cancel, expire (order row lock, then seat locks)
                                               OrderExpirySweeper: the @Scheduled job
                                  payment/     PaymentGateway interface + FakePaymentGateway; Payment (audit trail)
                                  report/      SalesReportService: SQL aggregation for organizers
                                               Order (optimistic @Version), OrderItem, OrderRules (limits)
                                  ratelimit/   SlidingWindowRateLimiter, @RateLimited, the interceptor that applies it
                                  availability/ AvailabilityController -> AvailabilityService -> SeatRepository;
                                               AdjacentSeatFinder (the algorithm), SeatView (lazy expiry)
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
- [x] **M5** Seat holds, orders (pessimistic locking) and a sliding-window rate limiter
- [x] **M6** Simulated payment, hold expiry sweep, cancellation with refunds, organizer sales report
- [ ] M7 Final documentation
