package com.homecare.provider.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.provider.dto.RecurringScheduleDtos.DayTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.LocalTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertScheduleExceptionsRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertWeeklyScheduleRequest;
import com.homecare.provider.entity.ScheduleExceptionType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecurringScheduleValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void cascadesValidationIntoWeeklyScheduleIntervals() {
        var invalid = new UpsertWeeklyScheduleRequest(
                "Asia/Ho_Chi_Minh",
                LocalDate.of(2026, 12, 1),
                null,
                List.of(new DayTimeIntervalDto(DayOfWeek.MONDAY, null, LocalTime.of(12, 0))));

        var violations = validator.validate(invalid);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("intervals[0].startTime");
    }

    @Test
    void cascadesValidationIntoScheduleExceptionIntervals() {
        var invalid = new UpsertScheduleExceptionsRequest(
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 1),
                ScheduleExceptionType.CUSTOM_HOURS,
                "Override",
                List.of(new LocalTimeIntervalDto(LocalTime.of(8, 0), null)));

        var violations = validator.validate(invalid);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("intervals[0].endTime");
    }
}
