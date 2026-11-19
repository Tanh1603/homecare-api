package com.homecare.common.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void validateRejectsInvalidBoundsAndAcceptsValidBounds() {
        assertThatCode(() -> PageResponse.validate(1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> PageResponse.validate(2, 100)).doesNotThrowAnyException();

        for (int[] invalid : new int[][] { { 0, 20 }, { -1, 20 }, { 1, 0 }, { 1, 101 } }) {
            assertThatThrownBy(() -> PageResponse.validate(invalid[0], invalid[1]))
                    .isInstanceOf(DomainException.class)
                    .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((DomainException) ex).errorCode())
                            .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name()));
        }
    }
}
