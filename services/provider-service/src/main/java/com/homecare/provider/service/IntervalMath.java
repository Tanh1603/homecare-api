package com.homecare.provider.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure half-open [start, end) interval math and DST-aware local-to-Instant conversion.
 */
public final class IntervalMath {

    private static final Logger log = LoggerFactory.getLogger(IntervalMath.class);

    public record TimeRange(Instant start, Instant end) {
        public TimeRange {
            if (start == null || end == null || !end.isAfter(start)) {
                throw new IllegalArgumentException("TimeRange requires end > start");
            }
        }

        public boolean overlaps(TimeRange other) {
            return start.isBefore(other.end) && end.isAfter(other.start);
        }

        public boolean covers(TimeRange other) {
            return !start.isAfter(other.start) && !end.isBefore(other.end);
        }
    }

    public record LocalTimeRange(LocalTime start, LocalTime end) {
        public LocalTimeRange {
            if (start == null || end == null || !end.isAfter(start)) {
                throw new IllegalArgumentException("LocalTimeRange requires end > start");
            }
        }
    }

    private IntervalMath() {
    }

    public static List<TimeRange> merge(List<TimeRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        List<TimeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(TimeRange::start).thenComparing(TimeRange::end));
        List<TimeRange> result = new ArrayList<>();
        TimeRange current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            TimeRange next = sorted.get(i);
            if (!next.start.isAfter(current.end)) {
                Instant maxEnd = next.end.isAfter(current.end) ? next.end : current.end;
                current = new TimeRange(current.start, maxEnd);
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    public static List<TimeRange> subtract(List<TimeRange> base, List<TimeRange> toRemove) {
        if (base == null || base.isEmpty()) {
            return List.of();
        }
        List<TimeRange> current = merge(base);
        if (toRemove == null || toRemove.isEmpty()) {
            return current;
        }
        List<TimeRange> removals = merge(toRemove);
        for (TimeRange rem : removals) {
            List<TimeRange> next = new ArrayList<>();
            for (TimeRange seg : current) {
                if (!seg.overlaps(rem)) {
                    next.add(seg);
                    continue;
                }
                if (seg.start.isBefore(rem.start)) {
                    next.add(new TimeRange(seg.start, rem.start));
                }
                if (seg.end.isAfter(rem.end)) {
                    next.add(new TimeRange(rem.end, seg.end));
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    public static List<TimeRange> intersect(List<TimeRange> a, List<TimeRange> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return List.of();
        }
        List<TimeRange> left = merge(a);
        List<TimeRange> right = merge(b);
        List<TimeRange> result = new ArrayList<>();
        for (TimeRange l : left) {
            for (TimeRange r : right) {
                if (!l.overlaps(r)) {
                    continue;
                }
                Instant maxStart = l.start.isAfter(r.start) ? l.start : r.start;
                Instant minEnd = l.end.isBefore(r.end) ? l.end : r.end;
                if (minEnd.isAfter(maxStart)) {
                    result.add(new TimeRange(maxStart, minEnd));
                }
            }
        }
        return merge(result);
    }

    public static List<TimeRange> clipAfter(List<TimeRange> ranges, Instant floor) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        List<TimeRange> result = new ArrayList<>();
        for (TimeRange r : ranges) {
            if (!r.end.isAfter(floor)) {
                continue;
            }
            Instant clippedStart = r.start.isBefore(floor) ? floor : r.start;
            if (r.end.isAfter(clippedStart)) {
                result.add(new TimeRange(clippedStart, r.end));
            }
        }
        return merge(result);
    }

    public static boolean isCoveredBy(TimeRange target, List<TimeRange> coveringRanges) {
        if (coveringRanges == null || coveringRanges.isEmpty()) {
            return false;
        }
        for (TimeRange merged : merge(coveringRanges)) {
            if (merged.covers(target)) {
                return true;
            }
        }
        return false;
    }

    public static List<LocalTimeRange> subtractLocal(List<LocalTimeRange> base, List<LocalTimeRange> toRemove) {
        if (base == null || base.isEmpty()) {
            return List.of();
        }
        List<LocalTimeRange> current = mergeLocal(base);
        if (toRemove == null || toRemove.isEmpty()) {
            return current;
        }
        for (LocalTimeRange rem : mergeLocal(toRemove)) {
            List<LocalTimeRange> next = new ArrayList<>();
            for (LocalTimeRange seg : current) {
                if (!seg.start.isBefore(rem.end) || !seg.end.isAfter(rem.start)) {
                    next.add(seg);
                    continue;
                }
                if (seg.start.isBefore(rem.start)) {
                    next.add(new LocalTimeRange(seg.start, rem.start));
                }
                if (seg.end.isAfter(rem.end)) {
                    next.add(new LocalTimeRange(rem.end, seg.end));
                }
            }
            current = next;
        }
        return current;
    }

    public static List<LocalTimeRange> mergeLocal(List<LocalTimeRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        List<LocalTimeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(LocalTimeRange::start).thenComparing(LocalTimeRange::end));
        List<LocalTimeRange> result = new ArrayList<>();
        LocalTimeRange current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            LocalTimeRange next = sorted.get(i);
            if (!next.start.isAfter(current.end)) {
                LocalTime maxEnd = next.end.isAfter(current.end) ? next.end : current.end;
                current = new LocalTimeRange(current.start, maxEnd);
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    /**
     * Converts a local (date, startTime, endTime) in {@code zone} to an absolute half-open [Instant, Instant).
     * Per BR-SCH-058: if either endpoint falls in a DST gap (nonexistent local time) or DST overlap (ambiguous local time),
     * logs a structured warning and returns {@link Optional#empty()} instead of silently shifting working hours.
     */
    public static Optional<TimeRange> toInstantRangeStrictDst(
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            ZoneId zone) {
        LocalDateTime startLdt = LocalDateTime.of(date, startTime);
        LocalDateTime endLdt = LocalDateTime.of(date, endTime);
        ZoneRules rules = zone.getRules();

        ZoneOffsetTransition startTrans = rules.getTransition(startLdt);
        if (startTrans != null) {
            log.warn("[SCHEDULE-DST-SKIP] Skipping interval date={} start={} end={} zone={} reason=start_in_dst_{}",
                    date, startTime, endTime, zone, startTrans.isGap() ? "gap" : "overlap");
            return Optional.empty();
        }
        ZoneOffsetTransition endTrans = rules.getTransition(endLdt);
        if (endTrans != null) {
            log.warn("[SCHEDULE-DST-SKIP] Skipping interval date={} start={} end={} zone={} reason=end_in_dst_{}",
                    date, startTime, endTime, zone, endTrans.isGap() ? "gap" : "overlap");
            return Optional.empty();
        }
        Instant start = startLdt.atZone(zone).toInstant();
        Instant end = endLdt.atZone(zone).toInstant();
        if (!end.isAfter(start)) {
            log.warn("[SCHEDULE-DST-SKIP] Skipping degenerate interval date={} start={} end={} zone={}",
                    date, startTime, endTime, zone);
            return Optional.empty();
        }
        return Optional.of(new TimeRange(start, end));
    }
}
