package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.provider.service.IntervalMath.LocalTimeRange;
import com.homecare.provider.service.IntervalMath.TimeRange;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalMathTest {

    @Test
    void subtractsMiddleAndPartialOverlapsCleanly() {
        TimeRange full = new TimeRange(Instant.parse("2026-12-01T01:00:00Z"), Instant.parse("2026-12-01T10:00:00Z"));
        TimeRange middle = new TimeRange(Instant.parse("2026-12-01T03:00:00Z"), Instant.parse("2026-12-01T05:00:00Z"));
        TimeRange tail = new TimeRange(Instant.parse("2026-12-01T09:00:00Z"), Instant.parse("2026-12-01T11:00:00Z"));

        List<TimeRange> result = IntervalMath.subtract(List.of(full), List.of(middle, tail));
        assertThat(result).containsExactly(
                new TimeRange(Instant.parse("2026-12-01T01:00:00Z"), Instant.parse("2026-12-01T03:00:00Z")),
                new TimeRange(Instant.parse("2026-12-01T05:00:00Z"), Instant.parse("2026-12-01T09:00:00Z")));
    }

    @Test
    void clipsPastIntervalsAndKeepsRemainingFuturePortion() {
        Instant now = Instant.parse("2026-12-01T03:30:00Z");
        List<TimeRange> clipped = IntervalMath.clipAfter(List.of(
                new TimeRange(Instant.parse("2026-12-01T01:00:00Z"), Instant.parse("2026-12-01T03:00:00Z")),
                new TimeRange(Instant.parse("2026-12-01T03:00:00Z"), Instant.parse("2026-12-01T05:00:00Z")),
                new TimeRange(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T08:00:00Z"))), now);

        assertThat(clipped).containsExactly(
                new TimeRange(Instant.parse("2026-12-01T03:30:00Z"), Instant.parse("2026-12-01T05:00:00Z")),
                new TimeRange(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T08:00:00Z")));
    }

    @Test
    void intersectsReleasedIntervalWithEligibleWindows() {
        TimeRange released = new TimeRange(Instant.parse("2026-12-01T04:00:00Z"), Instant.parse("2026-12-01T06:00:00Z"));
        TimeRange eligible = new TimeRange(Instant.parse("2026-12-01T01:00:00Z"), Instant.parse("2026-12-01T05:00:00Z"));

        assertThat(IntervalMath.intersect(List.of(released), List.of(eligible))).containsExactly(
                new TimeRange(Instant.parse("2026-12-01T04:00:00Z"), Instant.parse("2026-12-01T05:00:00Z")));
    }

    @Test
    void skipsDstGapAndOverlapEndpointsInsteadOfSilentlyShiftingHours() {
        ZoneId ny = ZoneId.of("America/New_York");
        // 2026-03-08 02:30 does not exist in America/New_York (spring forward 02:00 -> 03:00)
        assertThat(IntervalMath.toInstantRangeStrictDst(
                LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), LocalTime.of(5, 0), ny)).isEmpty();
        // 2026-11-01 01:30 occurs twice in America/New_York (fall back 02:00 -> 01:00)
        assertThat(IntervalMath.toInstantRangeStrictDst(
                LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), LocalTime.of(4, 0), ny)).isEmpty();
        // Normal Asia/Ho_Chi_Minh date converts cleanly
        assertThat(IntervalMath.toInstantRangeStrictDst(
                LocalDate.of(2026, 12, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), ZoneId.of("Asia/Ho_Chi_Minh")))
                .contains(new TimeRange(Instant.parse("2026-12-01T01:00:00Z"), Instant.parse("2026-12-01T05:00:00Z")));
    }

    @Test
    void subtractsPartialLocalLeaveFromWorkingShift() {
        List<LocalTimeRange> result = IntervalMath.subtractLocal(
                List.of(new LocalTimeRange(LocalTime.of(8, 0), LocalTime.of(17, 0))),
                List.of(new LocalTimeRange(LocalTime.of(12, 0), LocalTime.of(13, 0))));
        assertThat(result).containsExactly(
                new LocalTimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new LocalTimeRange(LocalTime.of(13, 0), LocalTime.of(17, 0)));
    }
}
