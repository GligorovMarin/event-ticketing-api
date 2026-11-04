package com.portfolio.ticketing.availability;

import com.portfolio.ticketing.event.Seat;
import com.portfolio.ticketing.event.SeatStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One seat as the availability code sees it: where it is, and whether a customer could take it RIGHT NOW.
 *
 * <p>The stored status can be out of date: a seat marked HELD whose hold has run out is, in truth, free
 * again, even if no background job has tidied the row yet. {@link #of(Seat, Instant)} works out the
 * <i>effective</i> status from the stored one and the current time. Doing it at read time means the answer is
 * correct even if the cleanup job is late or not running (the same "lazy expiry" idea as the clinic project's
 * waitlist holds).
 */
public record SeatView(UUID id, UUID sectionId, String rowLabel, int seatNumber, SeatStatus status) {

    public static SeatView of(Seat seat, Instant now) {
        return new SeatView(seat.getId(), seat.getSectionId(), seat.getRowLabel(), seat.getSeatNumber(),
                effectiveStatus(seat.getStatus(), seat.getHoldExpiresAt(), now));
    }

    /**
     * HELD whose expiry is now or earlier counts as AVAILABLE. "Now or earlier" (not "strictly earlier"):
     * a hold that ends at 12:00:00 is over AT 12:00:00, so the seat can be taken from that instant.
     */
    static SeatStatus effectiveStatus(SeatStatus stored, Instant holdExpiresAt, Instant now) {
        boolean holdHasLapsed = stored == SeatStatus.HELD && holdExpiresAt != null && !holdExpiresAt.isAfter(now);

        return holdHasLapsed ? SeatStatus.AVAILABLE : stored;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }
}
