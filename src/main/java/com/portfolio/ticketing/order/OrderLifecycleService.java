package com.portfolio.ticketing.order;

import com.portfolio.ticketing.common.ConflictException;
import com.portfolio.ticketing.common.NotFoundException;
import com.portfolio.ticketing.event.Event;
import com.portfolio.ticketing.event.EventRepository;
import com.portfolio.ticketing.event.Seat;
import com.portfolio.ticketing.event.SeatRepository;
import com.portfolio.ticketing.event.SeatStatus;
import com.portfolio.ticketing.order.dto.OrderResponse;
import com.portfolio.ticketing.payment.Payment;
import com.portfolio.ticketing.payment.PaymentDeclinedException;
import com.portfolio.ticketing.payment.PaymentGateway;
import com.portfolio.ticketing.payment.PaymentRepository;
import com.portfolio.ticketing.payment.PaymentStatus;
import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What happens to an order AFTER it was created: it gets paid, cancelled, or expires.
 *
 * <h3>One rule keeps all of this deadlock-free: the LOCK ORDER</h3>
 * Every method here locks the ORDER row first and then its SEATS in ascending id. Creating an order
 * (OrderService) locks seats only. Since nobody ever locks a seat and then waits for an order row someone
 * else holds, a cycle of waiting transactions cannot form.
 *
 * <h3>Why each method locks the order row</h3>
 * "Pay", "cancel", "pay again (double click)" and the expiry sweep can all hit the same order at the same
 * moment. The row lock (SELECT ... FOR UPDATE) makes them take turns; each one then re-reads the order and acts on
 * the state the previous one left. Hibernate's {@code @Version} on the order is a second line of defence.
 *
 * <p>Connections: {@link OrderController} (pay, cancel) and {@link OrderExpirySweeper} (expire) call it;
 * money goes through {@link PaymentGateway}; every attempt is recorded in {@link PaymentRepository}.
 */
@Service
public class OrderLifecycleService {

    private static final String LOCK_WAIT_LIMIT = "5s";

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final SeatRepository seats;
    private final EventRepository events;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OrderService orderService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrderLifecycleService(OrderRepository orders, OrderItemRepository orderItems, SeatRepository seats,
                                 EventRepository events, PaymentRepository payments, PaymentGateway gateway,
                                 OrderService orderService, JdbcTemplate jdbc, Clock clock) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.seats = seats;
        this.events = events;
        this.payments = payments;
        this.gateway = gateway;
        this.orderService = orderService;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---- Paying -----------------------------------------------------------------------------------

    /**
     * Charges the customer and turns the held seats into sold seats.
     *
     * <ul>
     *   <li>Paying an already PAID order just returns it: pressing "pay" twice never charges twice (idempotent).</li>
     *   <li>A DECLINED card keeps the order open for another try; the attempt is recorded
     *       ({@code noRollbackFor}: the exception must not undo the DECLINED payment row).</li>
     *   <li>If the provider is unreachable the exception rolls everything back: nothing was charged, nothing changed.</li>
     *   <li>The database refuses a second successful payment for one order whatever this code does
     *       (partial unique index, migration V4).</li>
     * </ul>
     *
     * <p>Trade-off, stated openly: the gateway is called INSIDE the transaction, so the order and seat locks are held
     * during that call. That is only acceptable because the call is quick and the locked rows belong to one order.
     * A production system would first record a "payment started" row, commit, call the provider with no locks held, and
     * then record the result, plus a reconciliation job for the case where the process dies in between.
     */
    @Transactional(noRollbackFor = PaymentDeclinedException.class)
    public OrderResponse pay(AuthenticatedUser customer, UUID orderId, String paymentToken) {
        Instant now = clock.instant();
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_WAIT_LIMIT + "'");

