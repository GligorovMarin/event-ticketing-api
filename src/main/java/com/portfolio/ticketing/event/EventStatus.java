package com.portfolio.ticketing.event;

/**
 * Where an event is in its life.
 * <pre>
 *   DRAFT -- publish --> PUBLISHED -- cancel --> CANCELLED
 * </pre>
 * <ul>
 *   <li>DRAFT: the organizer is still building the seat map; invisible to the public.</li>
 *   <li>PUBLISHED: on sale. The seat map is frozen: sections can no longer be added.</li>
 *   <li>CANCELLED: called off (added in a later milestone together with refunds).</li>
 * </ul>
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}
