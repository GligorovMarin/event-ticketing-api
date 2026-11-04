package com.portfolio.ticketing.availability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Find me N seats together": the best block of N ADJACENT free seats in one row of a section.
 *
 * <p>A PURE algorithm: seats in, answer out, no database, no Spring. That makes it easy to test thoroughly
 * (AdjacentSeatFinderTest even compares it with a slow brute-force version on random seat maps).
 *
 * <h3>What "best" means (in this order)</h3>
 * <ol>
 *   <li><b>Row nearest the front</b> (row A beats row B): the first row that has ANY block wins;</li>
 *   <li>within that row, the block whose <b>middle is closest to the middle of the row</b>;</li>
 *   <li>if two blocks are equally central, the <b>leftmost</b>.</li>
 * </ol>
 *
 * <h3>How it works</h3>
 * For each row it builds a <b>prefix-sum array</b>: {@code unavailableBefore[i]} = how many unavailable seats
 * there are among the first i seats. Then "are seats i .. i+N-1 all free?" is just
 * {@code unavailableBefore[i+N] - unavailableBefore[i] == 0}: two array reads, whatever N is. Sliding the window
 * along the row therefore costs O(1) per position.
 *
 * <h3>Complexity</h3>
 * Let S be the number of seats in the section. Grouping into rows is O(S), the prefix sums are O(S) in total,
 * and trying every window position is O(S) in total. So the whole search is <b>O(S)</b> time and O(S) extra
 * memory: one pass, however large N is. (A naive version that re-checks N seats at every position would be
 * O(S x N).)
 *
 * <p>Used by AvailabilityService; the limit on N comes from order/OrderRules.
 */
public final class AdjacentSeatFinder {

    private AdjacentSeatFinder() {
    }

    /**
     * @param seatsInMapOrder every seat of ONE section: rows front to back, seats left to right within a row
     *                        (that is the order the database returns them in)
     * @param count           how many seats together, at least 1
     * @return the chosen seats, left to right, or empty if no row has {@code count} free seats side by side
     */
    public static Optional<List<SeatView>> find(List<SeatView> seatsInMapOrder, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }

        // Group the seats by row, remembering row order. LinkedHashMap = a hash map that iterates in insertion
        // order, which here is front row first.
        Map<String, List<SeatView>> rows = new LinkedHashMap<>();
        for (SeatView seat : seatsInMapOrder) {
            rows.computeIfAbsent(seat.rowLabel(), label -> new ArrayList<>()).add(seat);
        }

        // Rows are visited front to back, so the first row that contains a block gives the answer.
        for (List<SeatView> row : rows.values()) {
            Optional<List<SeatView>> block = bestBlockInRow(row, count);
            if (block.isPresent()) {
                return block;
            }
        }
        return Optional.empty();
    }

    /** The most central window of {@code count} adjacent free seats in one row, if any. */
    private static Optional<List<SeatView>> bestBlockInRow(List<SeatView> row, int count) {
        int seatsInRow = row.size();
        if (count > seatsInRow) {
            return Optional.empty();
        }

        // unavailableBefore[i] = number of unavailable seats among row[0 .. i-1]. One extra slot at the end.
        int[] unavailableBefore = new int[seatsInRow + 1];
        for (int i = 0; i < seatsInRow; i++) {
            unavailableBefore[i + 1] = unavailableBefore[i] + (row.get(i).isAvailable() ? 0 : 1);
        }

        int bestStart = -1;
        int bestOffCenter = Integer.MAX_VALUE;

        // Try every window [start, start + count). A row of S seats has S - count + 1 of them.
        for (int start = 0; start + count <= seatsInRow; start++) {
            int unavailableInWindow = unavailableBefore[start + count] - unavailableBefore[start];
            if (unavailableInWindow != 0) {
                continue; // somebody has a seat in this window
            }

            // How far is the window's middle from the row's middle? Both middles are doubled so that
            // everything stays in whole numbers (a middle can fall between two seats, e.g. 5.5).
            //   window middle (doubled) = 2 * start + (count - 1)      [0-based positions]
            //   row middle    (doubled) = seatsInRow - 1
            int offCenter = Math.abs((2 * start + (count - 1)) - (seatsInRow - 1));

            // Strictly smaller wins; on a tie the earlier (more left) start is kept because we scan left to right.
            if (offCenter < bestOffCenter) {
                bestOffCenter = offCenter;
                bestStart = start;
            }
        }

        if (bestStart < 0) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(row.subList(bestStart, bestStart + count)));
    }
}
