package com.homecare.review.service;

import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.homecare.common.error.DomainException;
import com.homecare.review.client.EvidenceVerificationClient;
import com.homecare.review.client.EvidenceVerificationClient.BookingEligibility;
import com.homecare.review.client.EvidenceVerificationClient.OrderLineEligibility;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.ReviewResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class ReviewDomainServiceTest {
    @Autowired WebApplicationContext webContext;

    @Test
    void productReviewEditDeleteChecksAuthorAndPathTarget() throws Exception {
        UUID product = UUID.randomUUID(); UUID line = UUID.randomUUID();
        when(verificationClient.verifyOrderLineEligibility(line, "review-owner")).thenReturn(new OrderLineEligibility(true, product));
        var review = service.createProductReview("review-owner", product, new CreateProductReviewRequest(line, 5, "Good"));
        var mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String path = "/api/v1/products/" + product + "/reviews/" + review.id();
        var owner = SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("review-owner"));
        mvc.perform(MockMvcRequestBuilders.patch(path)
                .with(SecurityMockMvcRequestPostProcessors.jwt())
                .contentType("application/json").content("{\"rating\":3}"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.patch(path).with(owner)
                .contentType("application/json").content("{\"rating\":3}"))
                .andExpect(MockMvcResultMatchers.status().isOk());
        assertThat(service.listProductReviews(product).getFirst().rating()).isEqualTo(3);
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/products/" + UUID.randomUUID() + "/reviews/" + review.id()).with(owner))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.delete(path).with(owner))
                .andExpect(MockMvcResultMatchers.status().isNoContent());
        assertThat(service.listProductReviews(product)).isEmpty();
    }

    @Autowired
    private ReviewDomainService service;

    @MockitoBean
    private EvidenceVerificationClient verificationClient;

    @Test
    void enforcesEligibilityAndPreventsDuplicateReviews() {
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(verificationClient.verifyBookingEligible(bookingId, "user-1"))
                .thenReturn(new BookingEligibility(false, null));

        assertThatThrownBy(() -> service.createServiceReview(
                "user-1",
                new CreateServiceReviewRequest(bookingId, providerId, 5, "Great service")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("REVIEW_NOT_ELIGIBLE");

        when(verificationClient.verifyBookingEligible(bookingId, "user-1"))
                .thenReturn(new BookingEligibility(true, providerId));

        ReviewResponse created = service.createServiceReview(
                "user-1",
                new CreateServiceReviewRequest(bookingId, providerId, 5, "Kỹ thuật viên làm rất sạch sẽ"));
        assertThat(created.rating()).isEqualTo(5);

        assertThatThrownBy(() -> service.createServiceReview(
                "user-1",
                new CreateServiceReviewRequest(bookingId, providerId, 4, "Duplicate review")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("REVIEW_ALREADY_EXISTS");
    }

    @Test
    void enforcesProviderAndProductMatchAndPreventsCrossTypeUpdate() {
        UUID bookingId = UUID.randomUUID();
        UUID actualProviderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID spoofedProviderId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(verificationClient.verifyBookingEligible(bookingId, "user-2"))
                .thenReturn(new BookingEligibility(true, actualProviderId));

        assertThatThrownBy(() -> service.createServiceReview(
                "user-2",
                new CreateServiceReviewRequest(bookingId, spoofedProviderId, 1, "Spoofed target provider")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        UUID orderLineId = UUID.randomUUID();
        UUID actualProductId = UUID.randomUUID();
        UUID spoofedProductId = UUID.randomUUID();
        when(verificationClient.verifyOrderLineEligibility(orderLineId, "user-2"))
                .thenReturn(new OrderLineEligibility(true, actualProductId));

        assertThatThrownBy(() -> service.createProductReview(
                "user-2",
                spoofedProductId,
                new CreateProductReviewRequest(orderLineId, 5, "Spoofed product target")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        ReviewResponse productReview = service.createProductReview(
                "user-2",
                actualProductId,
                new CreateProductReviewRequest(orderLineId, 5, "San pham dung chuan"));

        assertThatThrownBy(() -> service.updateServiceReview(
                "user-2",
                productReview.id(),
                new UpdateReviewRequest(4, "Updating product review via service endpoint")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");
    }
}
