package com.homecare.booking.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class BookingExecutionDtos {

    private BookingExecutionDtos() {
    }

    public record WaitingForPartsRequest(
            @NotBlank String reason) {
    }

    public record ResumeFromPartsRequest(
            UUID technicianId,
            String technicianProfileId,
            String technicianName,
            String note) {
    }

    public record CustomerNoShowRequest(
            @NotNull UUID evidenceId,
            @NotBlank String callLogNote) {
    }

    public record SubmitCompletionRequest(
            @NotBlank String workSummary,
            List<UUID> evidenceIds) {
    }

    public record AcceptBookingRequest(
            String note) {
    }

    public record RequestRevisionRequest(
            @NotBlank String reason) {
    }

    public record StartReworkRequest(
            String note) {
    }

    public record RefuseRevisionRequest(
            @NotBlank String reason) {
    }
}
