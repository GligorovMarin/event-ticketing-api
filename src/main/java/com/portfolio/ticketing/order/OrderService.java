package com.portfolio.ticketing.order;

import com.portfolio.ticketing.availability.SeatView;
import com.portfolio.ticketing.common.ConflictException;
import com.portfolio.ticketing.common.NotFoundException;
import com.portfolio.ticketing.event.Event;
import com.portfolio.ticketing.event.EventRepository;
import com.portfolio.ticketing.event.EventStatus;
import com.portfolio.ticketing.event.Seat;
import com.portfolio.ticketing.event.SeatRepository;
import com.portfolio.ticketing.event.Section;
import com.portfolio.ticketing.event.SectionRepository;
import com.portfolio.ticketing.order.dto.CreateOrderRequest;
import com.portfolio.ticketing.order.dto.OrderResponse;
import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creating and reading orders. Creating an order = HOLDING seats for 10 minutes while the customer pays.
 *
 * <h3>How createOrder() stays correct when many customers click at once</h3>
 * <ol>
 *   <li><b>Lock the seat rows in a fixed order</b> ({@code SELECT ... ORDER BY id FOR UPDATE}). Nobody else can
 *       change these seats until we commit, and because everyone locks in the same order nobody can deadlock.</li>
 *   <li><b>Check availability only AFTER the locks are held.</b> Checking first and locking later would leave a
 *       gap in which someone else could take the seat (a "check-then-act" race).</li>
 *   <li><b>Write everything in one transaction</b>: order, items and the held seats. If anything fails, nothing
 *       at all is kept, so there are never half-held seats.</li>
 *   <li>The database has the last word: a partial unique index allows one PENDING order per customer and event.</li>
 * </ol>
 *
 * <p>Connections: {@link OrderController} calls it; seats via {@link SeatRepository}; prices from
 * {@link SectionRepository}; "now" is the injected {@link Clock}; the rule "can this seat be taken?" is
 * {@link SeatView#isTakeable}.
 */
@Service
public class OrderService {

    /** How long a request may wait for another customer's seat locks before giving up with a 409. */
    private static final String LOCK_WAIT_LIMIT = "5s";

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final SeatRepository seats;
    private final SectionRepository sections;
    private final EventRepository events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderItemRepository orderItems, SeatRepository seats,
                        SectionRepository sections, EventRepository events, JdbcTemplate jdbc, Clock clock) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.seats = seats;
        this.sections = sections;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse create(AuthenticatedUser customer, CreateOrderRequest request) {
        Instant now = clock.instant();

        // Never wait forever for someone else's locks. SET LOCAL lasts until the end of this transaction only.
        // If the wait is exceeded, PostgreSQL aborts the statement and ApiExceptionHandler answers 409 "busy".
        jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_WAIT_LIMIT + "'");

        // 1. The event must be on sale and not started.
        Event event = events.findByIdAndStatus(request.eventId(), EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Event " + request.eventId() + " not found"));
        if (!event.getStartsAt().isAfter(now)) {
            throw new ConflictException("This event has already started");
        }

        // 2. If this customer has an older order for the event whose hold ran out, close it, so that a new
        //    one is allowed (only one PENDING order per customer and event may exist).
        orders.expireLapsedPending(customer.id(), event.getId(), now);

        // 3. LOCK the requested seats, in ascending id order (see SeatRepository.lockAllByIdOrdered).
        List<UUID> seatIds = request.seatIds().stream().sorted().toList();
        List<Seat> lockedSeats = seats.lockAllByIdOrdered(seatIds);

        // Every requested id must exist AND belong to this event. Otherwise the whole request is rejected.
        boolean allBelongToEvent = lockedSeats.stream().allMatch(seat -> seat.getEventId().equals(event.getId()));
        if (lockedSeats.size() != seatIds.size() || !allBelongToEvent) {
            throw new NotFoundException("One or more seats do not exist in this event");
        }

        // 4. Check availability, now that nobody can change these rows. Report ALL taken seats at once.
        List<String> taken = lockedSeats.stream()
                .filter(seat -> !SeatView.isTakeable(seat, now))
                .map(Seat::label)
                .toList();
        if (!taken.isEmpty()) {
            throw new ConflictException("These seats are not available: " + String.join(", ", taken));
        }

        // 5. Price the order from the sections' prices (never from the client).
        Map<UUID, Section> sectionsById = sections
                .findAllById(lockedSeats.stream().map(Seat::getSectionId).distinct().toList()).stream()
                .collect(Collectors.toMap(Section::getId, Function.identity()));
        int totalCents = lockedSeats.stream().mapToInt(seat -> sectionsById.get(seat.getSectionId()).getPriceCents()).sum();

        // 6. Create the order. The partial unique index decides if the customer already has one in progress.
        Instant holdExpiresAt = now.plus(OrderRules.HOLD_DURATION);
        Order order;
        try {
            order = orders.saveAndFlush(new Order(customer.id(), event.getId(), totalCents, holdExpiresAt));
        } catch (DataIntegrityViolationException alreadyHasPendingOrder) {
            throw new ConflictException("You already have an order in progress for this event. Pay for it or let it expire first");
        }

        // 7. Hold the seats and record the items. The seats are managed (locked) entities, so Hibernate saves
        //    the changes at commit; if anything above or below throws, the whole transaction rolls back.
        List<OrderItem> items = new ArrayList<>();
        for (Seat seat : lockedSeats) {
            seat.hold(order.getId(), holdExpiresAt);
            items.add(new OrderItem(order.getId(), seat.getId(), sectionsById.get(seat.getSectionId()).getPriceCents()));
        }
        orderItems.saveAll(items);

        return toResponses(List.of(order), now).getFirst();
    }

    /** The customer's orders, newest first. A fixed number of queries however many orders there are. */
    @Transactional(readOnly = true)
    public List<OrderResponse> listMine(AuthenticatedUser customer) {
        return toResponses(orders.findByCustomerIdOrderByCreatedAtDesc(customer.id()), clock.instant());
    }

    /** One of the customer's own orders. Someone else's order looks exactly like a missing one (404). */
    @Transactional(readOnly = true)
    public OrderResponse getMine(AuthenticatedUser customer, UUID orderId) {
        Order order = orders.findByIdAndCustomerId(orderId, customer.id())
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));

        return toResponses(List.of(order), clock.instant()).getFirst();
    }

    /** One order as a response, built at the current time. Used by OrderLifecycleService after pay / cancel. */
    @Transactional(readOnly = true)
    public OrderResponse respond(Order order) {
        return toResponses(List.of(order), clock.instant()).getFirst();
    }

    // ---- Building responses -----------------------------------------------------------------------

    /**
     * Turns orders into responses using a FIXED number of queries (items, seats, sections, events), however many
     * orders there are. Each lookup table below is a hash map, so attaching the details to each order is O(1)
     * per item. Loading per order instead would be the "N+1 queries" problem.
     */
    private List<OrderResponse> toResponses(Collection<Order> orderList, Instant now) {
        if (orderList.isEmpty()) {
            return List.of();
        }

        List<UUID> orderIds = orderList.stream().map(Order::getId).toList();
        List<OrderItem> items = orderItems.findByOrderIdIn(orderIds);

        Map<UUID, Seat> seatsById = seats.findAllById(items.stream().map(OrderItem::getSeatId).distinct().toList()).stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity()));
        Map<UUID, Section> sectionsById = sections.findAllById(seatsById.values().stream().map(Seat::getSectionId).distinct().toList()).stream()
                .collect(Collectors.toMap(Section::getId, Function.identity()));
        Map<UUID, Event> eventsById = events.findAllById(orderList.stream().map(Order::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));

        // Group the items by order once, then build each response from its group.
        Map<UUID, List<OrderItem>> itemsByOrder = new HashMap<>();
        for (OrderItem item : items) {
            itemsByOrder.computeIfAbsent(item.getOrderId(), id -> new ArrayList<>()).add(item);
        }

        List<OrderResponse> responses = new ArrayList<>();
        for (Order order : orderList) {
            List<OrderResponse.Item> itemResponses = itemsByOrder.getOrDefault(order.getId(), List.of()).stream()
                    .map(item -> {
                        Seat seat = seatsById.get(item.getSeatId());
                        Section section = sectionsById.get(seat.getSectionId());
                        return new OrderResponse.Item(seat.getId(), section.getName(), seat.getRowLabel(),
                                seat.getSeatNumber(), seat.label(), item.getPriceCents());
                    })
                    // Seat-map order, so the response reads the same way as the tickets will.
                    .sorted(java.util.Comparator.comparing(OrderResponse.Item::section)
                            .thenComparing(OrderResponse.Item::row)
                            .thenComparingInt(OrderResponse.Item::number))
                    .toList();

            responses.add(new OrderResponse(order.getId(), order.getEventId(), eventsById.get(order.getEventId()).getName(),
                    order.effectiveStatus(now), order.getTotalCents(), order.getHoldExpiresAt(), order.getCreatedAt(), order.getPaidAt(), order.getCancelledAt(), itemResponses));
        }
        return responses;
    }
}
