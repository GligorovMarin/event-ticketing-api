package com.portfolio.ticketing.availability;

import com.portfolio.ticketing.event.SeatStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT tests of the "find N seats together" algorithm: hand-written cases first (they document the rules),
 * then a RANDOMIZED comparison with a deliberately simple brute-force version, which finds edge cases nobody
 * thought to write by hand.
 */
class AdjacentSeatFinderTest {

    private static final UUID SECTION = UUID.randomUUID();

    /**
     * Builds a section from a picture. Each string is one row, front row first; '.' is a free seat and 'X' is a
     * taken one. {@code seats(".X.", "...")} is two rows of three seats where A2 is taken.
     */
    private static List<SeatView> seats(String... rows) {
        List<SeatView> seats = new ArrayList<>();
        for (int r = 0; r < rows.length; r++) {
            String label = String.valueOf((char) ('A' + r));
            for (int i = 0; i < rows[r].length(); i++) {
                SeatStatus status = rows[r].charAt(i) == '.' ? SeatStatus.AVAILABLE : SeatStatus.SOLD;
                seats.add(new SeatView(UUID.randomUUID(), SECTION, label, i + 1, status));
            }
        }
        return seats;
    }

    /** "A4 A5 A6" for readable assertions. */
    private static String labels(Optional<List<SeatView>> block) {
        return block.map(list -> list.stream().map(s -> s.rowLabel() + s.seatNumber()).reduce((a, b) -> a + " " + b).orElse(""))
                .orElse("none");
    }

    @Nested
    @DisplayName("which block is best")
    class Choosing {

        @Test
        @DisplayName("in an empty row the block is as central as possible")
        void centralBlock() {
            // Row of 10: the middle is between seats 5 and 6. Four seats centred there are 4,5,6,7.
            assertThat(labels(AdjacentSeatFinder.find(seats(".........."), 4))).isEqualTo("A4 A5 A6 A7");
        }

        @Test
        @DisplayName("with an odd number of seats in an odd row, the block sits exactly in the middle")
        void exactMiddle() {
            assertThat(labels(AdjacentSeatFinder.find(seats("........."), 3))).isEqualTo("A4 A5 A6");
        }

        @Test
        @DisplayName("a tie between two equally central blocks goes to the LEFT one")
        void tieGoesLeft() {
            // Row of 10, three seats: 4,5,6 (middle 5) and 5,6,7 (middle 6) are equally far from the row middle 5.5.
            assertThat(labels(AdjacentSeatFinder.find(seats(".........."), 3))).isEqualTo("A4 A5 A6");
        }

        @Test
        @DisplayName("a single seat is the most central free seat")
        void singleSeat() {
            assertThat(labels(AdjacentSeatFinder.find(seats("....."), 1))).isEqualTo("A3");
            assertThat(labels(AdjacentSeatFinder.find(seats("..X.."), 1))).isEqualTo("A2");
        }

        @Test
        @DisplayName("the front row wins even if a later row has a more central block")
        void frontRowWins() {
            // Row A is free only at its far left; row B is wide open in the middle. Front row still wins.
            assertThat(labels(AdjacentSeatFinder.find(seats("..XXXXXXXX", ".........."), 2))).isEqualTo("A1 A2");
        }

        @Test
        @DisplayName("skips a row that is blocked and takes the next one")
        void nextRowWhenFrontIsFull() {
            assertThat(labels(AdjacentSeatFinder.find(seats("XXXXX", "....."), 3))).isEqualTo("B2 B3 B4");
        }

        @Test
        @DisplayName("works around a taken seat in the middle of the row")
        void aroundATakenSeat() {
            // Seats 1-4 free, 5 taken, 6-10 free. For 3 seats the candidates are 1-3, 2-4, 6-8, 7-9, 8-10.
            // Row middle is 5.5; block 6,7,8 (middle 7) is 1.5 away, block 2,3,4 (middle 3) is 2.5 away.
            assertThat(labels(AdjacentSeatFinder.find(seats("....X....."), 3))).isEqualTo("A6 A7 A8");
        }

        @Test
        @DisplayName("seats separated by a taken seat are NOT adjacent")
        void separatedSeatsDoNotCount() {
            assertThat(labels(AdjacentSeatFinder.find(seats(".X.X."), 2))).isEqualTo("none");
        }
    }

    @Nested
    @DisplayName("when there is no answer")
    class NoAnswer {

        @Test
        @DisplayName("more seats requested than a row has")
        void biggerThanARow() {
            assertThat(labels(AdjacentSeatFinder.find(seats("....", "...."), 5))).isEqualTo("none");
        }

