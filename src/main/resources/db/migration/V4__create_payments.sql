-- V4__create_payments.sql
--
-- Payments: every attempt to pay for an order, successful or not (an audit trail).
-- Java side: payment/Payment. The payment itself is made through the PaymentGateway interface.

CREATE TABLE payments (
    id                UUID         PRIMARY KEY,
    order_id          UUID         NOT NULL REFERENCES orders (id),
    amount_cents      INTEGER      NOT NULL CHECK (amount_cents >= 0),
    -- APPROVED: money taken.  DECLINED: the card was refused (the order stays open for another try).
    -- REFUNDED: an APPROVED payment that was given back when the order was cancelled.
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('APPROVED', 'DECLINED', 'REFUNDED')),
    -- The payment provider's own reference for the charge; needed to refund it later.
    gateway_reference VARCHAR(100),
    failure_reason    VARCHAR(200),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    refunded_at       TIMESTAMPTZ,
    -- A refunded payment says when; no other payment does.
    CONSTRAINT payments_refund_consistency_check CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL)),
    -- An approved or refunded payment always has a provider reference.
    CONSTRAINT payments_reference_check CHECK (status = 'DECLINED' OR gateway_reference IS NOT NULL)
);

-- THE guarantee against charging twice: an order can have at most ONE successful payment (APPROVED, or later
-- REFUNDED: the same row changes state). A partial unique index only looks at those rows, so any number of
-- DECLINED attempts is fine. Even if the application's locking were wrong, the database would refuse a second charge.
CREATE UNIQUE INDEX payments_one_success_per_order
    ON payments (order_id) WHERE status IN ('APPROVED', 'REFUNDED');

CREATE INDEX payments_order_idx ON payments (order_id);

-- The expiry sweep asks "which PENDING orders have a hold that ended?" every minute. A partial index holding
-- only PENDING orders, sorted by deadline, answers that without scanning finished orders.
CREATE INDEX orders_pending_expiry_idx ON orders (hold_expires_at) WHERE status = 'PENDING';

-- Releasing or checking the seats of one order ("which seats does order X hold?") should not scan every seat.
CREATE INDEX seats_order_idx ON seats (order_id) WHERE order_id IS NOT NULL;
