package com.portfolio.ticketing.event;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure rules about the SHAPE of a seat map: how rows are named and which seats a section contains.
 * No database, no Spring: numbers and strings in, numbers and strings out, so it is trivial to test.
 *
 * <p>Rows are labelled A, B, C ... Z (so at most 26 rows) and seats in a row are numbered 1, 2, 3 ...
 * "Row C, seat 7" is how a ticket names a seat. These limits are also enforced by CHECK constraints in
 * migration V2; the code checks first so the client gets a friendly message.
 */
public final class SeatMapLayout {

    public static final int MAX_ROWS = 26;
    public static final int MAX_SEATS_PER_ROW = 50;
    /** Upper bound for a whole event (all sections), a sanity limit for the hold and availability queries. */
    public static final int MAX_SEATS_PER_EVENT = 5000;

    private SeatMapLayout() {
    }

    /** Where one seat sits in a section. */
    public record SeatPosition(String rowLabel, int seatNumber) {
    }

    /** 0 -> "A", 1 -> "B", ... 25 -> "Z". */
    public static String rowLabel(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= MAX_ROWS) {
            throw new IllegalArgumentException("row index must be between 0 and " + (MAX_ROWS - 1));
        }
        return String.valueOf((char) ('A' + rowIndex));
    }

    /**
     * Every seat of a section, front row first, left to right: A1, A2 ... A20, B1 ... This order is the
     * "seat-map order" that the availability view and the adjacent-seat search rely on.
     */
    public static List<SeatPosition> positions(int rows, int seatsPerRow) {
        List<SeatPosition> positions = new ArrayList<>(rows * seatsPerRow);

        // Outer loop: rows from the front. Inner loop: seats from the left.
        for (int row = 0; row < rows; row++) {
            String label = rowLabel(row);
            for (int seat = 1; seat <= seatsPerRow; seat++) {
                positions.add(new SeatPosition(label, seat));
            }
        }
        return positions;
    }
}