        @Test
        @DisplayName("every seat taken")
        void everythingTaken() {
            assertThat(labels(AdjacentSeatFinder.find(seats("XXX", "XXX"), 1))).isEqualTo("none");
        }

        @Test
        @DisplayName("no seats at all")
        void emptySection() {
            assertThat(AdjacentSeatFinder.find(List.of(), 2)).isEmpty();
        }

        @Test
        @DisplayName("a count below 1 is a programming error")
        void invalidCount() {
            assertThatThrownBy(() -> AdjacentSeatFinder.find(seats("..."), 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("boundaries")
    class Boundaries {

        @Test
        @DisplayName("a request for exactly the whole row works when the row is free")
        void wholeRow() {
            assertThat(labels(AdjacentSeatFinder.find(seats("...."), 4))).isEqualTo("A1 A2 A3 A4");
        }

        @Test
        @DisplayName("...and fails when a single seat in the row is taken")
        void wholeRowWithOneTaken() {
            assertThat(labels(AdjacentSeatFinder.find(seats("..X."), 4))).isEqualTo("none");
        }

        @Test
        @DisplayName("held seats are not available either, only AVAILABLE ones count")
        void heldSeatsAreNotFree() {
            List<SeatView> row = new ArrayList<>(seats("...."));
            row.set(1, new SeatView(UUID.randomUUID(), SECTION, "A", 2, SeatStatus.HELD));

            assertThat(labels(AdjacentSeatFinder.find(row, 2))).isEqualTo("A3 A4");
        }

        @Test
        @DisplayName("the answer does not change the input list")
        void inputIsNotModified() {
            List<SeatView> input = seats("....", "....");
            List<SeatView> copy = List.copyOf(input);

            AdjacentSeatFinder.find(input, 2);

            assertThat(input).isEqualTo(copy);
        }
    }

    @Nested
    @DisplayName("compared with a brute-force version on random seat maps")
    class AgainstBruteForce {

        /**
         * The obviously-correct, slow version: for every row and every start, re-check all N seats one by one,
         * and keep the best by (row, distance from the middle, start). If the fast algorithm is right it must
         * give the same answer on every input.
         */
        private Optional<List<SeatView>> bruteForce(List<SeatView> seats, int count) {
            List<SeatView> best = null;
            int bestRow = Integer.MAX_VALUE;
            int bestOffCenter = Integer.MAX_VALUE;

            // Rows of the section, in order.
            List<String> rowLabels = seats.stream().map(SeatView::rowLabel).distinct().toList();
            for (int rowIndex = 0; rowIndex < rowLabels.size(); rowIndex++) {
                String label = rowLabels.get(rowIndex);
                List<SeatView> row = seats.stream().filter(s -> s.rowLabel().equals(label)).toList();

                for (int start = 0; start + count <= row.size(); start++) {
                    boolean allFree = true;
                    for (int i = start; i < start + count; i++) {
                        if (!row.get(i).isAvailable()) {
                            allFree = false;
                        }
                    }
                    if (!allFree) {
                        continue;
                    }

                    int offCenter = Math.abs((2 * start + (count - 1)) - (row.size() - 1));
                    boolean better = rowIndex < bestRow || (rowIndex == bestRow && offCenter < bestOffCenter);
                    if (better) {
                        best = row.subList(start, start + count);
                        bestRow = rowIndex;
                        bestOffCenter = offCenter;
                    }
                }
            }
            return Optional.ofNullable(best).map(List::copyOf);
        }

        @Test
        @DisplayName("agrees on 2000 random sections (same seed every run, so a failure is reproducible)")
        void agreesWithBruteForce() {
            Random random = new Random(20260705L);

            for (int round = 0; round < 2000; round++) {
                int rows = 1 + random.nextInt(6);
                int perRow = 1 + random.nextInt(14);
                // Random density of taken seats, from empty halls to nearly full ones.
                double takenProbability = random.nextDouble();

                String[] picture = new String[rows];
                for (int r = 0; r < rows; r++) {
                    StringBuilder row = new StringBuilder();
                    for (int s = 0; s < perRow; s++) {
                        row.append(random.nextDouble() < takenProbability ? 'X' : '.');
                    }
                    picture[r] = row.toString();
                }
                List<SeatView> section = seats(picture);
                int count = 1 + random.nextInt(6);

                assertThat(AdjacentSeatFinder.find(section, count))
                        .as("section %s, count %d", String.join("/", picture), count)
                        .isEqualTo(bruteForce(section, count));
            }
        }
    }
}
