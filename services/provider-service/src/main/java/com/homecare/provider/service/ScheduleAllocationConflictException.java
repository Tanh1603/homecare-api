package com.homecare.provider.service;

import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleConflictItem;
import java.util.List;

/**
 * Thrown when a schedule edit, termination, leave exception, or slot deletion conflicts
 * with one or more existing BOOKED schedule windows (BR-SCH-016, BR-SCH-022, BR-SCH-031).
 */
public class ScheduleAllocationConflictException extends RuntimeException {

    private final List<ScheduleConflictItem> conflicts;

    public ScheduleAllocationConflictException(String message, List<ScheduleConflictItem> conflicts) {
        super(message);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public List<ScheduleConflictItem> conflicts() {
        return conflicts;
    }
}
