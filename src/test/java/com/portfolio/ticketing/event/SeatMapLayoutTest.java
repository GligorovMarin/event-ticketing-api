package com.portfolio.ticketing.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UNIT test of the pure seat-map rules. No Spring, no database. */
class SeatMapLayoutTest {

    @ParameterizedTest(name = "row index {0} is labelled {1}")
    @CsvSource({"0,A", "1,B", "7,H", "25,Z"})
    @DisplayName("rows are labelled A to Z")
    void rowLabels(int index, String expected) {
        assertThat(SeatMapLayout.rowLabel(index)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 26, 100})
    @DisplayName("a row index outside 0..25 is refused")
    void rowIndexOutOfRange(int index) {
        assertThatThrownBy(() -> SeatMapLayout.rowLabel(index)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a section has rows x seatsPerRow seats")
    void seatCount() {
        assertThat(SeatMapLayout.positions(3, 4)).hasSize(12);
        assertThat(SeatMapLayout.positions(26, 50)).hasSize(1300);
    }

    @Test
    @DisplayName("seats come in seat-map order: front row first, left to right")
    void seatMapOrder() {
        List<SeatMapLayout.SeatPosition> positions = SeatMapLayout.positions(2, 3);

        assertThat(positions).containsExactly(
                new SeatMapLayout.SeatPosition("A", 1),
                new SeatMapLayout.SeatPosition("A", 2),
                new SeatMapLayout.SeatPosition("A", 3),
                new SeatMapLayout.SeatPosition("B", 1),
                new SeatMapLayout.SeatPosition("B", 2),
                new SeatMapLayout.SeatPosition("B", 3));
    }

    @Test
    @DisplayName("no two seats share a position")
    void positionsAreUnique() {
        assertThat(SeatMapLayout.positions(26, 50)).doesNotHaveDuplicates();
    }
}
