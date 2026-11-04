package com.portfolio.ticketing.availability.dto;

import java.util.List;
import java.util.UUID;

/**
 * The answer to "find me N seats together". {@code seats} is empty (and {@code totalPriceCents} 0) when the
 * section has no row with N free seats side by side; that is a normal answer, not an error.
 */
public record SeatSuggestionResponse(UUID sectionId, int requested, List<SuggestedSeat> seats, int totalPriceCents) {

    /** {@code label} is how a ticket names the seat, e.g. "C7". */
    public record SuggestedSeat(UUID id, String row, int number, String label) {
    }
}
