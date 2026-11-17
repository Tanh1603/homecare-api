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
        assertThat(service.productReviewsPage(product, 1, 20).items().getFirst().rating()).isEqualTo(3);
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/products/" + UUID.randomUUID() + "/reviews/" + review.id()).with(owner))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.delete(path).with(owner))
                .andExpect(MockMvcResultMatchers.status().isNoContent());
        assertThat(service.productReviewsPage(product, 1, 20).items()).isEmpty();

        var recreated = service.createProductReview("review-owner", product, new CreateProductReviewRequest(line, 4, "Again"));
        assertThat(recreated.id()).isEqualTo(review.id());
        assertThat(recreated.status()).isEqualTo("PUBLISHED");
        assertThat(service.productReviewsPage(product, 1, 20).items()).extracting("comment").containsExactly("Again");

        UUID line2 = UUID.randomUUID();
        when(verificationClient.verifyOrderLineEligibility(line2, "review-owner-2")).thenReturn(new OrderLineEligibility(true, product));
        service.createProductReview("review-owner-2", product, new CreateProductReviewRequest(line2, 5, "Second"));
        var prodPage = service.productReviewsPage(product, 1, 1);
        assertThat(prodPage.items()).hasSize(1);
        assertThat(prodPage.pagination().totalItems()).isEqualTo(2);

        UUID provider = UUID.randomUUID();
        UUID b1 = UUID.randomUUID(), b2 = UUID.randomUUID();
        when(verificationClient.verifyBookingEligible(b1, "u1")).thenReturn(new com.homecare.review.client.EvidenceVerificationClient.BookingEligibility(true, provider));
        when(verificationClient.verifyBookingEligible(b2, "u2")).thenReturn(new com.homecare.review.client.EvidenceVerificationClient.BookingEligibility(true, provider));
        service.createServiceReview("u1", new com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest(b1, provider, 5, "S1"));
        service.createServiceReview("u2", new com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest(b2, provider, 4, "S2"));
        var provPage = service.providerReviewsPage(provider, 1, 1);
        assertThat(provPage.items()).hasSize(1);
        assertThat(provPage.pagination().totalItems()).isEqualTo(2);
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
    void enforcesProviderAndProductMatchAndPreventsCrossTypeUpdate() throws Exception {
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

        // Owner-only mutation & fail-closed X-Organization-Id rejection
        assertThatThrownBy(() -> service.updateProductReview(
                "other-user",
                actualProductId,
                productReview.id(),
                new UpdateReviewRequest(1, "Hijack")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        var mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(MockMvcRequestBuilders.patch("/api/v1/products/" + actualProductId + "/reviews/" + productReview.id())
                        .header("X-Organization-Id", UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("user-2")))
                        .contentType("application/json").content("{\"rating\":4}"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }
}
