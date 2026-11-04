package com.portfolio.ticketing.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code events}. See migration V2 for the column rules.
 *
 * <p>The organizer is stored as a plain {@code organizerId}, not as a {@code @ManyToOne User} object. That is a
 * deliberate choice: JPA associations make it easy to trigger hidden extra queries (lazy loading) or to load
 * far more than you need. With plain ids, every query in the code is a query you wrote on purpose.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private UUID organizerId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 150)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status = EventStatus.DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
    }

    public Event(UUID organizerId, String name, String description, String venue, Instant startsAt) {
        this.organizerId = organizerId;
        this.name = name;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
    }

    /** DRAFT -> PUBLISHED. The rules (needs a section, not already published) are checked by EventService. */
    public void publish() {
        this.status = EventStatus.PUBLISHED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizerId() {
        return organizerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