        Order order = orders.findOwnedForUpdate(orderId, customer.id())
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));

        OrderStatus status = order.effectiveStatus(now);
        if (status == OrderStatus.PAID) {
            return orderService.respond(order); // already paid: same answer, no second charge
        }
        if (status == OrderStatus.EXPIRED) {
            throw new ConflictException("The hold on these seats has expired. Please choose your seats again");
        }
        if (status != OrderStatus.PENDING) {
            throw new ConflictException("This order is " + status.name().toLowerCase() + " and cannot be paid");
        }

        // Lock the order's seats and make sure they are STILL held for this order. Normally they are; this guards
        // against any path that gave them to someone else.
        List<Seat> lockedSeats = lockSeatsOf(orderId);
        boolean allStillHeldForThisOrder = lockedSeats.stream()
                .allMatch(seat -> orderId.equals(seat.getOrderId()) && seat.getStatus() == SeatStatus.HELD);
        if (lockedSeats.isEmpty() || !allStillHeldForThisOrder) {
            throw new ConflictException("Your seats are no longer held for this order. Please choose your seats again");
        }

        PaymentGateway.ChargeResult result = gateway.charge(paymentToken, order.getTotalCents(), orderId.toString());
        if (!result.approved()) {
            payments.save(Payment.declined(orderId, order.getTotalCents(), result.declineReason()));
            throw new PaymentDeclinedException(result.declineReason());
        }

        payments.save(Payment.approved(orderId, order.getTotalCents(), result.reference()));
        order.markPaid(now);
        lockedSeats.forEach(Seat::sell);

        return orderService.respond(order);
    }

    // ---- Cancelling -------------------------------------------------------------------------------

    /**
     * Cancels the customer's own order and puts its seats back on sale.
     * <ul>
     *   <li>PENDING: no money was taken; cancel at any time.</li>
     *   <li>PAID: only until 48 hours before the event; the payment is refunded through the gateway. If the refund
     *       fails, the exception rolls everything back and the order stays PAID.</li>
     * </ul>
     */
    @Transactional
    public OrderResponse cancel(AuthenticatedUser customer, UUID orderId) {
        Instant now = clock.instant();
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_WAIT_LIMIT + "'");

        Order order = orders.findOwnedForUpdate(orderId, customer.id())
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));

        switch (order.effectiveStatus(now)) {
            case CANCELLED -> throw new ConflictException("This order is already cancelled");
            case EXPIRED -> throw new ConflictException("This order has already expired");
            case PAID -> refundPaidOrder(order, now);
            case PENDING -> { /* nothing was charged, so there is nothing to refund */ }
        }

        releaseSeatsOf(order);
        order.markCancelled(now);
        return orderService.respond(order);
    }

    private void refundPaidOrder(Order order, Instant now) {
        Event event = events.findById(order.getEventId()).orElseThrow();
        if (!OrderRules.canCancelPaidOrder(event.getStartsAt(), now)) {
            throw new ConflictException("Paid tickets can be cancelled up to "
                    + OrderRules.PAID_ORDER_CANCELLATION_NOTICE.toHours() + " hours before the event");
        }

        Payment payment = payments.findByOrderIdAndStatus(order.getId(), PaymentStatus.APPROVED)
                .orElseThrow(() -> new IllegalStateException("Paid order " + order.getId() + " has no approved payment"));

        gateway.refund(payment.getGatewayReference(), payment.getAmountCents()); // throws if the provider fails
        payment.markRefunded(now);
    }

    // ---- Expiring (called by the sweep) -----------------------------------------------------------

    /**
     * Writes down that an order's hold ended and frees its seats. Safe to call at any time and more than once: it
     * re-checks under the lock and does nothing if the order is no longer a lapsed PENDING order (it may have been
     * paid or cancelled a moment ago).
     *
     * @return true if the order was expired by this call
     */
    @Transactional
    public boolean expire(UUID orderId) {
        Instant now = clock.instant();
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_WAIT_LIMIT + "'");

        Order order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING || order.getHoldExpiresAt().isAfter(now)) {
            return false;
        }

        releaseSeatsOf(order);
        order.markExpired();
        return true;
    }

    // ---- Shared -----------------------------------------------------------------------------------

    /** Locks the seats of an order in ascending id order (see SeatRepository.lockAllByIdOrdered). */
    private List<Seat> lockSeatsOf(UUID orderId) {
        List<UUID> seatIds = orderItems.findByOrderId(orderId).stream().map(OrderItem::getSeatId).sorted().toList();

        return seats.lockAllByIdOrdered(seatIds);
    }

    /**
     * Puts the order's seats back on sale. Only seats that STILL point at this order are released: if a lapsed hold
     * was already taken over by another customer, that seat now belongs to their order and must be left alone.
     */
    private void releaseSeatsOf(Order order) {
        for (Seat seat : lockSeatsOf(order.getId())) {
            if (order.getId().equals(seat.getOrderId())) {
                seat.release();
            }
        }
    }
}
