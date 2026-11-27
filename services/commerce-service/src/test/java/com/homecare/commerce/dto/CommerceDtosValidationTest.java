package com.homecare.commerce.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CommerceDtosValidationTest {

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
    void cascadesValidationIntoOrderLineItems() {
        var invalid = new CreateOrderRequest(
                List.of(new OrderLineRequest(null, 0)),
                "123 Nguyen Hue");

        var violations = validator.validate(invalid);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("items[0].productId", "items[0].quantity");
    }
}
