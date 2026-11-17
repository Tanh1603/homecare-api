package com.homecare.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.homecare.review.client.EvidenceVerificationClient;
import com.homecare.review.client.EvidenceVerificationClient.OrderLineEligibility;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ReviewPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ReviewDomainService service;

    @MockitoBean
    EvidenceVerificationClient verificationClient;

    @Test
    void persistsAndQueriesProductReviewOnPostgres() {
        UUID product = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        when(verificationClient.verifyOrderLineEligibility(line, "pg-reviewer"))
                .thenReturn(new OrderLineEligibility(true, product));
        var created = service.createProductReview("pg-reviewer", product, new CreateProductReviewRequest(line, 5, "Great"));
        assertThat(created.rating()).isEqualTo(5);
    }
}
