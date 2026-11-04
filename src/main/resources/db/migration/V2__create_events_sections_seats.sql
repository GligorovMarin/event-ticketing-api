-- V2__create_events_sections_seats.sql
--
-- The seat map: an EVENT has SECTIONS (Orchestra, Balcony ...), each section is a grid of SEATS.
-- Java side: event/Event, event/Section, event/Seat.

CREATE TABLE events (
    id           UUID          PRIMARY KEY,
    -- Who owns the event. RESTRICT: a user who organizes events cannot be deleted out from under them.
    organizer_id UUID          NOT NULL REFERENCES users (id),
    name         VARCHAR(150)  NOT NULL,
    description  VARCHAR(2000),
    venue        VARCHAR(150)  NOT NULL,
    -- A moment in time, stored in UTC (timestamptz). Shown to people in their own time zone by the client.
    starts_at    TIMESTAMPTZ   NOT NULL,
    -- DRAFT: being set up, invisible to the public.  PUBLISHED: on sale.  CANCELLED: called off.
    status       VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- "My events" lists and ownership checks filter by organizer.
CREATE INDEX events_organizer_idx ON events (organizer_id);
-- The public listing: published events ordered by date. A PARTIAL index (only published rows) stays small
-- and is exactly what that query needs.
CREATE INDEX events_public_listing_idx ON events (starts_at) WHERE status = 'PUBLISHED';

CREATE TABLE sections (
    id            UUID         PRIMARY KEY,
    -- CASCADE: deleting a (draft) event removes its sections and seats with it.
    event_id      UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name          VARCHAR(50)  NOT NULL,
    -- Money is stored as an INTEGER number of cents. Floating point (0.1 + 0.2 != 0.3) must never touch money.
    price_cents   INTEGER      NOT NULL CHECK (price_cents >= 0),
    row_count     INTEGER      NOT NULL CHECK (row_count BETWEEN 1 AND 26),
    seats_per_row INTEGER      NOT NULL CHECK (seats_per_row BETWEEN 1 AND 50),
    -- Two sections of one event cannot share a name.
    CONSTRAINT sections_event_name_key UNIQUE (event_id, name)
);

CREATE TABLE seats (
    id              UUID        PRIMARY KEY,
    section_id      UUID        NOT NULL REFERENCES sections (id) ON DELETE CASCADE,
    -- Denormalised copy of the section's event: availability queries ask "all seats of this event" and
    -- would otherwise need a JOIN on every request. Kept consistent because seats are only created together
    -- with their section, in one transaction.
    event_id        UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    row_label       VARCHAR(3)  NOT NULL,
    seat_number     INTEGER     NOT NULL CHECK (seat_number >= 1),
    -- AVAILABLE: free.  HELD: reserved by a customer's pending order until hold_expires_at.  SOLD: paid for.
    status          VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    -- The order holding or owning the seat. The foreign key to the orders table is added when that table
    -- is created (a later migration).
    order_id        UUID,
    hold_expires_at TIMESTAMPTZ,
    -- A seat is identified by (section, row, number): the same seat can never exist twice.
    CONSTRAINT seats_position_key UNIQUE (section_id, row_label, seat_number),
    -- A HELD seat must say when its hold ends; any other seat must not have a hold date.
    -- "(a) = (b)" compares two booleans: both true, or both false.
    CONSTRAINT seats_hold_consistency_check CHECK ((status = 'HELD') = (hold_expires_at IS NOT NULL)),
    -- A HELD or SOLD seat belongs to an order; an AVAILABLE one does not.
    CONSTRAINT seats_order_consistency_check CHECK ((status = 'AVAILABLE') = (order_id IS NULL))
);

-- "All seats of this event, in seat-map order": the availability map and the best-seats search.
CREATE INDEX seats_event_idx ON seats (event_id, section_id, row_label, seat_number);
