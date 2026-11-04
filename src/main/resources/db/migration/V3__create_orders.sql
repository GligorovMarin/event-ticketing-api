-- V3__create_orders.sql
--
-- Orders: a customer's claim on one or more seats. Java side: order/Order, order/OrderItem.
-- An order is born PENDING (the seats are HELD for 10 minutes) and becomes PAID, CANCELLED or EXPIRED.

CREATE TABLE orders (
    id              UUID         PRIMARY KEY,
    customer_id     UUID         NOT NULL REFERENCES users (id),
    event_id        UUID         NOT NULL REFERENCES events (id),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED')),
    -- Sum of the seat prices at the moment of ordering, in cents.
    total_cents     INTEGER      NOT NULL CHECK (total_cents >= 0),
    -- When the 10-minute hold ends. Kept after payment too, as a record of the original deadline.
    hold_expires_at TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    paid_at         TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    -- Optimistic locking counter: Hibernate adds "WHERE version = ?" to every UPDATE and bumps it. If two
    -- requests change the same order at once (say "pay" and "cancel"), the slower one updates 0 rows and fails
    -- instead of silently overwriting the other.
    version         BIGINT       NOT NULL DEFAULT 0
);

-- A customer can have only ONE order in progress per event. A PARTIAL unique index: it only looks at PENDING
-- rows, so any number of finished (PAID / CANCELLED / EXPIRED) orders are fine. This is enforced by the
-- database, so even two simultaneous requests cannot both succeed. It stops one account from hoarding seats
-- by opening order after order.
CREATE UNIQUE INDEX orders_one_pending_per_customer_event
    ON orders (customer_id, event_id) WHERE status = 'PENDING';

CREATE INDEX orders_customer_idx ON orders (customer_id, created_at DESC);

CREATE TABLE order_items (
    id          UUID    PRIMARY KEY,
    order_id    UUID    NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    seat_id     UUID    NOT NULL REFERENCES seats (id),
    -- The price AT PURCHASE TIME. Copying it (instead of looking up the section's price later) keeps old orders
    -- correct if an organizer's prices ever change.
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    -- The same seat cannot appear twice in one order.
    CONSTRAINT order_items_order_seat_key UNIQUE (order_id, seat_id)
);

CREATE INDEX order_items_order_idx ON order_items (order_id);

-- Now that orders exist, seats can point at them for real. (V2 created seats.order_id without this link.)
ALTER TABLE seats
    ADD CONSTRAINT seats_order_fk FOREIGN KEY (order_id) REFERENCES orders (id);
